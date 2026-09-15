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
 *
 * Se **delega** en una instancia en vez de heredar: el constructor `DynamicBundle(String)`
 * que usa la herencia está obsoleto y el Plugin Verifier lo cuenta como API deprecada. El
 * de `(Class, String)` localiza el fichero con el classloader de la clase que se le pasa,
 * que es el del plugin.
 */
internal object TasklaneBundle {

    private val instance = DynamicBundle(TasklaneBundle::class.java, BUNDLE)

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        instance.getMessage(key, *params)
}
