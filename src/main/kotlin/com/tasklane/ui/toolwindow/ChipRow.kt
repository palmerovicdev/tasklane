package com.tasklane.ui.toolwindow

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent

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
 *
 * **Salvo lo que se marca con [keep]** (2.3.0): un distintivo así no se cae nunca. Si no
 * cabe entero se coloca en su lugar su versión reducida —en la tarjeta, sólo el icono—,
 * y el sitio de esa versión se aparta **antes** de colocar nada: un distintivo normal
 * que va delante sólo entra si deja hueco para lo que viene detrás y no se puede caer.
 * Las versiones reducidas son hijos del mismo panel, invisibles para la medida, y sólo
 * reciben tamaño cuando sustituyen a su distintivo.
 *
 * [gap] es la separación entre uno y el siguiente, sin escalar. Los distintivos llevan
 * texto y necesitan aire; los tres botones de la derecha de la tarjeta son iconos que
 * ya traen su margen, y con el mismo hueco se leían como tres controles sueltos en vez
 * de como un grupo (2.3.0).
 */
internal class ChipRow(private val gap: Int = GAP) : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component) = Unit

    override fun removeLayoutComponent(comp: Component) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = measure(parent)

    override fun minimumLayoutSize(parent: Container): Dimension = measure(parent)

    override fun layoutContainer(parent: Container) {
        val gap = JBUI.scale(gap)
        val left = parent.insets.left
        val limit = parent.width - parent.insets.right
        for (child in parent.components) {
            if (isCompact(child)) child.setBounds(0, 0, 0, 0)
        }
        val shown = parent.components.filter { it.isVisible && !isCompact(it) }

        // Lo que todavía necesitan, como poco, los que no se pueden caer.
        var reserved = shown.sumOf { child -> compactOf(child)?.let { it.preferredSize.width + gap } ?: 0 }

        var x = left
        for (child in shown) {
            val size = child.preferredSize
            val compact = compactOf(child)
            if (compact != null) reserved -= compact.preferredSize.width + gap
            val room = limit - reserved
            val placed = when {
                x + size.width <= room -> child
                // El primero entra aunque no quepa, como siempre: una línea vacía no dice
                // nada. Pero sólo si no le quita el sitio a uno que no se puede caer.
                compact == null && x == left && reserved == 0 -> child
                compact != null -> compact
                else -> null
            }
            if (placed !== child) child.setBounds(0, 0, 0, 0)
            if (placed == null) continue
            val width = placed.preferredSize.width
            placed.setBounds(x, parent.insets.top, width, placed.preferredSize.height)
            x += width + gap
        }
    }

    private fun measure(parent: Container): Dimension {
        val gap = JBUI.scale(gap)
        var width = 0
        var height = 0
        for (child in parent.components) {
            if (!child.isVisible || isCompact(child)) continue
            val size = child.preferredSize
            if (width > 0) width += gap
            width += size.width
            height = maxOf(height, size.height)
        }
        val insets = parent.insets
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    private fun compactOf(component: Component): Component? =
        (component as? JComponent)?.getClientProperty(COMPACT) as? Component

    private fun isCompact(component: Component): Boolean =
        (component as? JComponent)?.getClientProperty(IS_COMPACT) == true

    companion object {
        /** El hueco entre distintivos. */
        const val GAP = 8

        private const val COMPACT = "tasklane.chipRow.compact"
        private const val IS_COMPACT = "tasklane.chipRow.isCompact"

        /**
         * [full] no se cae de la fila: cuando no quepa, se coloca [compact] en su lugar.
         * Los dos tienen que ser hijos del mismo panel, y [compact] detrás de [full].
         */
        fun keep(full: JComponent, compact: JComponent) {
            full.putClientProperty(COMPACT, compact)
            compact.putClientProperty(IS_COMPACT, true)
        }
    }
}
