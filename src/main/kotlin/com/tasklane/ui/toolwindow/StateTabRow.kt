package com.tasklane.ui.toolwindow

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskState
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

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

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 0, 2)
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
                override fun focusGained(e: FocusEvent) = repaint()
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
            accessibleContext.accessibleName = TasklaneBundle.message("a11y.tab", name, count)
            repaint()
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
    }
}
