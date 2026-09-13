package com.tasklane.ui.common

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * [IconLoader] resuelve solo la variante `_dark` y el escalado HiDPI, así que
 * basta con referenciar el fichero base.
 */
internal object TasklaneIcons {

    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/tasklane.svg", TasklaneIcons::class.java)

    /**
     * El vencimiento de una fila. Es propio porque `AllIcons` no trae calendario ni
     * reloj, y sin él la fila tenía que llevar la palabra «Due» delante de la fecha
     * para distinguirla de la de modificación, que va justo al lado.
     */
    @JvmField
    val Calendar: Icon = IconLoader.getIcon("/icons/calendar.svg", TasklaneIcons::class.java)
}
