package com.tasklane.ui.common

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Insets
import javax.swing.border.Border

/**
 * Franja fina de color en el borde izquierdo de la fila.
 *
 * Se implementa como [Border] y no como componente a propósito: el renderer del
 * `CheckboxTree` ya ocupa el WEST de su BorderLayout con el checkbox, así que
 * insertar un componente ahí sería frágil. Pintando en el borde, la franja queda
 * en el extremo izquierdo real de la fila sin tocar el layout.
 */
internal class PriorityStripeBorder(private val color: JBColor) : Border {

    override fun getBorderInsets(c: Component): Insets = JBUI.insetsLeft(GAP + WIDTH)

    override fun isBorderOpaque(): Boolean = false

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val w = JBUI.scale(WIDTH)
        val inset = JBUI.scale(VERTICAL_INSET)
        g.color = color
        g.fillRect(x, y + inset, w, (height - inset * 2).coerceAtLeast(1))
    }

    private companion object {
        const val WIDTH = 3
        const val GAP = 5
        const val VERTICAL_INSET = 2
    }
}
