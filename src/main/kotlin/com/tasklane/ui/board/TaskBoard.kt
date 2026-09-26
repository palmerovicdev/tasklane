package com.tasklane.ui.board

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskState
import com.tasklane.service.SearchService
import com.tasklane.service.TaskService
import com.tasklane.service.ViewService
import com.tasklane.ui.actions.TasklaneDataKeys
import com.tasklane.ui.common.noStatesPanel
import com.tasklane.ui.search.QuerySearchField
import com.tasklane.ui.toolwindow.BoardHost
import com.tasklane.ui.toolwindow.RepoSelectorAction
import com.tasklane.ui.toolwindow.TasklanePanel
import com.tasklane.ui.toolwindow.onKeymapChange
import com.tasklane.ui.toolwindow.ViewFilterAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.Graphics
import java.awt.LayoutManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * El tablero (2.17.0): los estados en columnas, una al lado de otra, en una pestaña del
 * editor.
 *
 * La tool window vive estrecha —es la herramienta del día a día, y se usa a trescientos
 * píxeles— y enseña un estado cada vez. Para planificar hace falta lo contrario: verlo todo
 * de una vez, y mover de una columna a otra. El editor tiene el ancho que a la ventana le
 * falta.
 *
 * **Cada columna es un [TasklanePanel]**, la misma lista de la tool window en su papel de
 * columna: las mismas tarjetas, el mismo menú, los mismos atajos y el mismo `⌘Z`, y todo lo
 * que la lista aprenda después lo aprende también el tablero sin tocar esta clase. Lo que
 * se pone aquí es lo que es de **todas** las columnas: el buscador, el filtro y el
 * repositorio, arriba; que una tarjeta pase de una columna a otra al soltarla; y cuál tuvo
 * el foco la última vez, que es con quién hablan las acciones de la barra.
 *
 * **Sólo los estados que se hayan dejado en el tablero** en la tabla de estados de los
 * ajustes (2.17.1), que de fábrica son todos. Pasar de columna con el teclado salta los que
 * no están; *Move To* sigue ofreciendo todos, porque mandar una tarea a un estado sin
 * columna es una forma legítima de quitarla de delante.
 *
 * El buscador y el filtro son **los de la ventana**: `SearchService` y `ViewService` son
 * del proyecto, y buscar aquí es buscar también en la tool window. Es deliberado: son dos
 * vistas de las mismas tareas, y que cada una filtrara distinto obligaría a mirar dos
 * campos para saber qué se está viendo. El repositorio, igual: el tablero enseña el activo,
 * como todo en Tasklane.
 */
