package com.tasklane

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.TasklaneBundle"

/**
 * Acceso tipado a los textos del plugin.
 *
 * [DynamicBundle] y no [java.util.ResourceBundle] porque permite que un plugin de
 * localización aporte traducciones sin que nosotros hagamos nada.
 */
internal object TasklaneBundle : DynamicBundle(BUNDLE) {

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
