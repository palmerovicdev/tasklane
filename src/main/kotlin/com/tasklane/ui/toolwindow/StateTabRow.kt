package com.tasklane.ui.toolwindow

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskState
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * La fila de estados, justo encima del buscador: `ToDo 5`, `Doing 0`, `Done 2`.
 *
 * Vive **dentro** del panel y no en la cabecera de la tool window. Las pestañas del
 * `ContentManager` eran lo anterior y daban la barra nativa gratis, pero el header
 * las mezclaba con el título, el selector de repositorio y el filtro, y con eso
 * dejaba de leerse de un vistazo cuántas tareas hay en cada estado. Aquí la fila es
 * lo primero que se ve encima de la lista. El precio está anotado en
 * `docs/plan-rediseno.md`: se pierde `Alt+←/→`, que lo ponía el IDE.
 *
 * El recuento es un número a secas. Es lo que hay en la pestaña: la palabra sobra
 * porque la fila entera habla de tareas.
 *
 * **Se desplaza de lado** (2.29.0): con muchos estados o la ventana estrecha, las pestañas
 * que no caben se quedaban cortadas por la derecha, sin forma de llegar a ellas con el
 * ratón. Va dentro de [scrollable], y la rueda, el trackpad y el tabulador la mueven; la
 * pestaña elegida se trae siempre a la vista.
 */
