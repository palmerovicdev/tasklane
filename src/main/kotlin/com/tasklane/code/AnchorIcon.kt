package com.tasklane.code

import com.intellij.ui.scale.JBUIScale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import javax.swing.Icon

/**
 * La marca de «aquí hay una tarea», dibujada y no cargada de un SVG.
 *
 * **Se pinta a mano por el color.** El icono lleva el color de la prioridad, y ésa la
 * configura el usuario: con ficheros haría falta uno por prioridad y tema, y dejarían
 * de existir en cuanto alguien añadiera una cuarta prioridad o cambiara un color en
 * *Settings*. `IconLoader` no tiñe colores arbitrarios; un `Path2D` sí.
 *
 * El dibujo es **el logo de Tasklane**: el visto con su renglón. Una fila es una tarea;
 * dos filas —el logo entero— es que en esa línea hay más de una. Así la marca del
 * editor y la de la ventana son reconociblemente la misma cosa, y «hay varias» no
 * necesita ni un número ni un segundo icono que aprenderse.
 *
 * Las coordenadas son de una rejilla de [SIZE]×[SIZE] y se escalan con el factor del
 * IDE, igual que hace `IconLoader` con los SVG.
 */
internal class AnchorIcon(private val color: Color, private val stacked: Boolean) : Icon {

    override fun getIconWidth(): Int = JBUIScale.scale(SIZE)

    override fun getIconHeight(): Int = JBUIScale.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val scale = JBUIScale.scale(1f).toDouble()
            g2.translate(x, y)
            g2.scale(scale, scale)
            g2.color = color
            g2.stroke = BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (stacked) {
                row(g2, 3.2f)
                row(g2, 8.8f)
            } else {
                row(g2, 6f)
            }
        } finally {
            g2.dispose()
        }
    }

    /** Un visto y su renglón, a la altura [middle] de la rejilla. */
    private fun row(g2: Graphics2D, middle: Float) {
        val check = Path2D.Float()
        check.moveTo(1.4f, middle - 0.2f)
        check.lineTo(2.8f, middle + 1.2f)
        check.lineTo(5.3f, middle - 1.6f)
        g2.draw(check)
        g2.draw(Line2D.Float(7.1f, middle - 0.2f, 10.6f, middle - 0.2f))
    }

    override fun equals(other: Any?): Boolean =
        other is AnchorIcon && other.color == color && other.stacked == stacked

    override fun hashCode(): Int = 31 * color.hashCode() + stacked.hashCode()

    companion object {
        /** La rejilla del dibujo. 12 es lo que mide un icono del margen del editor. */
        const val SIZE = 12

        /** El mismo grosor de trazo que los iconos propios de la barra. */
        private const val STROKE = 1.3f
    }
}
