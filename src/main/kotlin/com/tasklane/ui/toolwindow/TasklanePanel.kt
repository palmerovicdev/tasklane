package com.tasklane.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeUIHelper
import com.intellij.util.ui.tree.TreeUtil
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.TasklaneConfigService
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.DateGrouper
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.service.TaskService
import com.tasklane.ui.editor.TaskEditDialog
import com.tasklane.ui.settings.TasklaneConfigurable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.JComponent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Una pestaña de la Tool Window: las tareas de **un** estado.
 *
 * Observa el snapshot del servicio y le envía comandos; no toca el almacén ni
 * contiene lógica de negocio. El filtrado, la ordenación y la agrupación se hacen
 * fuera del EDT; en el hilo de UI sólo queda construir nodos y recargar.
 */
internal class TasklanePanel(
    private val project: Project,
    val stateId: StateId,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = TaskService.getInstance(project)
    private val renderer = TaskTreeRenderer()
    private val root = CheckedTreeNode("tasklane")

    private val tree = object : CheckboxTree(
        renderer,
        root,
        // Nada de propagación a padres ni a hijos: completar una tarea solo
        // afecta a esa tarea.
        CheckboxTreeBase.CheckPolicy(false, false, false, false),
    ) {
        override fun onNodeStateChanged(node: CheckedTreeNode?) {
            val task = (node as? TaskNode)?.task ?: return
            service.apply(TaskCommand.ToggleComplete(task.repo, task.id))
        }
    }

    /**
     * Scope propio porque la vida de este panel es más corta que la del proyecto.
     * Se cancela en [dispose], que la tool window invoca vía Disposer.
     */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var snapshot: TasklaneSnapshot = TasklaneSnapshot.EMPTY

    /**
     * Grupos que el usuario ha plegado a mano. Se recuerdan por [DateGroup] y no por
     * fila: repintar reconstruye el árbol entero, y por índice se perdería.
     */
    private val collapsed = mutableSetOf<DateGroup>()

    /** `reload()` dispara eventos de plegado; sin esta guarda se tomarían por gestos del usuario. */
    private var rendering = false

    private val newAction = newAction()
    private val editAction = editAction()
    private val deleteAction = deleteAction()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.emptyText.text = TasklaneBundle.message("toolwindow.tree.empty")
        tree.emptyText.appendLine(TasklaneBundle.message("toolwindow.tree.empty.action"))
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = track(event, expanded = true)
            override fun treeCollapsed(event: TreeExpansionEvent) = track(event, expanded = false)

            private fun track(event: TreeExpansionEvent, expanded: Boolean) {
                if (rendering) return
                val group = (event.path.lastPathComponent as? GroupNode)?.group ?: return
                if (expanded) collapsed -= group else collapsed += group
            }
        })

        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        toolbar = buildToolbar()

        installShortcuts()

        uiScope.launch {
            service.snapshot.collect { snap ->
                // Filtrar, ordenar y agrupar fuera del EDT: es el trabajo que crece
                // con el número de tareas.
                val sections = buildSections(snap)
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    render(snap, sections)
                }
            }
        }

        uiScope.launch {
            service.reveal.collect { ids ->
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { reveal(ids) }
            }
        }
    }

    // ------------------------------------------------------------- modelo

    /** Un bloque del árbol. [group] nulo == este estado no agrupa. */
    private class Section(val group: DateGroup?, val tasks: List<Task>)

    private fun buildSections(snap: TasklaneSnapshot): List<Section> {
        val state = snap.config.state(stateId) ?: return emptyList()
        val mine = snap.activeTasks.filter { it.stateId == stateId }

        // La prioridad manda sobre la fecha: es lo que se mira primero en una lista
        // de pendientes. Dentro de la misma prioridad, lo más reciente arriba.
        val order = compareByDescending<Task> { snap.config.priorityOrDefault(it.priorityId).order }
            .thenByDescending { (DateGrouper.anchorOf(it, state.anchor) ?: it.updatedAt).toEpochMilli() }

        if (state.grouping == Grouping.NONE) {
            return listOf(Section(null, mine.sortedWith(order)))
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        return mine
            .groupBy { DateGrouper.groupOf(DateGrouper.anchorOf(it, state.anchor), today, zone, firstDayOfWeek) }
            .toSortedMap()
            .map { (group, tasks) -> Section(group, tasks.sortedWith(order)) }
    }

    // ------------------------------------------------------------- rendering

    private fun render(snap: TasklaneSnapshot, sections: List<Section>) {
        snapshot = snap
        renderer.config = snap.config
        val terminal = snap.config.state(stateId)?.terminal ?: false

        val previouslySelected = selectedTasks().map { it.id }.toSet()

        rendering = true
        try {
            root.removeAllChildren()
            for (section in sections) {
                if (section.group == null) {
                    section.tasks.forEach { root.add(TaskNode(it, terminal)) }
                } else {
                    val group = GroupNode(section.group, section.tasks.size)
                    section.tasks.forEach { group.add(TaskNode(it, terminal)) }
                    root.add(group)
                }
            }
            (tree.model as DefaultTreeModel).reload()
            expandGroups()
        } finally {
            rendering = false
        }

        restoreSelection(previouslySelected)
    }

    private fun expandGroups() {
        for (node in root.children().asSequence().filterIsInstance<GroupNode>()) {
            if (node.group !in collapsed) tree.expandPath(TreePath(node.path))
        }
    }

    /** La selección se restaura por [TaskId], no por índice: reordenar no debe moverla. */
    private fun restoreSelection(ids: Set<TaskId>) {
        if (ids.isEmpty()) return
        val paths = taskNodes().filter { it.task.id in ids }.map { TreePath(it.path) }.toList()
        if (paths.isNotEmpty()) tree.selectionPaths = paths.toTypedArray()
    }

    /**
     * Atiende «enséñame estas tareas» —hoy, la acción de la notificación de remapeo—.
     * Si ninguna cae en esta pestaña no hace nada, así que las demás pueden ignorarla
     * sin coordinarse entre sí.
     */
    private fun reveal(ids: Set<TaskId>) {
        val paths = taskNodes().filter { it.task.id in ids }.map { TreePath(it.path) }.toList()
        if (paths.isEmpty()) return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        toolWindow?.contentManager?.getContent(this)?.let { toolWindow.contentManager.setSelectedContent(it) }
        toolWindow?.activate(null, false)

        tree.selectionPaths = paths.toTypedArray()
        TreeUtil.showRowCentered(tree, tree.getRowForPath(paths.first()), false, true)
    }

    private fun taskNodes(): Sequence<TaskNode> =
        TreeUtil.treeNodeTraverser(root).traverse().filter(TaskNode::class.java).asSequence()

    private fun selectedTasks(): List<Task> =
        tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? TaskNode)?.task }

    // -------------------------------------------------------------- acciones

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup(
            newAction,
            editAction,
            deleteAction,
            Separator.getInstance(),
            groupByDateAction(),
            settingsAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    private fun editable(): Boolean = !service.isReadOnly(snapshot.activeRepo)

    private fun newAction() = object : DumbAwareAction(
        TasklaneBundle.message("action.task.new.text"),
        TasklaneBundle.message("action.task.new.description"),
        AllIcons.General.Add,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = editable()
        }

        override fun actionPerformed(e: AnActionEvent) = createTask()
    }

    private fun editAction() = object : DumbAwareAction(
        TasklaneBundle.message("action.task.edit.text"),
        TasklaneBundle.message("action.task.edit.description"),
        AllIcons.Actions.Edit,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedTasks().size == 1 && editable()
        }

        override fun actionPerformed(e: AnActionEvent) = editSelected()
    }

    private fun deleteAction() = object : DumbAwareAction(
        TasklaneBundle.message("action.task.delete.text"),
        TasklaneBundle.message("action.task.delete.description"),
        AllIcons.General.Remove,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedTasks().isNotEmpty() && editable()
        }

        override fun actionPerformed(e: AnActionEvent) = deleteSelected()
    }

    /**
     * La agrupación es una propiedad **del estado**, no de la vista, así que el
     * toggle escribe en la configuración del proyecto y vuelve por el snapshot.
     * Es lo que hace que quede recordada al reabrir y que se comparta con el equipo.
     */
    private fun groupByDateAction() = object : DumbAwareToggleAction(
        TasklaneBundle.message("action.group.byDate.text"),
        TasklaneBundle.message("action.group.byDate.description"),
        AllIcons.Actions.GroupByPackage,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean =
            snapshot.config.state(stateId)?.grouping == Grouping.BY_DATE

        override fun setSelected(e: AnActionEvent, selected: Boolean) {
            val configService = TasklaneConfigService.getInstance(project)
            val config = configService.config.value
            configService.update(
                config.copy(
                    states = config.states.map { state ->
                        if (state.id != stateId) state
                        else state.copy(grouping = if (selected) Grouping.BY_DATE else Grouping.NONE)
                    },
                ),
            )
        }
    }

    private fun settingsAction() = object : DumbAwareAction(
        TasklaneBundle.message("action.settings.text"),
        TasklaneBundle.message("action.settings.description"),
        AllIcons.General.Settings,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, TasklaneConfigurable::class.java)
        }
    }

    private fun installShortcuts() {
        editAction.registerCustomShortcutSet(CommonShortcuts.ENTER, tree, this)
        deleteAction.registerCustomShortcutSet(CommonShortcuts.getDelete(), tree, this)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (selectedTasks().size != 1) return false
                editSelected()
                return true
            }
        }.installOn(tree)
    }

    private fun createTask() {
        // La pestaña activa es el destino natural de una tarea nueva.
        val dialog = TaskEditDialog(project, snapshot.config, initialState = stateId)
        if (!dialog.showAndGet()) return
        service.apply(
            TaskCommand.Create(snapshot.activeRepo, dialog.body, dialog.stateId, dialog.priorityId),
        )
    }

    private fun editSelected() {
        val task = selectedTasks().singleOrNull() ?: return
        val dialog = TaskEditDialog(project, snapshot.config, task.body, task.stateId, task.priorityId)
        if (!dialog.showAndGet()) return
        with(service) {
            apply(TaskCommand.UpdateBody(task.repo, task.id, dialog.body))
            apply(TaskCommand.ChangeState(task.repo, task.id, dialog.stateId))
            apply(TaskCommand.ChangePriority(task.repo, task.id, dialog.priorityId))
        }
    }

    private fun deleteSelected() {
        val tasks = selectedTasks().ifEmpty { return }
        service.apply(TaskCommand.Delete(snapshot.activeRepo, tasks.map { it.id }))
    }

    override fun dispose() {
        uiScope.cancel()
    }

    companion object {
        const val TOOL_WINDOW_ID = "Tasklane"
    }
}
