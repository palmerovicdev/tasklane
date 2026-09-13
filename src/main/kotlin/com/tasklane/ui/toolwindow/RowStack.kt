package com.tasklane.ui.toolwindow

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * Apila de arriba abajo las líneas visibles de una fila, cada una con su altura
 * natural y todo el ancho disponible.
 *
 * Sustituye al `BorderLayout` de dos huecos que tenía la fila cuando sólo podía
 * tener dos líneas. Con el título envuelto en hasta tres, la descripción y la línea
 * de metadatos, los huecos con nombre se acabaron; y `BoxLayout` no sirve porque
 * estira los componentes hasta su `maximumSize`, que en un `SimpleColoredComponent`
 * es ilimitado.
 *
 * Los invisibles no ocupan: es lo que permite que una tarea sin descripción ni
 * etiquetas siga midiendo exactamente una línea.
 */
internal class RowStack : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component) = Unit

    override fun removeLayoutComponent(comp: Component) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = measure(parent)

    override fun minimumLayoutSize(parent: Container): Dimension = measure(parent)

    override fun layoutContainer(parent: Container) {
        var y = parent.insets.top
        for (child in parent.components) {
            if (!child.isVisible) continue
            val height = child.preferredSize.height
            child.setBounds(parent.insets.left, y, parent.width - parent.insets.left - parent.insets.right, height)
            y += height
        }
    }

    private fun measure(parent: Container): Dimension {
        var width = 0
        var height = 0
        for (child in parent.components) {
            if (!child.isVisible) continue
            val size = child.preferredSize
            width = maxOf(width, size.width)
            height += size.height
        }
        val insets = parent.insets
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }
}