internal class TaskBoard(private val project: Project) :
    JPanel(BorderLayout()),
    BoardHost,
    UiDataProvider,
    Disposable {

    private val service = TaskService.getInstance(project)
    private val search = SearchService.getInstance(project)
    private val view = ViewService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val columns = LinkedHashMap<StateId, TasklanePanel>()
    private val strip = ColumnStrip()
    private val scroll = JBScrollPane(
        strip,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
    )

    /** Si no queda ningún estado en el tablero. Ver [noStatesPanel]. */
    private val empty = noStatesPanel(project, TasklaneBundle.message("board.empty"))

    private val cards = CardLayout()
    private val center = JPanel(cards).apply {
        add(scroll, COLUMNS)
        add(empty, EMPTY)
    }

    /** El historial es el de la ventana: ver [TasklanePanel.HISTORY_PROPERTY]. */
    private val searchField = QuerySearchField(TasklanePanel.HISTORY_PROPERTY, project)

    /** Ver el mismo campo en [TasklanePanel]: escribir la consulta desde fuera no la vuelve a publicar. */
    private var updatingSearchField = false

    /** La columna que tuvo el foco la última vez. A ella van la barra de arriba y el Enter del buscador. */
    private var active: TasklanePanel? = null

    /** La columna sobre la que se están arrastrando tarjetas de otra, para borrarle lo pintado al salir. */
    private var target: TasklanePanel? = null

    init {
        scroll.border = JBUI.Borders.empty()
        add(buildHeader(), BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)

        // Primera pasada síncrona, como en la tool window: la pestaña nunca se pinta vacía.
        sync(onBoard(service.snapshot.value.config.states, view.boardHidden.value))
        installSearchField()

        scope.launch {
            combine(service.snapshot.map { it.config.states }, view.boardHidden, ::onBoard)
                .distinctUntilChanged()
                .collect { states -> withContext(Dispatchers.EDT) { sync(states) } }
        }

        scope.launch {
            search.rawQuery.collect { raw ->
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    // La misma guarda que en la ventana: una emisión vieja no pisa lo que se
                    // está escribiendo.
                    if (searchField.text != raw && search.rawQuery.value == raw) {
                        updatingSearchField = true
                        try {
                            searchField.text = raw
                        } finally {
                            updatingSearchField = false
                        }
                    }
                }
            }
        }
    }

    /** Lo que tiene el foco al abrir la pestaña: la columna activa, para poder moverse con el teclado. */
    val preferredFocus: JComponent? get() = (active ?: columns.values.firstOrNull())?.focusTarget

    // --------------------------------------------------------------- composición

    /**
     * El buscador y, a su derecha, lo que es de todas las columnas: el repositorio y el
     * filtro —los mismos desplegables que la cabecera de la ventana—, buscar en todos los
     * repositorios y los ajustes. Crear y agrupar no están: son de cada columna y van en su
     * cabecera.
     */
    private fun buildHeader(): JComponent {
        val manager = ActionManager.getInstance()
        val group = DefaultActionGroup().apply {
            add(RepoSelectorAction(project))
            add(ViewFilterAction(project))
            manager.getAction(SEARCH_ALL_REPOS)?.let(::add)
            add(Separator.getInstance())
            manager.getAction(SETTINGS)?.let(::add)
        }
        val toolbar = manager.createActionToolbar(PLACE, group, true)
        toolbar.targetComponent = this
        toolbar.component.isOpaque = false
        toolbar.component.border = JBUI.Borders.empty()

        searchField.preferredSize = Dimension(JBUI.scale(SEARCH_WIDTH), searchField.preferredSize.height)
        return JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(3, 6),
            )
            add(searchField, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.CENTER)
        }
    }

    private fun installSearchField() {
        searchField.textEditor.accessibleContext.accessibleName = TasklaneBundle.message("a11y.search")
        TasklanePanel.showSearchHint(searchField)
        onKeymapChange(this) { TasklanePanel.showSearchHint(searchField) }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!updatingSearchField) search.setQuery(searchField.text)
            }
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        searchField.addCurrentTextToHistory()
                        active?.focusTree()
                    }

                    KeyEvent.VK_ESCAPE -> {
                        if (searchField.text.isEmpty()) return
                        e.consume()
                        search.clear()
                        active?.focusTree()
                    }
                }
            }
        })
    }

    /** Los estados con columna, en el orden de la configuración. */
    private fun onBoard(states: List<TaskState>, hidden: Set<StateId>): List<TaskState> =
        states.filter { it.id !in hidden }

    /**
     * Una columna por estado del tablero, en el orden de la configuración. Como en la tool window, la
     * identidad es el [StateId]: renombrar o reordenar estados en *Settings* no tira ninguna
     * columna, sólo la cambia de sitio.
     */
    private fun sync(states: List<TaskState>) {
        val ids = states.map { it.id }
        for (id in columns.keys - ids.toSet()) {
            columns.remove(id)?.let { column ->
                strip.remove(column)
                Disposer.dispose(column)
                if (active === column) active = null
                if (target === column) target = null
            }
        }
        for (id in ids) {
            columns.getOrPut(id) {
                TasklanePanel(project, id, ::focusColumn, this).also { column ->
                    Disposer.register(this, column)
                    column.whenFocused { active = column }
                }
            }
        }
        // Se vuelven a colocar sólo si cambió el orden: quitar y poner una columna le hace
        // perder el foco a quien lo tuviera.
        val placed = strip.components.toList()
        val wanted = ids.mapNotNull(columns::get)
        if (placed != wanted) {
            strip.removeAll()
            wanted.forEach(strip::add)
        }
        if (active == null) {
            active = states.firstOrNull { it.isDefault }?.id?.let(columns::get) ?: wanted.firstOrNull()
        }
        cards.show(center, if (wanted.isEmpty()) EMPTY else COLUMNS)
        strip.revalidate()
        strip.repaint()
    }

    /** Pone a la vista una columna que puede estar fuera, a los lados. */
    private fun show(column: TasklanePanel) {
        strip.scrollRectToVisible(column.bounds)
    }

    /** `⌥←/→` sobre una columna: pasar a la de al lado. */
    private fun focusColumn(state: StateId) {
        val column = columns[state] ?: return
        show(column)
        column.focusTree()
    }

    /** Cómo encuentran la columna activa las acciones de la barra de arriba. */
    override fun uiDataSnapshot(sink: DataSink) {
        sink.set(TasklaneDataKeys.PANEL, active)
    }

    // ---------------------------------------------------------------- BoardHost

    override fun shows(state: StateId): Boolean = state in columns

    override fun focusSearch() {
        searchField.textEditor.requestFocusInWindow()
        searchField.textEditor.selectAll()
    }

    override fun follow(state: StateId, ids: Set<TaskId>) {
        val column = columns[state] ?: return
        show(column)
        column.follow(ids)
    }

    override fun carry(source: TasklanePanel, tasks: List<Task>, screen: Point): Boolean {
        nudge(screen)
        val over = columnAt(screen)?.takeIf { it !== source }
        if (over !== target) {
            target?.carryOver(null)
            target = over
        }
        over?.carryOver(screen)
        return over != null
    }

    override fun drop(source: TasklanePanel, tasks: List<Task>, screen: Point): Boolean {
        val over = columnAt(screen)?.takeIf { it !== source }
        target?.takeIf { it !== over }?.carryOver(null)
        target = null
        if (over == null) return false
        // Lo pintado por última vez es lo que se prometió: se recalcula con el punto de
        // soltar por si el último movimiento no llegó a pintarse.
        over.carryOver(screen)
        over.receive(tasks)
        return true
    }

    /**
     * La columna que hay bajo [screen]. Sólo dentro de lo que se ve del tablero: una columna
     * cortada por el borde sigue midiendo su ancho entero por fuera, y eso podría ser el
     * árbol del proyecto que tiene al lado.
     */
    private fun columnAt(screen: Point): TasklanePanel? {
        if (!scroll.viewport.isShowing) return null
        val visible = Rectangle(scroll.viewport.locationOnScreen, scroll.viewport.size)
        if (!visible.contains(screen)) return null
        return columns.values.firstOrNull { column ->
            column.isShowing && Rectangle(column.locationOnScreen, column.size).contains(screen)
        }
    }

    /**
     * Arrastrando cerca de un borde del tablero, se desplaza hacia ese lado: la columna de
     * destino puede no caber en pantalla. Va con el ratón —cada movimiento, un paso—, como
     * la columna de destino en vertical: ver [TasklanePanel.carryOver].
     */
    private fun nudge(screen: Point) {
        val viewport = scroll.viewport
        if (!viewport.isShowing) return
        val point = Point(screen).also { SwingUtilities.convertPointFromScreen(it, viewport) }
        if (point.y !in 0 until viewport.height) return
        val edge = JBUI.scale(EDGE)
        val step = when {
            point.x < edge -> -JBUI.scale(STEP)
            point.x >= viewport.width - edge -> JBUI.scale(STEP)
            else -> return
        }
        val last = (strip.width - viewport.width).coerceAtLeast(0)
        val position = viewport.viewPosition
        val x = (position.x + step).coerceIn(0, last)
        if (x != position.x) viewport.viewPosition = Point(x, position.y)
    }

    override fun dispose() {
        scope.cancel()
        columns.clear()
    }

    private companion object {
        const val PLACE = "TasklaneBoard"
        const val COLUMNS = "columns"
        const val EMPTY = "empty"
        const val SEARCH_ALL_REPOS = "Tasklane.SearchAllRepos"
        const val SETTINGS = "Tasklane.Settings"

        /** El buscador: lo bastante para una consulta con operadores, sin comerse la barra. */
        const val SEARCH_WIDTH = 360

        /** Cuánto antes del borde empieza a desplazarse el tablero arrastrando, y cuánto cada vez. */
        const val EDGE = 48
        const val STEP = 24
    }
}

