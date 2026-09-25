package com.tasklane.ui.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * La casilla de una línea de lista de comprobación en la tarjeta (2.11.1).
 *
 * Dibujada y no un glifo: hasta la 2.11.0 era `☐`, que sale del tamaño que le da la
 * fuente de la interfaz —en macOS, bastante más pequeño que una letra— y no había forma
 * de agrandarlo sin agrandar la línea entera. Marcada lleva el azul de las casillas del
 * IDE, que es lo que se reconoce como «hecho» de un vistazo.
 */
internal class CheckBoxIcon(private val checked: Boolean) : Icon {

    override fun getIconWidth(): Int = JBUI.scale(SIZE)

    override fun getIconHeight(): Int = JBUI.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val size = JBUI.scale(SIZE).toFloat()
            val inset = JBUI.scale(1).toFloat()
            val arc = JBUI.scale(ARC).toFloat()
            val box = RoundRectangle2D.Float(x + inset / 2, y + inset / 2, size - inset, size - inset, arc, arc)
            if (checked) {
                g2.color = FILL
                g2.fill(box)
                g2.color = MARK
                g2.stroke = BasicStroke(JBUI.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val mark = Path2D.Float()
                mark.moveTo(x + size * 0.27f, y + size * 0.52f)
                mark.lineTo(x + size * 0.44f, y + size * 0.69f)
                mark.lineTo(x + size * 0.74f, y + size * 0.34f)
                g2.draw(mark)
            } else {
                g2.color = BORDER
                g2.stroke = BasicStroke(JBUI.scale(1f))
                g2.draw(box)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        /** En píxeles lógicos. Un poco más que la altura de una minúscula de la lista. */
        const val SIZE = 12
        private const val ARC = 3

        private val BORDER = JBColor(0x818594, 0x868A91)
        private val FILL = JBColor(0x3574F0, 0x3574F0)
        private val MARK = JBColor(0xFFFFFF, 0xFFFFFF)

        val UNCHECKED = CheckBoxIcon(false)
        val CHECKED = CheckBoxIcon(true)
    }
}
