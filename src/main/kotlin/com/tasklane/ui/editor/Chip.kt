package com.tasklane.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Una ficha con aspa: la forma en la que el diálogo enseña los elementos de una lista.
 *
 * Vive aparte porque la usan dos campos —las etiquetas y las anclas de código— y son
 * la misma cosa vista dos veces: un elemento que se ve, que se puede quitar, y del que
 * no se edita el texto. La diferencia entre ellos está en **cómo se añaden**, no en
 * cómo se pintan.
 */
internal class Chip(
    text: String,
    icon: Icon? = null,
    tooltip: String? = null,
    removeTooltip: String,
    /** El del texto, si no es el de siempre: el color de una etiqueta (P30). */
    foreground: Color? = null,
    private val onRemove: () -> Unit,
) : JPanel(BorderLayout(JBUI.scale(GAP), 0)) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, PADDING, 1, 2)
        add(
            JBLabel(text).apply {
                // Se asigna sólo si lo hay, en vez de pasar un icono nulo al
                // constructor: las etiquetas van sin él y el de la plataforma no
                // promete admitirlo.
                icon?.let { this.icon = it }
                foreground?.let { this.foreground = it }
                font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
                toolTipText = tooltip
            },
            BorderLayout.CENTER,
        )
        add(closeButton(removeTooltip), BorderLayout.EAST)
    }

    private fun closeButton(tooltip: String) = JBLabel(AllIcons.Actions.Close).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = tooltip
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                icon = AllIcons.Actions.CloseHovered
            }

            override fun mouseExited(e: MouseEvent) {
                icon = AllIcons.Actions.Close
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                onRemove()
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.color = JBUI.CurrentTheme.ActionButton.pressedBackground()
            val arc = JBUI.scale(ARC)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private companion object {
        const val GAP = 4
        const val PADDING = 6
        const val ARC = 10
    }
}

/**
 * Coloca las fichas de izquierda a derecha y salta de línea cuando no caben.
 *
 * No es `FlowLayout` porque éste no sabe decir cuánto mide envolviendo: preguntado por
 * su tamaño preferido contesta el de una sola fila, y el campo se quedaría con la mitad
 * de las fichas fuera en cuanto hubiera unas cuantas.
 *
 * Con [stretchLast], el último hijo se queda con lo que sobre de su fila. Es lo que
 * necesita el campo de etiquetas, cuyo último hijo es el hueco donde se escribe la
 * siguiente; una lista de fichas a secas no lo quiere.
 */
internal class ChipsLayout(private val stretchLast: Boolean = false) : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component) = Unit

    override fun removeLayoutComponent(comp: Component) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = measure(parent)

    override fun minimumLayoutSize(parent: Container): Dimension = measure(parent)

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val gap = JBUI.scale(GAP)
        val limit = parent.width - insets.right
        val children = parent.components.filter { it.isVisible }

        var x = insets.left
        var y = insets.top
        var rowHeight = 0
        children.forEachIndexed { index, child ->
            val size = child.preferredSize
            val last = stretchLast && index == children.lastIndex
            val needed = if (last) JBUI.scale(MIN_LAST) else size.width
            if (x > insets.left && x + needed > limit) {
                x = insets.left
                y += rowHeight + gap
                rowHeight = 0
            }
            val width = if (last) (limit - x).coerceAtLeast(JBUI.scale(MIN_LAST)) else size.width
            child.setBounds(x, y, width, size.height)
            x += width + gap
            rowHeight = maxOf(rowHeight, size.height)
        }
    }

    private fun measure(parent: Container): Dimension {
        val insets = parent.insets
        val gap = JBUI.scale(GAP)
        val children = parent.components.filter { it.isVisible }
        if (children.isEmpty()) return Dimension(insets.left + insets.right, insets.top + insets.bottom)

        // Sin ancho todavía —la primera medida— se supone una sola fila; cuando el
        // contenedor ya tiene tamaño, quien reconstruye revalida y la cuenta sale bien.
        val available = (parent.width - insets.left - insets.right).takeIf { it > 0 } ?: Int.MAX_VALUE
        var x = 0
        var widest = 0
        var rows = 1
        var rowHeight = 0
        for (child in children) {
            val size = child.preferredSize
            if (x > 0 && x + size.width > available) {
                widest = maxOf(widest, x - gap)
                rows++
                x = 0
            }
            x += size.width + gap
            rowHeight = maxOf(rowHeight, size.height)
        }
        widest = maxOf(widest, x - gap)
        return Dimension(
            minOf(widest, available) + insets.left + insets.right,
            rows * rowHeight + (rows - 1) * gap + insets.top + insets.bottom,
        )
    }

    private companion object {
        const val GAP = 4

        /** Lo mínimo que se le deja al último hijo cuando se estira. */
        const val MIN_LAST = 60
    }
}
