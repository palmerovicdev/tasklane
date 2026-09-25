package com.tasklane.ui.toolwindow

import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import com.intellij.util.ui.JBUI

/**
 * La columna de controles de la tarjeta (2.11.1): el primer hijo —la fila de marcador,
 * menú y desplegar— arriba, y el segundo —el asa de reordenar— en medio del hueco que
 * queda debajo, un poco separado del borde derecho (2.11.2: pegado abajo y al borde se
 * leía como parte de la línea de distintivos, y no como algo de la tarjeta).
 *
 * **Mide lo que mide la fila de arriba**, y es a propósito: el asa vive en el hueco que
 * la columna ya dejaba libre bajo los controles, y si contara para el alto preferido, una
 * tarjeta de una línea de título crecería sólo por estar en una pestaña a mano. El
 * `BorderLayout` de la tarjeta le da a esta columna todo el alto de la fila, que es donde
 * cabe el asa.
 */
internal class EastColumn : LayoutManager {

    private companion object {
        /** Lo que se separa el asa del borde derecho, en píxeles lógicos. */
        const val RIGHT_GAP = 4
    }


    override fun addLayoutComponent(name: String?, comp: java.awt.Component?) = Unit

    override fun removeLayoutComponent(comp: java.awt.Component?) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        val top = parent.getComponent(0).takeIf { it.isVisible }?.preferredSize ?: Dimension()
        val bottom = parent.componentCount.takeIf { it > 1 }?.let { parent.getComponent(1) }
            ?.takeIf { it.isVisible }?.preferredSize ?: Dimension()
        return Dimension(
            maxOf(top.width, bottom.width) + insets.left + insets.right,
            top.height + insets.top + insets.bottom,
        )
    }

    override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val width = parent.width - insets.left - insets.right
        val height = parent.height - insets.top - insets.bottom
        val top = parent.getComponent(0)
        val topSize = top.preferredSize
        top.setBounds(insets.left + width - topSize.width, insets.top, topSize.width, topSize.height)
        if (parent.componentCount < 2) return
        val bottom = parent.getComponent(1)
        val size = bottom.preferredSize
        // A media altura del hueco que dejan los controles, y nunca encima de ellos.
        val free = height - topSize.height
        val y = insets.top + topSize.height + ((free - size.height) / 2).coerceAtLeast(0)
        val x = insets.left + width - size.width - JBUI.scale(RIGHT_GAP)
        bottom.setBounds(x.coerceAtLeast(insets.left), y, size.width, size.height)
    }
}
