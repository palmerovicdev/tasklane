package com.tasklane.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
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
import com.tasklane.diagnostics.TasklaneMetrics
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.export.ExportScope
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.paging.GroupOutline
import com.tasklane.paging.PageQuery
import com.tasklane.paging.TaskPager
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
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
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
    private val metrics = TasklaneMetrics.getInstance(project)
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

        /**
         * Plegar de verdad, que es lo que la plataforma se negaba a hacer aquí.
         *
         * `CheckboxTree` hereda de `com.intellij.ui.treeStructure.Tree`, y su
         * `collapsePath` pliega **recursivamente** mientras el ajuste avanzado
         * `ide.tree.collapse.recursively` esté puesto —viene puesto de fábrica—. Ese
         * camino descarta los nodos que considera «siempre desplegados»: los de
         * profundidad cero según `TreeUtil.getNodeDepth`, que resta uno por raíz oculta
         * y otro por manecillas apagadas. Nuestras cabeceras cuelgan de la raíz y el
         * árbol va sin manecillas —ver `showsRootHandles` en el `init`—, así que daban
         * cero: la llamada no plegaba nada y tampoco avisaba de que no lo había hecho.
         * El síntoma era que la cabecera no respondía ni al clic ni a `←`.
         *
         * `setExpandedState` es exactamente lo que llama el `collapsePath` de `JTree`,
         * sin ese recorte, y sigue disparando `treeCollapsed` —que es como se recuerda
         * el pliegue—. No se pierde el plegado recursivo por el camino: aquí sólo hay
         * dos niveles y las tareas son hojas.
         *
         * Lo comprueba [com.tasklane.ui.GroupFoldingTest], que falla si la plataforma
         * cambia de opinión y este atajo deja de hacer falta.
         */
        override fun collapsePath(path: TreePath) {
            setExpandedState(path, false)
        }

        /**
         * El árbol mide **exactamente** lo que se ve, siempre.
         *
         * De fábrica un `JTree` sólo sigue al viewport cuando ya cabe dentro
         * (`parent.width > preferredSize.width`); en cuanto una fila pide un píxel de
         * más, se queda con su ancho y deja de encogerse. Y aquí eso no es un detalle
         * de aspecto: la tarjeta envuelve su título contra el ancho **visible**, así
         * que un árbol más ancho que el hueco pinta filas de tres líneas en el sitio
         * que se midió para dos — y lo que se queda fuera, abajo, es la línea de
         * distintivos.
         *
         * Con esto el ancho del árbol y el del viewport son el mismo número: aparecer
         * la barra de desplazamiento vertical lo estrecha, estrecharlo dispara su
         * `componentResized` —ver el `init`—, y ahí se tiran las medidas viejas y se
         * vuelve a envolver contra lo que hay. Nunca hay barra horizontal, que en una
         * lista de tarjetas tampoco llevaría a ningún sitio.
         */
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }

    /**
     * Las vistas previas de las tarjetas desplegadas. Vive aquí y no en el renderer
     * porque cuando una imagen termina de cargarse hay que **rehacer alturas**, y la
     * altura de una fila es cosa del árbol: el renderer no tiene a quién avisar.
     */
    private val cardImages = CardImages(project) { TreeUtil.invalidateCacheAndRepaint(tree.ui) }

    /**
     * El hueco por el que se mira la lista. Es campo y no una variable local de
     * [buildContent] porque desde la Fase 2 hace falta **escucharlo**: llegar
     * desplazándose al centinela del final de una página es lo que pide la siguiente.
     */
    private val scroll: JScrollPane = ScrollPaneFactory.createScrollPane(tree, true)

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
     * El árbol acotado y su sincronización por diferencias, que es el trabajo de la
     * Fase 2. Vive fuera del panel porque no necesita un IDE: ahí están las páginas,
     * los centinelas, el presupuesto de filas y las ventanas cargadas.
     */
    private val list = ListSync(tree, stateId)

    /**
     * Qué lista describe lo que hay cargado. Cambiar de consulta, de filtro, de
     * agrupación o de repositorio es empezar **otra** lista, y una lista nueva empieza
     * por el principio: sin esto, teclear en el buscador obligaría a rehacer las dos
     * mil filas que el usuario hubiera ido cargando al desplazarse.
     */
    private data class ViewKey(
        val grouping: Grouping,
        val filter: TaskFilter,
        val query: String,
        val repo: RepoKey,
    )

    private var listKey: ViewKey? = null

    /** Una carga de página ya encolada. Ver [loadVisibleSentinel]. */
    private var loadPending = false

    /**
     * Lo que se pidió enseñar y todavía no estaba en el árbol. Ver [reveal].
     */
    private var pendingReveal: Set<TaskId> = emptySet()

    init {
        renderer.images = cardImages
        tree.isRootVisible = false
        tree.showsRootHandles = false
        // Ni el árbol ni el buscador llevan etiqueta visible —el sitio manda—, así que
        // sin esto un lector de pantalla anuncia «árbol» y «campo de texto» a secas.
        tree.accessibleContext.accessibleName = TasklaneBundle.message("a11y.tree")
        searchField.textEditor.accessibleContext.accessibleName = TasklaneBundle.message("a11y.search")
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        // El gesto de plegar es nuestro —lo atiende [installGroupToggle]—, así que se
        // apaga el de la plataforma. Si no, el doble clic sobre una cabecera disparaba
        // los dos y se anulaban entre sí; y encima el número de clics que hace falta
        // depende de *Expand nodes with single click*, un ajuste del IDE que cambiaría
        // el gesto de esta lista sin que nadie lo pidiera.
        tree.toggleClickCount = 0
        // La selección no manda sobre el plegado. `JTree` despliega por defecto los
        // ancestros de lo que se selecciona, así que restaurar la selección tras un
        // repintado reabría el grupo recién plegado —y de paso borraba el recuerdo, que
        // se lleva escuchando eventos de despliegue—. Abrir un grupo se pide a las
        // claras: ver [reveal].
        tree.expandsSelectedPaths = false
        // Altura variable por fila: desde la Fase 5 el renderer tiene una segunda
        // línea que sólo aparece cuando hay enlaces o etiquetas que enseñar.
        tree.rowHeight = 0
        // El resalte del ratón lo pinta la tarjeta, no el árbol: el del árbol va
        // detrás del renderer y más ancho que él, así que asomaba por los bordes
        // redondeados. Ver [TaskTreeRenderer.paintComponent].
        RenderingUtil.setHoverPaintingDisabled(tree, true)
        // Sin la ventanita de «fila completa» de la plataforma. Cuando una fila no cabe
        // de ancho, `Tree` saca al pasar el ratón un trozo flotante con lo que falta,
        // **por fuera** del panel. Con estas filas eso es media tarjeta asomando sobre
        // el editor y, dentro, los botones de la derecha: acercarse a pulsarlos saca el
        // ratón de la fila y la ventanita se cierra en el camino. La tarjeta ya se
        // encarga de no pasarse de ancho —ver [TaskTreeRenderer.getPreferredSize]—, así
        // que esto es el cinturón: una barra de desplazamiento horizontal, un tema con
        // otras métricas o un distintivo más largo de la cuenta la dejarían volver.
        tree.setExpandableItemsEnabled(false)
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
                // Y con la caché se va la selección de texto: sus posiciones son
                // líneas pintadas, y otro ancho son otras líneas. Mantenerla sería
                // dejar marcado un tramo que ya no es el que se marcó.
                renderer.selection = null
                TreeUtil.invalidateCacheAndRepaint(tree.ui)
            }
        })
        // Un grupo nace **sin hijos** y aun así tiene que poder desplegarse: es al
        // desplegarlo cuando se le pide su primera página. `CheckboxTree` monta un
        // `DefaultTreeModel` de fábrica, que tomaría esa cabecera por una hoja. Ver
        // [TaskTreeModel].
        tree.model = TaskTreeModel(root)
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = track(event, expanded = true)
            override fun treeCollapsed(event: TreeExpansionEvent) = track(event, expanded = false)

            private fun track(event: TreeExpansionEvent, expanded: Boolean) {
                // Los despliegues que hace la propia sincronización no son gestos.
                if (list.syncing) return
                val key = (event.path.lastPathComponent as? GroupNode)?.key ?: return
                list.toggled(key, expanded)
                // El gesto es la mitad del trabajo: abrir un grupo es pedir su primera
                // página —hasta ahora la cabecera sólo llevaba su número— y cerrarlo es
                // soltar las filas que tenía. De eso vive el tope del árbol.
                metrics.time(TasklaneMetrics.Op.RENDER) { resync { list.sync() } }
            }
        })
        // Llegar al centinela desplazándose pide la siguiente página. Es el mismo
        // gesto que *Find in Files*, y el motivo de que el árbol no tenga que
        // contener el grupo entero para que se pueda recorrer.
        scroll.viewport.addChangeListener { loadVisibleSentinel() }

        setContent(buildContent())
        toolbar = buildToolbar()
        PopupHandler.installPopupMenu(tree, CONTEXT_MENU_GROUP, ActionPlaces.TOOLWINDOW_POPUP)

        installSearchField()
        installShortcuts()
        RowClicks.install(tree, renderer, project)
        TaskRowActions.install(tree, renderer, this)
        CardTextSelection.install(tree, renderer)

        uiScope.launch {
            combine(service.snapshot, search.results, view.filter, ::Triple).collect { (snap, found, filter) ->
                // Filtrar, ordenar, agrupar y contar fuera del EDT: es el trabajo que
                // crece con el número de tareas. Un solo «ahora» para los dos, o el
                // contador y la lista podrían discrepar en lo que está vencido.
                val now = Instant.now()
                val (pager, outline, counts) = metrics.time(TasklaneMetrics.Op.SECTIONS) {
                    val pager = service.pager(found, filter, now)
                    // Se calientan aquí a propósito: es donde vive el O(n log n) de
                    // ordenar y agrupar. Lo que el EDT pida después son sublistas de
                    // algo ya ordenado y una cuenta ya hecha.
                    Triple(pager, pager.outline(stateId), pager.counts())
                }
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    // RENDER va aparte de SECTIONS porque es lo unico de los dos que
                    // ocurre en el EDT, y por tanto lo unico que tiene un techo duro
                    // de 16 ms. Mezclarlos daria una cifra que no se puede juzgar.
                    metrics.time(TasklaneMetrics.Op.RENDER) {
                        render(snap, found, filter, pager, outline, counts)
                    }
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

    /**
     * La tool window no baja de [MIN_WIDTH] píxeles.
     *
     * Por debajo de eso la tarjeta deja de ser una tarjeta: el título se parte en tres
     * líneas de dos palabras, los distintivos empiezan a caerse por la derecha y lo que
     * queda no se lee. La plataforma respeta el mínimo del contenido al repartir el
     * ancho de la banda lateral, así que el suelo se pide desde aquí. De ese ancho para
     * arriba la fila se defiende sola: se envuelve contra lo que hay y nunca pide más
     * —ver [TaskTreeRenderer.getPreferredSize]—.
     */
    override fun getMinimumSize(): Dimension {
        val size = super.getMinimumSize()
        return Dimension(maxOf(size.width, JBUI.scale(MIN_WIDTH)), size.height)
    }

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
        add(scroll, BorderLayout.CENTER)
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
        localShortcut(ACTION_EDIT, CommonShortcuts.ENTER, tree) { activateSelected() }
        localShortcut(ACTION_DELETE, CommonShortcuts.getDelete(), tree) { deleteSelected() }
        localShortcut(ACTION_FOCUS_SEARCH, searchShortcut(), this) { focusSearch() }
        // Los cuatro van sobre el **árbol** y no sobre el panel: con el cursor dentro
        // del buscador, `⌥←` es «palabra anterior» y robárselo ahí sería peor que no
        // tener el atajo.
        localShortcut(ACTION_SELECT_PREV_STATE, altArrow(KeyEvent.VK_LEFT), tree) { selectNeighbourState(-1) }
        localShortcut(ACTION_SELECT_NEXT_STATE, altArrow(KeyEvent.VK_RIGHT), tree) { selectNeighbourState(1) }
        localShortcut(ACTION_MOVE_PREV_STATE, altArrow(KeyEvent.VK_LEFT, shift = true), tree) { moveSelected(-1) }
        localShortcut(ACTION_MOVE_NEXT_STATE, altArrow(KeyEvent.VK_RIGHT, shift = true), tree) { moveSelected(1) }

        installDoubleClick()
        installGroupToggle()
        installTextSelection()
    }

    /**
     * `⌘C` copia el texto marcado dentro de una tarjeta, y `Escape` lo desmarca.
     *
     * Las dos se **apagan** cuando no hay nada marcado, y eso no es cosmética: una
     * acción deshabilitada no se queda con la pulsación, así que `Escape` sigue
     * llevando al editor y `⌘C` sigue siendo de quien fuera antes. Encendidas sólo
     * cuando hay selección, no le quitan el atajo a nadie.
     */
    private fun installTextSelection() {
        fun action(shortcuts: ShortcutSet, run: () -> Unit) {
            object : DumbAwareAction() {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = renderer.selection?.isEmpty == false
                }

                override fun actionPerformed(e: AnActionEvent) = run()
            }.registerCustomShortcutSet(shortcuts, tree, this)
        }

        action(CommonShortcuts.getCopy()) {
            CardTextSelection.selectedText(tree, renderer)?.let(CardTextSelection::copy)
        }
        action(CommonShortcuts.ESCAPE) {
            renderer.selection = null
            tree.repaint()
        }
    }

    /**
     * Un clic en la cabecera despliega o pliega su grupo.
     *
     * Es el gesto principal con el ratón: el árbol va sin manecillas
     * —`showsRootHandles = false`, porque la plataforma las pinta fuera de la tarjeta
     * y desalinearían todas las filas—, así que sin esto nada anunciaría que el grupo
     * se pliega. El chevrón de la cabecera lo pinta [TaskTreeRenderer.renderGroup].
     *
     * Toda la fila responde, no sólo el chevrón: una cabecera es un objetivo ancho y
     * cómodo, y encima no tiene ninguna otra cosa que pulsar.
     *
     * Plegar acaba en el `collapsePath` de nuestro árbol, no en el de la plataforma:
     * el de la plataforma no plegaba estas cabeceras. Ahí está el porqué.
     */
    private fun installGroupToggle() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                // Sólo el primer clic: el segundo de un doble clic llega como otro
                // evento y desharía lo que acaba de hacer el primero.
                if (event.clickCount != 1 || event.button != MouseEvent.BUTTON1 || event.isPopupTrigger) return
                val row = rowAtHeight(tree, event.y)
                if (row < 0) return
                when (val node = tree.getPathForRow(row)?.lastPathComponent) {
                    // El centinela se pulsa como un enlace, que es como se pinta.
                    is MoreNode -> {
                        event.consume()
                        loadMore(node)
                    }

                    is GroupNode -> {
                        event.consume()
                        if (tree.isExpanded(row)) tree.collapseRow(row) else tree.expandRow(row)
                    }

                    else -> return
                }
            }
        })
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
    private fun installDoubleClick() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2 || event.button != MouseEvent.BUTTON1 || event.isPopupTrigger) return
                // Sobre un enlace manda el enlace: el primer clic ya lo abrió, y
                // abrir el editor encima sería una segunda cosa que nadie pidió. Lo
                // mismo con el ancla de código, el distintivo de prioridad, el
                // marcador, el menú y la casilla: el primer clic ya hizo lo suyo, y
                // el desplegable de la prioridad se quedaría además con el diálogo
                // abriéndose por detrás.
                if (renderer.hotspotAt(tree, event.point) != null) return
                if (renderer.targetAt(tree, event.point) != null) return
                if (renderer.isOnCheckbox(tree, event.point)) return
                if (selectedTasks().size != 1) return
                event.consume()
                editSelected()
            }
        })
    }

    /**
     * Qué hace `Enter` sobre la fila que está marcada. Sobre una tarea, abrirla; sobre
     * el centinela de una página, traer la siguiente: es la fila que se alcanza bajando
     * con el teclado, y no tendría sentido que sólo respondiera al ratón.
     */
    private fun activateSelected() {
        val node = tree.selectionPaths?.singleOrNull()?.lastPathComponent
        if (node is MoreNode) {
            loadMore(node)
            return
        }
        editSelected()
    }

    private fun moveSelected(delta: Int) {
        neighbourState(delta)?.let(::moveSelectedTo)
    }

    /**
     * `⌥←/→` cambia de pestaña y `⇧⌥←/→` se lleva la selección con ella: el mismo eje,
     * y `Shift` significa lo que significa en todas partes.
     *
     * `Alt` y no otra cosa porque es lo que el IDE usaba para pasar de pestaña cuando
     * los estados eran `Content`s, y es la deuda que quedó anotada al bajarlos al panel
     * (`docs/plan-rediseno.md`, R3). Al ser locales no le quitan `Alt+←/→` a nadie
     * fuera de esta lista.
     */
    private fun altArrow(keyCode: Int, shift: Boolean = false): ShortcutSet {
        val modifiers = InputEvent.ALT_DOWN_MASK or if (shift) InputEvent.SHIFT_DOWN_MASK else 0
        return CustomShortcutSet(KeyStroke.getKeyStroke(keyCode, modifiers))
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

    // ------------------------------------------------------------- rendering

    private fun render(
        snap: TasklaneSnapshot,
        found: SearchResults,
        filter: TaskFilter,
        pager: TaskPager,
        outline: List<GroupOutline>,
        counts: Map<StateId, Int>,
    ) {
        snapshot = snap
        list.pager = pager
        list.outline = outline
        tabs.update(snap.config.states, counts, stateId)

        // Una captura pegada puede rellenar un hueco que antes no estaba.
        cardImages.forgetMissing()
        renderer.config = snap.config
        renderer.highlighter = found.highlighter
        renderer.activeRepo = snap.activeRepo
        renderer.repoNames = snap.repositories.associate { it.key to it.displayName }

        updateEmptyText(found, filter)

        // Otra lista es otra ventana, y se empieza por el principio. Cambiar de
        // agrupación cambia además la identidad de los grupos, así que lo que el
        // usuario abrió o cerró deja de referirse a nada.
        val key = ViewKey(grouping, filter, found.raw, snap.activeRepo)
        if (listKey != key) {
            if (listKey?.grouping != key.grouping) list.forgetGroups()
            list.rewind()
            listKey = key
        }

        resync { list.sync() }

        // Lo que se pidió enseñar antes de que la tarea llegara a la lista. Se vacía
        // antes de reintentar para que un segundo fallo no lo deje dando vueltas.
        if (pendingReveal.isNotEmpty()) {
            val pending = pendingReveal
            pendingReveal = emptySet()
            reveal(pending, retry = false)
        }
    }

    /**
     * Todo lo que mueve el árbol pasa por aquí.
     *
     * Le pone al día el contexto que depende del estado —qué dice un grupo vacío no es
     * lo mismo en *ToDo* que en *Done*— y le devuelve la selección a lo que se haya
     * movido de sitio. Sincronizar por diferencias conserva los nodos que siguen, y con
     * ellos su selección: sólo se paga cuando una tarea ha cambiado de grupo o de
     * posición, que es lo único que `JTree` no puede preservar porque para él ha sido
     * quitar y volver a poner.
     */
    private fun resync(change: () -> Int): Int {
        val terminal = snapshot.config.state(stateId)?.terminal ?: false
        list.terminal = terminal
        list.emptyText = emptyGroupText(terminal)
        val selected = selectedTasks().mapTo(mutableSetOf()) { it.id }
        val touched = change()
        restoreSelection(selected)
        return touched
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

    /** La selección se restaura por [TaskId], no por índice: reordenar no debe moverla. */
    private fun restoreSelection(ids: Set<TaskId>) {
        if (ids.isEmpty()) return
        if (selectedTasks().mapTo(mutableSetOf()) { it.id } == ids) return
        val paths = taskNodes().filter { it.task.id in ids }.map { TreePath(it.path) }.toList()
        if (paths.isNotEmpty()) tree.selectionPaths = paths.toTypedArray()
    }

    private fun loadMore(node: MoreNode) {
        metrics.time(TasklaneMetrics.Op.RENDER) { resync { list.loadMore(node) } }
    }

    /**
     * Si el centinela del final se ve, pide su página. Es el mismo gesto que ya existe
     * en *Find in Files*, y el motivo de que el árbol no tenga que contener el grupo
     * entero para que se pueda recorrer.
     *
     * La carga va fuera del evento del viewport porque mutar el modelo mientras Swing
     * está repartiendo el sitio es pedirle que revalide dentro de una revalidación.
     */
    private fun loadVisibleSentinel() {
        if (loadPending) return
        val node = list.visibleSentinel() ?: return
        loadPending = true
        SwingUtilities.invokeLater {
            loadPending = false
            // Entre el encolado y ahora puede haber llegado un repintado que se lo
            // llevara por delante.
            if (node.parent != null) loadMore(node)
        }
    }

    /**
     * Atiende «enséñame estas tareas»: la acción de la notificación de remapeo y el clic
     * sobre una marca del editor. Si ninguna cae en esta pestaña no hace nada, así que
     * las demás pueden ignorarla sin coordinarse entre sí.
     *
     * **Puede llegar antes que la tarea.** Pulsar una marca de un fichero cuya tarea vive
     * en otro repositorio cambia el activo, y la lista se reconstruye después, fuera del
     * EDT: preguntar en ese instante no encontraría nada. Y una búsqueda o un filtro
     * puestos la esconderían aunque ya estuviera. Así que cuando la tarea **es de esta
     * pestaña** pero no se ve, se quita lo que la tapa y se apunta para el siguiente
     * repintado, que es cuando se reintenta —una vez, con [retry] a `false`: un reintento
     * que se re-apunta a sí mismo volvería a intentarlo en cada repintado para siempre—.
     */
    private fun reveal(ids: Set<TaskId>, retry: Boolean = true) {
        if (!list.reveal(ids)) {
            if (retry) waitFor(ids)
            return
        }

        onSelectState(stateId)
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null, false)

        metrics.time(TasklaneMetrics.Op.RENDER) { resync { list.sync() } }

        val paths = taskNodes().filter { it.task.id in ids }.map { TreePath(it.path) }.toList()
        if (paths.isEmpty()) return
        tree.selectionPaths = paths.toTypedArray()
        TreeUtil.showRowCentered(tree, tree.getRowForPath(paths.first()), false, true)
    }

    /**
     * Prepara la pestaña para una tarea que es suya pero todavía no se ve, y la apunta
     * para el siguiente repintado.
     *
     * El filtro y la búsqueda son **de la ventana**, así que quitarlos afecta a todas las
     * pestañas. Es deliberado: quien pulsa una marca pide ver *esa* tarea, y enseñarle
     * una lista donde no está —porque quedaba un `@overdue` de hace media hora— se lee
     * como que el plugin no hizo nada.
     */
    private fun waitFor(ids: Set<TaskId>) {
        val mine = ids.filter { service.task(it)?.stateId == stateId }.toSet()
        if (mine.isEmpty()) return
        pendingReveal = mine

        onSelectState(stateId)
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null, false)

        if (view.filter.value != TaskFilter.ALL) view.setFilter(TaskFilter.ALL)
        if (search.rawQuery.value.isNotEmpty()) search.clear()
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
     * Qué hay que exportar, **sin haberlo leído todavía**.
     *
     * Existe por la Fase 3. Desde la Fase 2 exportar vuelve a pedir la lista sin tope
     * —el árbol sólo tiene las páginas cargadas—, y mientras el corpus vivía en memoria
     * eso era una sublista de algo ya ordenado. Ahora es leer del almacén, y hacerlo en
     * el hilo de interfaz congelaría la ventana durante lo que tarde en salir un estado
     * entero.
     *
     * Así que se parte en dos: **en el EDT** se decide qué secciones hay —que es leer el
     * árbol, y sólo el EDT puede— y **en segundo plano** se leen.
     *
     * Desde la Fase 5 cada sección dice además **cuántas filas tiene** —la cabecera ya lo
     * sabía— y no trae una lista sino **cómo leerse a tandas** de un paginador: la
     * exportación decide el destino con [size] antes de leer nada, y lee sobre una foto del
     * [pager] —ver `TaskPager.snapshot`— para que lo que salga sea la pestaña en el
     * momento de pulsar.
     *
     * Las **cabeceras** salen de aquí y no del exportador porque son lo que el usuario ve
     * escrito en la pestaña: el nombre del estado y, si agrupa, el del grupo de fecha. Con
     * los grupos de fecha va además **el día**, que no es lo que se ve escrito —la pestaña
     * dice «Hoy»— sino lo que ese texto significa: en Markdown el exportador lo prefiere a
     * la cabecera, porque «Hoy» deja de ser verdad mañana y lo exportado se guarda.
     */
    class ExportRequest(
        val pager: TaskPager,
        /** Un nombre para el fichero, si hace falta uno: el repositorio y el estado. */
        val name: String,
        val sections: List<Pending>,
    ) {
        class Pending(
            val heading: String,
            val date: LocalDate?,
            val size: Int,
            /** Lee la sección de [TaskPager] —el congelado— a tandas. */
            val read: (TaskPager, (List<Task>) -> Unit) -> Unit,
        )

        val size: Int get() = sections.sumOf { it.size }
    }

    /**
     * Las secciones a exportar, resueltas a medias: la cabecera, cuántas filas y de dónde
     * salen. Ver [ExportRequest].
     */
    fun exportRequest(scope: ExportScope): ExportRequest {
        val state = snapshot.config.state(stateId)?.name.orEmpty()
        fun heading(key: GroupKey?): String =
            if (key == null) state else "$state · ${GroupLabels.of(key, snapshot.config)}"

        // El mismo «hoy» con el que se agrupó el árbol, y no uno por sección: pasada la
        // medianoche con la ventana abierta, media exportación diría un día y media otro.
        val today = LocalDate.now(ZoneId.systemDefault())
        fun day(key: GroupKey?): LocalDate? = (key as? GroupKey.OfDate)?.group?.dayOn(today)

        fun pending(key: GroupKey?) = ExportRequest.Pending(heading(key), day(key), list.sizeOf(key)) { pager, sink ->
            pager.each(PageQuery(stateId, key), block = sink)
        }

        val outline = list.outline
        val name = listOfNotNull(snapshot.activeRepository?.displayName, state.ifEmpty { null }).joinToString("-")
        return ExportRequest(
            list.pager,
            name,
            when (scope) {
                ExportScope.STATE ->
                    if (outline.isEmpty()) listOf(pending(null)) else outline.map { pending(it.key) }

                ExportScope.GROUP -> selectedGroup()?.let { listOf(pending(it)) }.orEmpty()

                // La selección puede cruzar grupos —y repositorios, buscando en todos—,
                // así que sale como un bloque único bajo el nombre del estado. Y ya está
                // leída: son las filas que el usuario tiene señaladas.
                ExportScope.SELECTION -> selectedTasks().let { tasks ->
                    listOf(ExportRequest.Pending(heading(null), null, tasks.size) { _, sink -> sink(tasks) })
                }
            },
        )
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
            task.anchors,
        )
        if (!dialog.showAndGet()) return
        // UN comando y no seis. Hasta la Fase 1 esto mandaba uno por campo, y cada uno
        // producía un snapshot: seis copias de la lista del repositorio y hasta seis
        // repintados del árbol por un solo clic en *Guardar*. Ver
        // [TaskCommand.UpdateTask].
        service.apply(
            TaskCommand.UpdateTask(
                repo = task.repo,
                id = task.id,
                body = dialog.body,
                stateId = dialog.stateId,
                priorityId = dialog.priorityId,
                tags = dialog.tags,
                dueDate = java.util.Optional.ofNullable(dialog.dueDate),
                anchors = dialog.anchors,
            ),
        )
    }

    /**
     * Despliega la tarjeta de [task], o la vuelve a plegar. Lo pide [TaskRowActions]
     * desde el botón de la fila.
     *
     * El árbol guarda la altura de cada fila y no tiene forma de enterarse de que el
     * renderer mide otra cosa, así que hay que tirarle la caché: el mismo problema
     * —y la misma cura— que al cambiar de ancho la tool window. Y con ella se va la
     * selección de texto, por lo mismo: desplegar reenvuelve todas las líneas.
     */
    fun toggleExpanded(task: Task) {
        renderer.toggleExpanded(task)
        renderer.selection = null
        TreeUtil.invalidateCacheAndRepaint(tree.ui)
    }

    /** Marcar desde la fila, sin pasar por la selección: lo usa [TaskRowActions]. */
    fun toggleBookmark(task: Task) {
        service.apply(TaskCommand.ToggleBookmark(task.repo, task.id))
    }

    /**
     * Las operaciones sobre la selección van **todas** por [TaskService.applyAll]: una
     * transacción y un repintado por gesto, no uno por fila. Ver `TaskCommand.Batch`.
     *
     * Cada fila lleva **su** repositorio. Con la búsqueda en todos los repositorios una
     * misma selección puede tener filas de varios, y mandarlas todas con la clave del
     * activo tocaría sólo unas cuantas, y en silencio.
     */
    fun toggleBookmarkSelected() {
        service.applyAll(selectedTasks().map { TaskCommand.ToggleBookmark(it.repo, it.id) })
    }

    /**
     * Un `Delete` por fila y no uno por repositorio con todos los ids: así el lote cuenta
     * **filas**, que es lo que decide si cabe en el gesto o va en segundo plano. Un único
     * `Delete` con cinco mil ids sería un comando suelto que se aplicaría en el acto.
     */
    fun deleteSelected() {
        service.applyAll(selectedTasks().map { TaskCommand.Delete(it.repo, listOf(it.id)) })
    }

    fun toggleSelected() {
        service.applyAll(selectedTasks().map { TaskCommand.ToggleComplete(it.repo, it.id) })
    }

    // ----------------------------------------------------- mover entre estados

    /** Los estados configurados, en el orden de las pestañas. Lo consume el submenú *Move to*. */
    val states: List<TaskState> get() = snapshot.config.states

    /**
     * Las prioridades configuradas, de la más alta a la más baja. Lo consume el
     * submenú *Priority*, y ese orden es el mismo con el que se agrupa la lista por
     * prioridad: el de la importancia, que es como se lee una lista de pendientes.
     */
    val priorities: List<TaskPriority> get() = snapshot.config.priorities.sortedByDescending { it.order }

    /**
     * Cambia la prioridad de la selección sin pasar por el diálogo.
     *
     * Va tarea a tarea con **su** repositorio por lo mismo que [deleteSelected] y
     * [moveSelectedTo]: buscando en todos, una misma selección puede mezclar filas de
     * varios repositorios.
     */
    fun setPrioritySelected(target: PriorityId) {
        service.applyAll(selectedTasks().map { TaskCommand.ChangePriority(it.repo, it.id, target) })
    }

    /**
     * Manda la selección a otro estado sin pasar por el diálogo.
     *
     * Es la operación más repetida de una lista con estados, y hasta ahora costaba abrir
     * un modal, cambiar un desplegable y aceptar. Va tarea a tarea con **su**
     * repositorio por lo mismo que [deleteSelected]: buscando en todos, una misma
     * selección puede mezclar filas de varios.
     */
    fun moveSelectedTo(target: StateId) {
        service.applyAll(selectedTasks().map { TaskCommand.ChangeState(it.repo, it.id, target) })
    }

    /**
     * El estado que hay [delta] pestañas más allá de ésta, o `null` si no hay ninguno.
     *
     * **No da la vuelta**, ni para mover ni para cambiar de pestaña. Pasar de la última
     * a la primera convertiría «avanza esta tarea» en «devuélvela al principio», que es
     * lo contrario y sin decirlo.
     */
    fun neighbourState(delta: Int): StateId? {
        val all = states
        val index = all.indexOfFirst { it.id == stateId }
        if (index < 0) return null
        return all.getOrNull(index + delta)?.id
    }

    /** Cambia de pestaña al estado vecino. Es el `Alt+←/→` que se perdió al bajarlas al panel. */
    fun selectNeighbourState(delta: Int) {
        neighbourState(delta)?.let(onSelectState)
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
        private const val ACTION_SELECT_PREV_STATE = "Tasklane.SelectPreviousState"
        private const val ACTION_SELECT_NEXT_STATE = "Tasklane.SelectNextState"
        private const val ACTION_MOVE_PREV_STATE = "Tasklane.MoveToPreviousState"
        private const val ACTION_MOVE_NEXT_STATE = "Tasklane.MoveToNextState"

        /** Clave del historial del campo de búsqueda en `PropertiesComponent`. */
        private const val HISTORY_PROPERTY = "Tasklane.Search"

        /**
         * Lo más estrecha que se deja poner la ventana. Es el ancho por debajo del cual
         * una tarjeta deja de leerse: el título se parte en líneas de dos palabras y los
         * distintivos empiezan a caerse. Ver [getMinimumSize].
         */
        private const val MIN_WIDTH = 300
    }
}
