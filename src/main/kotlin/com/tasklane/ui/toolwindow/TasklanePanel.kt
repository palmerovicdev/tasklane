package com.tasklane.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.SearchTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.TasklaneConfigService
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.export.ExportScope
import com.tasklane.domain.export.TaskExporter
import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.DateGrouper
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.service.SearchResults
import com.tasklane.service.SearchService
import com.tasklane.service.TaskService
import com.tasklane.service.ViewService
import com.tasklane.ui.actions.TasklaneDataKeys
import com.tasklane.ui.common.GroupLabels
import com.tasklane.ui.editor.TaskEditDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
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
 *
 * Desde la Fase 4 las acciones no se construyen aquí: están declaradas en
 * `plugin.xml` y encuentran esta pestaña por el `DataContext`, vía
 * [TasklaneDataKeys.PANEL]. Por eso los métodos de edición son públicos —son la
 * superficie que consumen esas acciones— y por eso el panel implementa
 * `uiDataSnapshot`.
 */
internal class TasklanePanel(
    private val project: Project,
    val stateId: StateId,
    /** Cómo se pide cambiar de estado. Lo atiende [TasklaneWindow], que es quien tiene las tarjetas. */
    private val onSelectState: (StateId) -> Unit,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = TaskService.getInstance(project)
    private val search = SearchService.getInstance(project)
    private val view = ViewService.getInstance(project)
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
     * El historial se persiste con el nombre de propiedad, así que las consultas
     * recientes sobreviven al reinicio sin que haya que guardarlas a mano.
     */
    private val searchField = SearchTextField(HISTORY_PROPERTY)

    /** Los estados, encima del buscador. Cada panel pinta la suya y marca la propia. */
    private val tabs = StateTabRow(onSelectState)

    /**
     * Scope propio porque la vida de este panel es más corta que la del proyecto.
     * Se cancela en [dispose], que la tool window invoca vía Disposer.
     */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var snapshot: TasklaneSnapshot = TasklaneSnapshot.EMPTY

    /**
     * Las secciones tal y como se pintaron por última vez. Se guardan porque la
     * exportación exporta **lo que se ve**: el mismo orden, los mismos grupos y sólo
     * lo que la búsqueda dejó pasar. Recalcularlas al exportar podría dar otra cosa.
     */
    private var sections: List<Section> = emptyList()

    /**
     * Grupos que el usuario ha plegado a mano. Se recuerdan por [GroupKey] y no por
     * fila: repintar reconstruye el árbol entero, y por índice se perdería.
     */
    private val collapsed = mutableSetOf<GroupKey>()

    /** `reload()` dispara eventos de plegado; sin esta guarda se tomarían por gestos del usuario. */
    private var rendering = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = false
        // Ni el árbol ni el buscador llevan etiqueta visible —el sitio manda—, así que
        // sin esto un lector de pantalla anuncia «árbol» y «campo de texto» a secas.
        tree.accessibleContext.accessibleName = TasklaneBundle.message("a11y.tree")
        searchField.textEditor.accessibleContext.accessibleName = TasklaneBundle.message("a11y.search")
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        // Altura variable por fila: desde la Fase 5 el renderer tiene una segunda
        // línea que sólo aparece cuando hay enlaces o etiquetas que enseñar.
        tree.rowHeight = 0
        // El resalte del ratón lo pinta la tarjeta, no el árbol: el del árbol va
        // detrás del renderer y más ancho que él, así que asomaba por los bordes
        // redondeados. Ver [TaskTreeRenderer.paintComponent].
        RenderingUtil.setHoverPaintingDisabled(tree, true)
        // Sin speed search del árbol: desde la Fase 4 el campo de búsqueda hace ese
        // trabajo, y mejor —entiende operadores y busca en el cuerpo, no sólo en la
        // fila—. Con los dos vivos, teclear sobre el árbol abriría un buscador
        // flotante con otras reglas que el que está justo encima.
        // El título se envuelve contra el ancho visible, así que al estrechar o
        // ensanchar la tool window cambia el número de líneas de cada fila. El árbol
        // cachea las alturas y no tiene por qué enterarse de que el renderer mide
        // distinto, así que hay que tirarle la caché a la cara.
        tree.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                TreeUtil.invalidateCacheAndRepaint(tree.ui)
            }
        })
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = track(event, expanded = true)
            override fun treeCollapsed(event: TreeExpansionEvent) = track(event, expanded = false)

            private fun track(event: TreeExpansionEvent, expanded: Boolean) {
                if (rendering) return
                val key = (event.path.lastPathComponent as? GroupNode)?.key ?: return
                if (expanded) collapsed -= key else collapsed += key
            }
        })

        setContent(buildContent())
        toolbar = buildToolbar()
        PopupHandler.installPopupMenu(tree, CONTEXT_MENU_GROUP, ActionPlaces.TOOLWINDOW_POPUP)

        installSearchField()
        installShortcuts()
        TaskLinks.install(tree, renderer)
        TaskRowActions.install(tree, renderer, this)

        uiScope.launch {
            combine(service.snapshot, search.results, view.filter, ::Triple).collect { (snap, found, filter) ->
                // Filtrar, ordenar, agrupar y contar fuera del EDT: es el trabajo que
                // crece con el número de tareas. Un solo «ahora» para los dos, o el
                // contador y la lista podrían discrepar en lo que está vencido.
                val now = Instant.now()
                val sections = buildSections(snap, found, filter, now)
                val counts = VisibleTasks.countsByState(snap, found, filter, now)
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    render(snap, found, filter, sections, counts)
                }
            }
        }

        uiScope.launch {
            // La consulta es de la ventana, no de la pestaña: se escribe en una y las
            // demás tienen que enseñar lo mismo al cambiar de tab.
            search.rawQuery.collect { raw ->
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    if (searchField.text != raw) searchField.text = raw
                }
            }
        }

        uiScope.launch {
            service.reveal.collect { ids ->
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { reveal(ids) }
            }
        }
    }

    // --------------------------------------------------------------- composición

    private fun buildContent(): JComponent = JPanel(BorderLayout()).apply {
        searchField.border = JBUI.Borders.empty(2, 4)
        // Pestañas y buscador van juntos arriba, en ese orden: la fila dice qué
        // estado se está mirando y el campo acota lo que se ve dentro de él.
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(tabs, BorderLayout.NORTH)
            add(searchField, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
    }

    private fun buildToolbar(): JComponent {
        val group = ActionManager.getInstance().getAction(TOOLBAR_GROUP) as ActionGroup
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        // Apuntar al árbol y no al panel es lo que hace que las acciones vean la
        // selección: el DataContext se construye desde este componente hacia arriba.
        toolbar.targetComponent = tree
        return toolbar.component
    }

    /** Cómo las acciones registradas encuentran esta pestaña. */
    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink.set(TasklaneDataKeys.PANEL, this)
    }

    private fun installSearchField() {
        // El atajo se enseña dentro del campo. Es la única pista de que existe: no
        // hay botón de búsqueda que pueda llevar un tooltip con él, y ⌘K aquí no es
        // el ⌘K del resto del IDE.
        val hint = searchShortcut().shortcuts.firstOrNull()?.let(KeymapUtil::getShortcutText)
        searchField.textEditor.emptyText.text =
            if (hint.isNullOrBlank()) TasklaneBundle.message("search.placeholder")
            else TasklaneBundle.message("search.placeholder.shortcut", hint)
        searchField.textEditor.toolTipText = TasklaneBundle.message("search.tooltip")
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = search.setQuery(searchField.text)
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    // Enter no «ejecuta» nada —los resultados ya están— pero sí
                    // archiva la consulta y devuelve el foco a la lista, que es lo
                    // siguiente que se quiere tocar.
                    KeyEvent.VK_ENTER -> {
                        searchField.addCurrentTextToHistory()
                        tree.requestFocusInWindow()
                    }

                    KeyEvent.VK_ESCAPE -> {
                        if (searchField.text.isEmpty()) return
                        // Consumido para que Escape limpie la búsqueda en vez de
                        // saltar al editor, que es lo que haría la tool window.
                        e.consume()
                        search.clear()
                        tree.requestFocusInWindow()
                    }
                }
            }
        })
    }

    /**
     * Instala los atajos **dentro** de la pestaña.
     *
     * Cada uno se lee del keymap si la acción registrada tiene asignación, y si no
     * cae a un valor local. Eso es lo que permite que `⌘K` funcione aquí sin
     * declararlo como atajo global: fuera de la tool window sigue siendo *Commit*, y
     * quien quiera un atajo global lo asigna en *Settings → Keymap* y este código lo
     * recoge solo.
     *
     * Se registran acciones locales y no las registradas: `registerCustomShortcutSet`
     * **reescribe** el `ShortcutSet` de la acción sobre la que se llama, y hacerlo
     * sobre una acción del `ActionManager` le cambiaría el atajo a todo el IDE.
     */
    private fun installShortcuts() {
        localShortcut(ACTION_EDIT, CommonShortcuts.ENTER, tree) { editSelected() }
        localShortcut(ACTION_DELETE, CommonShortcuts.getDelete(), tree) { deleteSelected() }
        localShortcut(ACTION_FOCUS_SEARCH, searchShortcut(), this) { focusSearch() }

        installDoubleClick()
        installGroupToggle()
    }

    /**
     * Doble clic == abrir la tarea.
     *
     * Se atiende en `mouseClicked` y **no** con un `DoubleClickListener`, que es lo
     * que parecería natural. `CheckboxTree` instala su propio `ClickListener` en el
     * constructor —antes que cualquier oyente nuestro— y, en cuanto el clic es doble
     * y no cae en la casilla, **consume el evento de soltar** para poder disparar su
     * gancho `onDoubleClick`. `DoubleClickListener` vive precisamente de ese evento,
     * así que no llegaba a enterarse y el gesto no hacía nada. `MOUSE_CLICKED` es
     * otro evento distinto y sí llega.
     */
    /**
     * Un clic en la cabecera despliega o pliega su grupo.
     *
     * Es el **único** gesto que lo hace con el ratón: el árbol va sin manecillas
     * —`showsRootHandles = false`, porque la plataforma las pinta fuera de la tarjeta
     * y desalinearían todas las filas—, así que el grupo se podía plegar sólo con
     * `←`/`→` del teclado y nada lo anunciaba. El chevrón de la cabecera lo pinta
     * [TaskTreeRenderer.renderGroup].
     *
     * Toda la fila responde, no sólo el chevrón: una cabecera es un objetivo ancho y
     * cómodo, y encima no tiene ninguna otra cosa que pulsar.
     */
    private fun installGroupToggle() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1 || event.isPopupTrigger) return
                val row = rowAtHeight(tree, event.y)
                if (row < 0) return
                if (tree.getPathForRow(row)?.lastPathComponent !is GroupNode) return
                event.consume()
                if (tree.isExpanded(row)) tree.collapseRow(row) else tree.expandRow(row)
            }
        })
    }

    private fun installDoubleClick() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2 || event.button != MouseEvent.BUTTON1 || event.isPopupTrigger) return
                // Sobre un enlace manda el enlace: el primer clic ya lo abrió, y
                // abrir el editor encima sería una segunda cosa que nadie pidió. Lo
                // mismo con el marcador, el menú y la casilla: el primer clic ya hizo
                // lo suyo.
                if (renderer.linksAt(tree, event.point).isNotEmpty()) return
                if (renderer.targetAt(tree, event.point) != null) return
                if (renderer.isOnCheckbox(tree, event.point)) return
                if (selectedTasks().size != 1) return
                event.consume()
                editSelected()
            }
        })
    }

    /**
     * El atajo de la búsqueda, resuelto igual que lo resuelve [localShortcut]: lo del
     * keymap si el usuario le asignó algo, y si no el local. Se saca aparte porque el
     * campo tiene que **escribirlo** y el panel tiene que **registrarlo**, y los dos
     * tienen que decir lo mismo.
     */
    private fun searchShortcut(): ShortcutSet {
        val assigned = ActionManager.getInstance().getAction(ACTION_FOCUS_SEARCH)?.shortcutSet
        if (assigned != null && assigned.shortcuts.isNotEmpty()) return assigned
        val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        return CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_K, menuMask))
    }

    private fun localShortcut(
        actionId: String,
        fallback: ShortcutSet,
        component: JComponent,
        run: () -> Unit,
    ) {
        val assigned = ActionManager.getInstance().getAction(actionId)?.shortcutSet
        val shortcuts = assigned?.takeIf { it.shortcuts.isNotEmpty() } ?: fallback
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = run()
        }.registerCustomShortcutSet(shortcuts, component, this)
    }

    // ------------------------------------------------------------- modelo

    /** Un bloque del árbol. [key] nulo == este estado no agrupa. */
    private class Section(val key: GroupKey?, val tasks: List<Task>)

    private fun buildSections(
        snap: TasklaneSnapshot,
        found: SearchResults,
        filter: TaskFilter,
        now: Instant,
    ): List<Section> {
        val state = snap.config.state(stateId) ?: return emptyList()

        // La misma cuenta que usan los contadores de las pestañas, y por eso vive
        // fuera: ver [VisibleTasks].
        val mine = VisibleTasks.of(snap, found, filter, stateId, now)

        // Lo marcado va primero pase lo que pase: marcar es precisamente decir «que
        // no se me pierda esto». Después la prioridad, que es lo que se mira en una
        // lista de pendientes, y dentro de la misma prioridad lo más reciente arriba.
        val natural = compareByDescending<Task> { it.bookmarked }
            .thenByDescending { snap.config.priorityOrDefault(it.priorityId).order }
            .thenByDescending { (DateGrouper.anchorOf(it, state.anchor) ?: it.updatedAt).toEpochMilli() }
        // Buscando, en cambio, lo que manda es lo que mejor casa: el orden normal
        // enterraría el resultado bueno bajo cualquier tarea de prioridad alta.
        val order =
            if (found.active) compareByDescending<Task> { found.scoreOf(it.id) }.then(natural) else natural

        return when (state.grouping) {
            Grouping.NONE -> listOf(Section(null, mine.sortedWith(order)))
            // «Hoy» se enseña aunque esté vacío, pero sólo cuando la lista habla de
            // todo: buscando o con un filtro puesto, un «no queda nada» diría que no
            // hay tareas hoy cuando lo que pasa es que no casan con lo que se pidió.
            Grouping.BY_DATE -> byDate(mine, state, order, keepToday = !found.active && filter == TaskFilter.ALL)
            Grouping.BY_PRIORITY -> byPriority(mine, snap, order)
            Grouping.BY_TAG -> byTag(mine, order)
        }
    }

    /**
     * Agrupa por fecha y, con [keepToday], se asegura de que «hoy» exista.
     *
     * Es el único grupo que vale la pena vacío: que no haya nada hoy es justo lo que
     * se viene a mirar. Los demás —ayer, esta semana— sólo importan cuando tienen
     * algo. Y no se añade a una lista vacía del todo: ahí el árbol tiene su propio
     * «no hay tareas», que además dice cómo crear la primera.
     */
    private fun byDate(
        tasks: List<Task>,
        state: TaskState,
        order: Comparator<Task>,
        keepToday: Boolean,
    ): List<Section> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val sections = tasks
            .groupBy { DateGrouper.groupOf(DateGrouper.anchorOf(it, state.anchor), today, zone, firstDayOfWeek) }
            .toSortedMap()
            .map { (group, group_tasks) -> Section(GroupKey.OfDate(group), group_tasks.sortedWith(order)) }

        val key = GroupKey.OfDate(DateGroup.Today)
        if (!keepToday || sections.isEmpty() || sections.any { it.key == key }) return sections
        // Delante: `DateGroup.Today` es el primero del orden, así que es donde habría
        // caído de tener tareas.
        return listOf(Section(key, emptyList())) + sections
    }

    /**
     * De la prioridad más alta a la más baja, y sólo con las que tienen algo: una
     * cabecera vacía por cada prioridad configurada convertiría la lista en un índice.
     */
    private fun byPriority(
        tasks: List<Task>,
        snap: TasklaneSnapshot,
        order: Comparator<Task>,
    ): List<Section> = tasks
        .groupBy { snap.config.priorityOrDefault(it.priorityId).id }
        .toList()
        .sortedByDescending { (id, _) -> snap.config.priorities.firstOrNull { it.id == id }?.order ?: 0 }
        .map { (id, group_tasks) -> Section(GroupKey.OfPriority(id), group_tasks.sortedWith(order)) }

    /**
     * Una tarea con varias etiquetas sale bajo **todas** las suyas, no bajo la
     * primera: agrupar por etiqueta sirve para ver junto todo lo de una, y repartir
     * cada tarea en un único cajón haría que la mitad de ellas faltasen del suyo.
     *
     * Las que no tienen ninguna van a un grupo propio al final en vez de
     * desaparecer, que es lo que haría un `flatMap` sobre `tags` a secas.
     */
    private fun byTag(tasks: List<Task>, order: Comparator<Task>): List<Section> = tasks
        .flatMap { task -> task.tags.ifEmpty { listOf(null) }.map { it to task } }
        .groupBy({ it.first }, { it.second })
        .toList()
        .sortedWith(compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.first })
        .map { (tag, group_tasks) -> Section(GroupKey.OfTag(tag), group_tasks.sortedWith(order)) }

    // ------------------------------------------------------------- rendering

    private fun render(
        snap: TasklaneSnapshot,
        found: SearchResults,
        filter: TaskFilter,
        sections: List<Section>,
        counts: Map<StateId, Int>,
    ) {
        snapshot = snap
        tabs.update(snap.config.states, counts, stateId)
        this.sections = sections
        renderer.config = snap.config
        renderer.highlighter = found.highlighter
        renderer.activeRepo = snap.activeRepo
        renderer.repoNames = snap.repositories.associate { it.key to it.displayName }
        val terminal = snap.config.state(stateId)?.terminal ?: false

        updateEmptyText(found, filter)

        val previouslySelected = selectedTasks().map { it.id }.toSet()

        rendering = true
        try {
            root.removeAllChildren()
            for (section in sections) {
                if (section.key == null) {
                    section.tasks.forEach { root.add(TaskNode(it, terminal)) }
                } else {
                    val group = GroupNode(section.key, section.tasks.size)
                    if (section.tasks.isEmpty()) {
                        group.add(EmptyGroupNode(emptyGroupText(terminal)))
                    } else {
                        section.tasks.forEach { group.add(TaskNode(it, terminal)) }
                    }
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

    /**
     * Qué dice un grupo vacío. En un estado terminal no hay nada que celebrar —no se
     * ha completado nada hoy, y ya—; en uno abierto, sí.
     */
    private fun emptyGroupText(terminal: Boolean): String =
        if (terminal) {
            TasklaneBundle.message("group.today.empty.done")
        } else {
            TasklaneBundle.message("group.today.empty.open")
        }

    /**
     * «Sin tareas», «sin resultados» y «nada pasa el filtro» no son lo mismo, y decir
     * el primero cuando pasa cualquiera de los otros dos hace pensar que la búsqueda
     * —o el filtro— borró algo.
     */
    private fun updateEmptyText(found: SearchResults, filter: TaskFilter) {
        tree.emptyText.clear()
        when {
            found.active -> tree.emptyText.text = TasklaneBundle.message("toolwindow.tree.noMatches")

            filter != TaskFilter.ALL ->
                tree.emptyText.text = TasklaneBundle.message("toolwindow.tree.noFilterMatches")

            else -> {
                tree.emptyText.text = TasklaneBundle.message("toolwindow.tree.empty")
                tree.emptyText.appendLine(TasklaneBundle.message("toolwindow.tree.empty.action"))
            }
        }
    }

    private fun expandGroups() {
        for (node in root.children().asSequence().filterIsInstance<GroupNode>()) {
            if (node.key !in collapsed) tree.expandPath(TreePath(node.path))
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

        onSelectState(stateId)
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null, false)

        tree.selectionPaths = paths.toTypedArray()
        TreeUtil.showRowCentered(tree, tree.getRowForPath(paths.first()), false, true)
    }

    private fun taskNodes(): Sequence<TaskNode> =
        TreeUtil.treeNodeTraverser(root).traverse().filter(TaskNode::class.java).asSequence()

    // ----------------------------------------------- superficie de las acciones

    fun selectedTasks(): List<Task> =
        tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? TaskNode)?.task }

    /** Si se puede crear aquí: depende del repositorio activo, que es donde iría. */
    fun isEditable(): Boolean = !service.isReadOnly(snapshot.activeRepo)

    /**
     * Si la selección se puede tocar. Se mira repositorio a repositorio y no sólo el
     * activo, porque buscando en todos pueden convivir filas de varios y sólo uno de
     * ellos estar en solo lectura.
     */
    fun isSelectionEditable(): Boolean =
        selectedTasks().let { tasks -> tasks.isNotEmpty() && tasks.none { service.isReadOnly(it.repo) } }

    val repositoryCount: Int get() = snapshot.repositories.size

    val activeRepository: RepositoryRef? get() = snapshot.activeRepository

    // ------------------------------------------------------------ exportación

    /**
     * Qué se lleva el portapapeles para cada alcance. Las cabeceras salen de aquí y
     * no del exportador porque son lo que el usuario ve escrito en la pestaña: el
     * nombre del estado y, si agrupa, el del grupo de fecha.
     */
    fun exportSections(scope: ExportScope): List<TaskExporter.Section> {
        val state = snapshot.config.state(stateId)?.name.orEmpty()
        fun heading(key: GroupKey?): String =
            if (key == null) state else "$state · ${GroupLabels.of(key, snapshot.config)}"

        // Los grupos vacíos —«hoy», cuando se enseña sin tareas— no se copian: una
        // cabecera con nada debajo no es lo que se quería pegar.
        val sections = sections.filter { it.tasks.isNotEmpty() }
        return when (scope) {
            ExportScope.STATE -> sections.map { TaskExporter.Section(heading(it.key), it.tasks) }

            ExportScope.GROUP -> selectedGroup()
                ?.let { key -> sections.filter { it.key == key } }
                .orEmpty()
                .map { TaskExporter.Section(heading(it.key), it.tasks) }

            // La selección puede cruzar grupos —y repositorios, buscando en todos—,
            // así que sale como un bloque único bajo el nombre del estado.
            ExportScope.SELECTION -> listOf(TaskExporter.Section(heading(null), selectedTasks()))
        }
    }

    /** El grupo donde está la selección. `null` si la pestaña no agrupa. */
    private fun selectedGroup(): GroupKey? {
        val path = tree.selectionPaths?.firstOrNull() ?: return null
        (path.lastPathComponent as? GroupNode)?.let { return it.key }
        return (path.parentPath?.lastPathComponent as? GroupNode)?.key
    }

    /** Si «exportar este grupo» tiene algo que exportar ahora mismo. */
    fun hasSelectedGroup(): Boolean = selectedGroup() != null

    val grouping: Grouping get() = snapshot.config.state(stateId)?.grouping ?: Grouping.NONE

    fun setGrouping(grouping: Grouping) {
        val configService = TasklaneConfigService.getInstance(project)
        val config = configService.config.value
        configService.update(
            config.copy(
                states = config.states.map { state ->
                    if (state.id != stateId) state else state.copy(grouping = grouping)
                },
            ),
        )
    }

    /** Manda el cursor a la lista. Lo usa [TasklaneWindow] al cambiar de estado. */
    fun focusTree() {
        tree.requestFocusInWindow()
    }

    fun focusSearch() {
        searchField.textEditor.requestFocusInWindow()
        searchField.textEditor.selectAll()
    }

    /**
     * Crea una tarea. Por defecto en esta pestaña, que es el destino natural; el
     * desplegable del botón partido pasa otro estado para poder apuntar algo donde no
     * se está mirando sin cambiar de pestaña primero.
     */
    fun createTask(target: StateId = stateId) {
        // El repositorio se captura **antes** de abrir el diálogo y se usa el mismo al
        // confirmar. `snapshot` se reasigna mientras el diálogo está abierto —el
        // repintado corre con `ModalityState.any()`—, así que volver a leerlo después
        // puede dar otro: la captura pegada se habría escrito en un repositorio y la
        // tarea nacería en el otro, con la imagen rota desde el primer segundo.
        val repo = snapshot.activeRepo
        val dialog = TaskEditDialog(project, snapshot.config, repo, initialState = target)
        if (!dialog.showAndGet()) return
        service.apply(
            TaskCommand.Create(repo, dialog.body, dialog.stateId, dialog.priorityId, dialog.tags, dialog.dueDate),
        )
    }

    fun editSelected() {
        val task = selectedTasks().singleOrNull() ?: return
        val dialog = TaskEditDialog(
            project,
            snapshot.config,
            task.repo,
            task.body,
            task.stateId,
            task.priorityId,
            task.tags,
            task.dueDate,
        )
        if (!dialog.showAndGet()) return
        with(service) {
            apply(TaskCommand.UpdateBody(task.repo, task.id, dialog.body))
            apply(TaskCommand.ChangeState(task.repo, task.id, dialog.stateId))
            apply(TaskCommand.ChangePriority(task.repo, task.id, dialog.priorityId))
            apply(TaskCommand.SetTags(task.repo, task.id, dialog.tags))
            apply(TaskCommand.SetDueDate(task.repo, task.id, dialog.dueDate))
        }
    }

    /** Marcar desde la fila, sin pasar por la selección: lo usa [TaskRowActions]. */
    fun toggleBookmark(task: Task) {
        service.apply(TaskCommand.ToggleBookmark(task.repo, task.id))
    }

    fun toggleBookmarkSelected() {
        selectedTasks().forEach { service.apply(TaskCommand.ToggleBookmark(it.repo, it.id)) }
    }

    /**
     * Se agrupa por repositorio antes de borrar. Con la búsqueda en todos los
     * repositorios una misma selección puede tener filas de varios, y `Delete` está
     * acotado a uno: mandarlas todas con la clave del activo borraría sólo unas
     * cuantas y en silencio.
     */
    fun deleteSelected() {
        selectedTasks()
            .groupBy { it.repo }
            .forEach { (repo, tasks) -> service.apply(TaskCommand.Delete(repo, tasks.map { it.id })) }
    }

    fun toggleSelected() {
        selectedTasks().forEach { service.apply(TaskCommand.ToggleComplete(it.repo, it.id)) }
    }

    override fun dispose() {
        uiScope.cancel()
    }

    companion object {
        const val TOOL_WINDOW_ID = "Tasklane"

        private const val TOOLBAR_GROUP = "Tasklane.Toolbar"
        private const val CONTEXT_MENU_GROUP = "Tasklane.ContextMenu"
        private const val ACTION_EDIT = "Tasklane.EditTask"
        private const val ACTION_DELETE = "Tasklane.DeleteTask"
        private const val ACTION_FOCUS_SEARCH = "Tasklane.FocusSearch"

        /** Clave del historial del campo de búsqueda en `PropertiesComponent`. */
        private const val HISTORY_PROPERTY = "Tasklane.Search"
    }
}
