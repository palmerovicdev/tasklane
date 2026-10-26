package com.tasklane.ui.common

import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.tasklane.domain.model.TaskState
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import javax.swing.Icon

/**
 * El icono de un estado (2.30.0): un círculo que se va llenando según lo avanzado que está
 * el estado en el flujo, y lleno con un visto si es terminal. Es el de los gestores de
 * incidencias —vacío *ToDo*, medio *Doing*, visto *Done*—, y por eso se lee como «estado»
 * sin explicarlo.
 *
 * Hasta la 2.29 el botón de estado de la tarjeta y *Move To ▸* llevaban `MoveTo2`, la
 * flecha que entra en una caja: decía «mover», que es lo que hace por debajo, pero no
 * «estado», que es lo que se está eligiendo. Ahora el botón enseña además **en cuál está**
 * la tarea, y cada entrada de *Move To ▸* el suyo.
 *
 * Se pinta y no es un SVG como los demás iconos del plugin porque cuánto se llena depende de
 * cuántos estados haya y en qué orden, que es configuración del proyecto. Mismos dos grises
 * y misma rejilla de 16 que los SVG (ver `docs/tools/gen_format_icons.py`).
 */
internal object StateIcons {

    /** El del grupo *Move To ▸*, que no es de ningún estado: medio lleno. */
    val generic: Icon = StateIcon(HALF)

    fun of(state: TaskState, states: List<TaskState>): Icon {
        val progress = progress(state, states)
        return cache.getOrPut(progress) { StateIcon(progress) }
    }

    /**
     * Cuánto se llena el círculo, de `0` —el primer estado abierto— hacia `1`, o `null` si
     * [state] es terminal. Cuentan sólo los abiertos, en el orden de las pestañas: con
     * *ToDo*, *Doing* y *Done*, `0`, `½` y el visto; con *Backlog*, *ToDo*, *Doing* y
     * *Review* delante del terminal, cuartos. Nunca llega a `1`, que se confundiría con el
     * terminal.
     */
    fun progress(state: TaskState, states: List<TaskState>): Float? {
        if (state.terminal) return null
        val open = states.filterNot(TaskState::terminal)
        val index = open.indexOfFirst { it.id == state.id }.coerceAtLeast(0)
        return if (open.isEmpty()) 0f else index.toFloat() / open.size
    }

    private val cache = HashMap<Float?, Icon>()

    private const val HALF = 0.5f
}

/** [fill] nulo: terminal. Ver [StateIcons.progress]. */
private class StateIcon(private val fill: Float?) : Icon {

    override fun getIconWidth(): Int = JBUI.scale(SIZE)

    override fun getIconHeight(): Int = JBUI.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.translate(x, y)
            val scale = JBUI.scale(1f)
            g2.scale(scale.toDouble(), scale.toDouble())
            g2.color = STROKE
            if (fill == null) {
                // Lleno hasta el borde exterior del trazo: el mismo tamaño que el vacío.
                val r = OUTER + HALF_STROKE
                g2.fill(Ellipse2D.Double(CENTER - r, CENTER - r, 2 * r, 2 * r))
                g2.color = CHECK
                g2.stroke = BasicStroke(CHECK_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(
                    Path2D.Double().apply {
                        moveTo(5.5, 8.25)
                        lineTo(7.25, 10.0)
                        lineTo(10.5, 6.25)
                    },
                )
                return
            }
            g2.stroke = BasicStroke(STROKE_WIDTH)
            g2.draw(Ellipse2D.Double(CENTER - OUTER, CENTER - OUTER, 2 * OUTER, 2 * OUTER))
            if (fill > 0f) {
                // Desde las doce y en el sentido del reloj, como se lee un avance.
                g2.fill(Arc2D.Double(CENTER - INNER, CENTER - INNER, 2 * INNER, 2 * INNER, 90.0, -360.0 * fill, Arc2D.PIE))
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val SIZE = 16
        const val CENTER = 8.0
        const val OUTER = 5.5
        const val INNER = 3.25
        const val STROKE_WIDTH = 1f
        const val HALF_STROKE = 0.5
        const val CHECK_STROKE = 1.3f

        /** Los dos grises de la interfaz nueva, los de los SVG del plugin. */
        val STROKE = JBColor(0x6C707E, 0xCED0D6)

        /** El visto, sobre el círculo lleno: claro en el tema claro y al revés. */
        val CHECK = JBColor(0xFFFFFF, 0x2B2D30)
    }
}