internal class StateTabRow(private val onSelect: (StateId) -> Unit) :
    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(VGAP))) {

    private val tabs = mutableMapOf<StateId, Tab>()

    /**
     * Qué estados hay y en qué orden, tal y como se construyeron los botones. Se
     * guarda para no reconstruir la fila en cada repintado: los recuentos cambian con
     * cada tecla del buscador y los estados casi nunca.
     */
    private var built: List<Pair<StateId, String>> = emptyList()

    /** La pestaña que se trajo a la vista la última vez, para no pelearse con quien desplaza. */
    private var revealed: StateId? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 0, 2)
        // La rueda de siempre, la vertical, también la desplaza: una fila que sólo se mueve
        // con Mayúsculas o con el trackpad de lado no la encuentra nadie. La clave de
        // `JBScrollPane` que hace esto es `@ApiStatus.Experimental`, así que se hace aquí.
        addMouseWheelListener(::scrollSideways)
    }

    /**
     * La fila dentro de su desplazamiento lateral, sin borde ni fondo propios: es lo que se
     * coloca en el panel. Sin barra vertical, y la horizontal sólo cuando no caben.
     *
     * **Cuando no caben, la barra lleva su propio hueco debajo.** En macOS es de las que se
     * superponen, y `JBScrollPane` no le aparta sitio ni la cuenta en el alto preferido: se
     * pintaba encima de las pestañas y les cortaba media letra. Se mide contra el ancho del
     * contenedor, que es el que ya tiene el ancho nuevo cuando se pregunta por el alto.
     */
    fun scrollable(): JComponent = object : JBScrollPane(
        this,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
    ) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            val room = (parent?.let { it.width - it.insets.left - it.insets.right } ?: width) - insets.left - insets.right
            if (room > 0 && this@StateTabRow.preferredSize.width > room) size.height += horizontalScrollBar.preferredSize.height
            return size
        }
    }.apply {
        border = JBUI.Borders.empty()
        viewportBorder = null
        isOpaque = false
        viewport.isOpaque = false
    }

    private fun scrollSideways(e: MouseWheelEvent) {
        val bar = (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane)
            ?.horizontalScrollBar ?: return
        if (!bar.isVisible) return
        val step = (e.preciseWheelRotation * JBUI.scale(WHEEL_STEP)).roundToInt()
        if (step == 0) return
        bar.value = (bar.value + step).coerceIn(bar.minimum, bar.maximum - bar.visibleAmount)
        e.consume()
    }

    fun update(states: List<TaskState>, counts: Map<StateId, Int>, selected: StateId) {
        val signature = states.map { it.id to it.name }
        if (signature != built) {
            built = signature
            removeAll()
            tabs.clear()
            for (state in states) {
                val tab = Tab(state.id)
                tabs[state.id] = tab
                add(tab)
            }
            revalidate()
        }
        for (state in states) {
            tabs[state.id]?.show(state.name, counts[state.id] ?: 0, state.id == selected)
        }
        repaint()
        // Elegida con `⌥←/→` o desde otro sitio, la pestaña puede estar fuera de la vista.
        // Después de colocarla, que es cuando tiene sitio; y sólo si cambió, porque los
        // recuentos se repintan con cada tecla del buscador.
        if (selected != revealed) {
            revealed = selected
            SwingUtilities.invokeLater { tabs[selected]?.let(::reveal) }
        }
    }

    private fun reveal(tab: JComponent) {
        scrollRectToVisible(tab.bounds)
    }

    /**
     * Un estado. Es un [SimpleColoredComponent] y no un `JButton` porque necesita dos
     * tipografías en la misma línea —nombre y número— y porque así hereda el
     * tratamiento de fuente y colores del tema sin configurar nada.
     */
    private inner class Tab(private val id: StateId) : SimpleColoredComponent() {

        private var active = false
        private var hovered = false

        init {
            isOpaque = false
            ipad = JBUI.insets(PADDING_V, PADDING_H)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            // Focusable: al bajar las pestañas del `ContentManager` al panel se perdió
            // el recorrido de teclado que daba la plataforma, y una fila de estados a
            // la que sólo se llega con el ratón deja la ventana inservible sin él.
            // `Tab` sale ahora en el recorrido del tabulador y se activa con Espacio
            // o Intro, que es lo que espera cualquiera que use un botón.
            isFocusable = true
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER) {
                        e.consume()
                        onSelect(id)
                    }
                }
            })
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    // Tabulando se llega también a las que no se ven.
                    reveal(this@Tab)
                    repaint()
                }

                override fun focusLost(e: FocusEvent) = repaint()
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) onSelect(id)
                }
            })
        }

        fun show(name: String, count: Int, active: Boolean) {
            this.active = active
            clear()
            val style = if (active) {
                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            append(name, style)
            append("  $count", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            // El nombre accesible se dice entero: un lector de pantalla leería
            // «ToDo 5» como dos cosas sueltas sin saber qué es el número.
            // `getAccessibleContext()` y no `accessibleContext`: en Kotlin ese nombre
            // resuelve al **campo** protegido que hereda de `JComponent` —nulo hasta
            // que alguien lo llena— y no al método de abajo, que es el que lo crea.
            getAccessibleContext().accessibleName = TasklaneBundle.message("a11y.tab", name, count)
            repaint()
        }

        /**
         * `SimpleColoredComponent` **no crea** su `AccessibleContext`: hereda el
         * `getAccessibleContext` de `JComponent`, que devuelve el campo tal cual y
         * es nulo hasta que alguien lo llena. Ponerle el nombre accesible sin esto
         * lanzaba un `NullPointerException` dentro de `update`, y como la fila se
         * actualiza **antes** de reconstruir el árbol, la ventana se quedaba con los
         * recuentos puestos y la lista vacía. Lo cubre `StateTabRowTest`.
         *
         * De paso el rol correcto: `PAGE_TAB` y no el `SWING_COMPONENT` genérico, que
         * es lo que hace que un lector de pantalla diga «pestaña» al llegar.
         */
        override fun getAccessibleContext(): AccessibleContext {
            if (accessibleContext == null) {
                accessibleContext = object : AccessibleJComponent() {
                    override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PAGE_TAB
                }
            }
            return accessibleContext
        }

        override fun paintComponent(g: Graphics) {
            val background = when {
                active -> JBUI.CurrentTheme.ActionButton.pressedBackground()
                hovered -> JBUI.CurrentTheme.ActionButton.hoverBackground()
                else -> null
            }
            val arc = JBUI.scale(ARC)
            if (background != null) {
                val config = GraphicsUtil.setupAAPainting(g)
                g.color = background
                g.fillRoundRect(0, 0, width, height, arc, arc)
                config.restore()
            }
            // Sin este contorno, tabular por la fila no se ve: el fondo de la activa
            // ya está puesto y el foco no cambiaría nada en pantalla.
            if (hasFocus()) {
                val config = GraphicsUtil.setupAAPainting(g)
                g.color = JBUI.CurrentTheme.Focus.focusColor()
                g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                config.restore()
            }
            super.paintComponent(g)
        }
    }

    private companion object {
        const val GAP = 4
        const val VGAP = 2
        const val PADDING_H = 8
        const val PADDING_V = 3
        const val ARC = 10

        /** Cuánto desplaza un paso de rueda: más o menos media pestaña corta. */
        const val WHEEL_STEP = 24
    }
}
