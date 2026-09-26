package com.tasklane.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapManagerListener

/**
 * El atajo de la acción [actionId] tal como esté **ahora** en el Keymap, o [fallback] si no
 * tiene ninguno (P34).
 *
 * Hasta la 2.24 los atajos de la lista se leían una vez, al abrir la ventana: reasignar
 * *Edit* en *Settings → Keymap* no se notaba hasta reabrir el proyecto, y quitarle el atajo
 * lo dejaba sin ninguno en vez de volver al de la lista. El despachador de teclas del IDE
 * pregunta `shortcuts` en cada pulsación, así que contestar aquí lo que diga el Keymap en ese
 * momento basta para que el cambio se aplique al instante, sin oyentes.
 */
internal class KeymapShortcut(private val actionId: String, private val fallback: ShortcutSet) : ShortcutSet {

    override fun getShortcuts(): Array<Shortcut> {
        val assigned = KeymapManager.getInstance()?.activeKeymap?.getShortcuts(actionId)
        return if (assigned.isNullOrEmpty()) fallback.shortcuts else assigned
    }
}

/**
 * Llama a [run] cada vez que cambia el Keymap —otro keymap activo, o un atajo reasignado—
 * mientras viva [parent]. Es para lo que **enseña** un atajo, como la pista del buscador: lo
 * que sólo lo usa ya lo lee al vuelo, ver [KeymapShortcut].
 */
internal fun onKeymapChange(parent: Disposable, run: () -> Unit) {
    ApplicationManager.getApplication().messageBus.connect(parent).subscribe(
        KeymapManagerListener.TOPIC,
        object : KeymapManagerListener {
            override fun activeKeymapChanged(keymap: Keymap?) = run()

            override fun shortcutsChanged(keymap: Keymap, actionIds: Collection<String>, fromSettings: Boolean) = run()
        },
    )
}
