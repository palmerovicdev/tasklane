package com.tasklane.ui.toolwindow

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * Coloca de izquierda a derecha los distintivos de la línea de metadatos.
 *
 * Existe porque un [com.intellij.ui.SimpleColoredComponent] sólo tiene **un** icono,
 * y la línea necesita varios: el del vencimiento, el
 * de cada etiqueta. Cada distintivo es entonces un componente propio, y esto los
 * alinea.
 *
 * Lo que no cabe se queda fuera en vez de estrecharse: un contador o una fecha
 * recortados a la mitad no dicen nada, y el orden en que se añaden ya es el de
 * importancia.
 */
internal class ChipRow : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component) = Unit

    override fun removeLayoutComponent(comp: Component) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = measure(parent)

    override fun minimumLayoutSize(parent: Container): Dimension = measure(parent)

    override fun layoutContainer(parent: Container) {
        val gap = JBUI.scale(GAP)
        var x = parent.insets.left
        val limit = parent.width - parent.insets.right
        for (child in parent.components) {
            if (!child.isVisible) continue
            val size = child.preferredSize
            if (x > parent.insets.left && x + size.width > limit) {
                child.setBounds(0, 0, 0, 0)
                continue
            }
            child.setBounds(x, parent.insets.top, size.width, size.height)
            x += size.width + gap
        }
    }

    private fun measure(parent: Container): Dimension {
        val gap = JBUI.scale(GAP)
        var width = 0
        var height = 0
        for (child in parent.components) {
            if (!child.isVisible) continue
            val size = child.preferredSize
            if (width > 0) width += gap
            width += size.width
            height = maxOf(height, size.height)
        }
        val insets = parent.insets
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    private companion object {
        const val GAP = 8
    }
}
