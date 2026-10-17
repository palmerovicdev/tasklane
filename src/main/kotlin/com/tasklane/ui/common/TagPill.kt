package com.tasklane.ui.common

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.domain.model.TagColor
import java.awt.Color

/**
 * Cómo se pinta una etiqueta (2.29.0): una **pastilla rellena** con su color, y la letra
 * blanca o casi negra según lo que se lea mejor encima. Hasta la 2.28 el color iba sólo en
 * la letra, y una etiqueta de color claro sobre la tarjeta casi no se veía.
 *
 * Lo usan la tarjeta y las fichas del diálogo, para que `#api` sea la misma cosa en los dos
 * sitios. Sin color, la pastilla es gris —la de las fichas de siempre— y la letra la del
 * tema: el color sigue siendo la excepción que se busca con la vista.
 */
internal object TagPill {

    /** El relleno: el color de la etiqueta en cada tema, o el gris de las fichas. */
    fun fill(color: TagColor?): Color =
        if (color == null) JBUI.CurrentTheme.ActionButton.pressedBackground() else JBColor(color.light, color.dark)

    /** La letra que se lee sobre [fill]. */
    fun text(color: TagColor?): Color =
        if (color == null) UIUtil.getLabelForeground() else JBColor(textOn(color.light), textOn(color.dark))

    /**
     * Blanco o casi negro, el de más contraste con [rgb] según WCAG. Un umbral fijo de
     * luminosidad se equivoca con los amarillos y los verdes claros, que son justo los que
     * más se eligen para una etiqueta.
     */
    fun textOn(rgb: Int): Int {
        val luminance = luminance(rgb)
        val withWhite = 1.05 / (luminance + 0.05)
        val withDark = (luminance + 0.05) / (luminance(DARK) + 0.05)
        return if (withWhite >= withDark) WHITE else DARK
    }

    private fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    const val WHITE = 0xFFFFFF
    const val DARK = 0x1E1F22
}
