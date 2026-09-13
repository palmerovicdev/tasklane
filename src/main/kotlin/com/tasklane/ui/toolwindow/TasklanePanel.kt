package com.tasklane.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeUIHelper
import com.tasklane.TasklaneBundle
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.service.TaskService
import com.tasklane.ui.editor.TaskEditDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Vista principal. Observa el snapshot del servicio y le envía comandos; no toca
 * el almacén ni contiene lógica de negocio.
 *
 * Fase 1: lista plana de un único pseudo-repositorio. Las tabs por estado, la
 * agrupación por fecha y el selector de repositorio llegan en las Fases 2 y 3.
 */
internal class TasklanePanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val service = project.service<TaskService>()
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

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.emptyText.text = TasklaneBundle.message("toolwindow.tree.empty")
        tree.emptyText.appendLine(TasklaneBundle.message("toolwindow.tree.empty.action"))
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        toolbar = buildToolbar()

        installShortcuts()

        uiScope.launch {
            service.snapshot.collect { snap ->
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { render(snap) }
            }
        }
    }

    // ------------------------------------------------------------- rendering

    private fun render(snap: TasklaneSnapshot) {
        snapshot = snap
        renderer.config = snap.config

        val previouslySelected = selectedTasks().map { it.id }.toSet()

        root.removeAllChildren()
        val ordered = snap.activeTasks.sortedWith(
            // Abiertas primero, y dentro de cada grupo por prioridad y recencia.
            compareBy<Task> { snap.config.stateOrDefault(it.stateId).terminal }
                .thenByDescending { snap.config.priorityOrDefault(it.priorityId).order }
                .thenByDescending { (it.completedAt ?: it.updatedAt).toEpochMilli() },
        )
        for (task in ordered) {
            root.add(TaskNode(task, snap.config.stateOrDefault(task.stateId).terminal))
        }
        (tree.model as DefaultTreeModel).reload()

        restoreSelection(previouslySelected)
    }

    /** La selección se restaura por [TaskId], no por índice: reordenar no debe moverla. */
    private fun restoreSelection(ids: Set<TaskId>) {
        if (ids.isEmpty()) return
        val paths = root.children().asSequence()
            .filterIsInstance<TaskNode>()
            .filter { it.task.id in ids }
            .map { javax.swing.tree.TreePath(arrayOf<Any>(root, it)) }
            .toList()
        if (paths.isNotEmpty()) tree.selectionPaths = paths.toTypedArray()
    }

    private fun selectedTasks(): List<Task> =
        tree.selectionPaths.orEmpty()
            .mapNotNull { (it.lastPathComponent as? TaskNode)?.task }

    // -------------------------------------------------------------- acciones

    private fun buildToolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup(newAction(), editAction(), deleteAction())
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    private fun newAction() = object : DumbAwareAction(
        TasklaneBundle.message("action.task.new.text"),
        TasklaneBundle.message("action.task.new.description"),
        AllIcons.General.Add,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !service.isReadOnly(snapshot.activeRepo)
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
            e.presentation.isEnabled =
                selectedTasks().size == 1 && !service.isReadOnly(snapshot.activeRepo)
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
            e.presentation.isEnabled =
                selectedTasks().isNotEmpty() && !service.isReadOnly(snapshot.activeRepo)
        }

        override fun actionPerformed(e: AnActionEvent) = deleteSelected()
    }

    private fun installShortcuts() {
        editAction().registerCustomShortcutSet(CommonShortcuts.ENTER, tree, this)
        deleteAction().registerCustomShortcutSet(CommonShortcuts.getDelete(), tree, this)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (selectedTasks().size != 1) return false
                editSelected()
                return true
            }
        }.installOn(tree)
    }

    private fun createTask() {
        val dialog = TaskEditDialog(project, snapshot.config)
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
}
