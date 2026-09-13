package com.tasklane.ui.actions

import com.intellij.openapi.actionSystem.DataKey
import com.tasklane.ui.toolwindow.TasklanePanel

/**
 * Cómo una acción registrada encuentra la pestaña sobre la que trabaja.
 *
 * Es lo que permite que las acciones vivan en `plugin.xml` —y por tanto aparezcan en
 * *Settings → Keymap* y en *Search Everywhere*— en vez de ser objetos anónimos
 * creados dentro del panel. La pestaña se publica en el `DataContext` y cada acción
 * la recoge de ahí, sin que ninguna tenga que saber cuántos paneles hay abiertos.
 */
internal object TasklaneDataKeys {
    val PANEL: DataKey<TasklanePanel> = DataKey.create("Tasklane.Panel")
}
