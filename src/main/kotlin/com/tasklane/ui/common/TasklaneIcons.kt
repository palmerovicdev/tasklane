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
}