/**
 * Las columnas, una al lado de otra y todas del mismo ancho, con una raya entre cada dos.
 *
 * **Llenan el hueco si caben a su ancho mínimo**, que es el de la tool window
 * (`TasklanePanel.MIN_WIDTH`): por debajo de eso la tarjeta deja de leerse, y aquí vale lo
 * mismo. Si no caben, cada una se queda en su mínimo y aparece la barra horizontal. Todas
 * miden el alto entero, y cada una se desplaza por su cuenta, como en cualquier tablero.
 */
private class ColumnStrip : JPanel(StripLayout()), Scrollable {

    init {
        isOpaque = false
    }

    /** La raya entre columnas, en el hueco que deja [StripLayout]. */
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        g.color = JBColor.border()
        val gap = StripLayout.gap()
        for (i in 0 until componentCount - 1) {
            val column = getComponent(i)
            g.fillRect(column.x + column.width, 0, gap, height)
        }
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        JBUI.scale(UNIT)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.HORIZONTAL) visibleRect.width else visibleRect.height

    /** Sigue al ancho del hueco mientras quepan todas; si no, se queda en el suyo y sale la barra. */
    override fun getScrollableTracksViewportWidth(): Boolean {
        val viewport = parent as? JViewport ?: return false
        return viewport.width >= minimumSize.width
    }

    override fun getScrollableTracksViewportHeight(): Boolean = true

    private companion object {
        const val UNIT = 16
    }
}

/** Ver [ColumnStrip]. */
internal class StripLayout : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: java.awt.Component?) = Unit

    override fun removeLayoutComponent(comp: java.awt.Component?) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = minimumLayoutSize(parent)

    override fun minimumLayoutSize(parent: Container): Dimension {
        val n = parent.componentCount
        if (n == 0) return Dimension()
        return Dimension(n * columnMinimum(parent) + (n - 1) * gap(), 0)
    }

    override fun layoutContainer(parent: Container) {
        val n = parent.componentCount
        if (n == 0) return
        val gap = gap()
        val width = maxOf(columnMinimum(parent), (parent.width - (n - 1) * gap) / n)
        var x = 0
        for (i in 0 until n) {
            // La última se lleva lo que sobre de redondear, para que no quede una raya de
            // fondo a la derecha.
            val w = if (i == n - 1) maxOf(width, parent.width - x) else width
            parent.getComponent(i).setBounds(x, 0, w, parent.height)
            x += w + gap
        }
    }

    /** El mínimo de la más exigente: todas miden lo mismo, y ninguna baja del suyo. */
    private fun columnMinimum(parent: Container): Int =
        (0 until parent.componentCount).maxOf { parent.getComponent(it).minimumSize.width }

    companion object {
        fun gap(): Int = JBUI.scale(1)
    }
}
