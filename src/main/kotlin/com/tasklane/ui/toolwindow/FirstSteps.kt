package com.tasklane.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.StatusText
import com.tasklane.TasklaneBundle
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Lo que dice la lista de un repositorio **sin ninguna tarea** (P35).
 *
 * Hasta la 2.25 era «No tasks yet · Press the + button to create one», en texto y sin enlace,
 * justo cuando quien acaba de instalar el plugin decide si le sirve. Lo que distingue a
 * Tasklane no se ve desde una lista vacía, así que la lista lo enseña: crear, crear desde
 * cualquier sitio con `⌘⌥R`, anclar al código desde el editor, traerse los `TODO` que ya hay y
 * conectar un agente. Cada línea es un enlace que lo hace, con su atajo al lado si lo tiene.
 *
 * **Es el texto vacío del árbol, no un panel encima**: la lista sigue ahí debajo, con el foco,
 * `⌘V` y lo que se suelte, que es la otra forma de empezar. Todo lo de aquí se alcanza
 * también sin ratón, por su atajo o desde *Find Action*.
 *
 * Los atajos se leen del Keymap al pintar y el panel lo repinta si cambia: ver
 * [onKeymapChange].
 */
internal object FirstSteps {

    private const val QUICK_ADD = "Tasklane.QuickAdd"
    private const val FROM_HERE = "Tasklane.NewTaskFromCode"
    private const val IMPORT_TODOS = "Tasklane.ImportTodos"

    /**
     * *Settings → Tools → MCP Server*, del plugin del IDE: es donde se enciende el servidor y
     * se conecta el agente, y Tasklane no tiene ajuste propio para eso. El id es el mismo en la
     * 2026.1.5 y en la 2026.2.
     */
    private const val MCP_SETTINGS = "com.intellij.mcpserver.settings"

    /** El aire tras el título y entre enlaces; el de la plataforma, para lo demás. Ver [fill]. */
    private const val TITLE_GAP = 10
    private const val LINK_GAP = 4
    private const val DEFAULT_GAP = 2

    /**
     * Rellena [status]. [newTaskShortcut] es el de *New Task* dentro de la lista —`⌘N`, o el
     * que diga el Keymap—, que es local y no se deduce del id.
     */
    fun fill(status: StatusText, project: Project, owner: JComponent, newTaskShortcut: String?, newTask: () -> Unit) {
        status.clear()
        // El hueco se fija al crear cada línea y se queda puesto para las siguientes, también
        // para los otros textos vacíos de esta lista: se devuelve al de fábrica al final.
        status.withUnscaledGapAfter(TITLE_GAP)
        status.appendText(TasklaneBundle.message("toolwindow.tree.empty"))
        status.withUnscaledGapAfter(LINK_GAP)

        link(status, TasklaneBundle.message("firststeps.new"), newTaskShortcut) { newTask() }
        link(status, TasklaneBundle.message("firststeps.quickAdd"), shortcutOf(QUICK_ADD)) { event ->
            run(QUICK_ADD, owner, event)
        }
        link(status, TasklaneBundle.message("firststeps.fromHere"), shortcutOf(FROM_HERE)) { event ->
            fromHere(project, owner, event)
        }
        link(status, TasklaneBundle.message("firststeps.importTodos"), null) { event ->
            run(IMPORT_TODOS, owner, event)
        }
        if (hasMcpServer()) {
            link(status, TasklaneBundle.message("firststeps.agent"), null) { openMcpSettings(project) }
        }
        status.withUnscaledGapAfter(DEFAULT_GAP)
    }

    private fun link(status: StatusText, text: String, shortcut: String?, run: (ActionEvent) -> Unit) {
        status.appendLine(text, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { run(it) }
        if (!shortcut.isNullOrBlank()) status.appendText("  $shortcut", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    /** Llamada directa y no referencia a función: ver el KDoc de `showSearchHint`. */
    private fun shortcutOf(actionId: String): String? =
        KeymapUtil.getFirstKeyboardShortcutText(actionId).takeIf { it.isNotBlank() }

    /**
     * Por el `ActionManager` y no llamando a la clase: así manda el `update` de la acción —si
     * el repositorio está en solo lectura, no hace nada— y lo que haga lo hace igual que desde
     * el menú. [context] es de dónde saca la acción su `DataContext`.
     */
    private fun run(actionId: String, context: Component, event: ActionEvent) {
        val manager = ActionManager.getInstance()
        val action = manager.getAction(actionId) ?: return
        manager.tryToExecute(action, event.source as? InputEvent, context, ActionPlaces.TOOLWINDOW_CONTENT, true)
    }

    /**
     * *from Here* necesita un sitio: el cursor del editor que se está viendo. Sin editor no
     * hay a qué anclar, y se dice cómo se hace en vez de abrir un diálogo sin ancla, que sería
     * lo mismo que *New Task*.
     */
    private fun fromHere(project: Project, owner: JComponent, event: ActionEvent) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null && !editor.isDisposed && editor.component.isShowing) {
            run(FROM_HERE, editor.contentComponent, event)
            return
        }
        val shortcut = shortcutOf(FROM_HERE)
        val text = if (shortcut == null) {
            TasklaneBundle.message("firststeps.fromHere.hint")
        } else {
            TasklaneBundle.message("firststeps.fromHere.hint.shortcut", shortcut)
        }
        val where = (event.source as? MouseEvent)?.let(::RelativePoint) ?: RelativePoint.getCenterOf(owner)
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(text, MessageType.INFO, null)
            .setFadeoutTime(HINT_FADEOUT_MS)
            .createBalloon()
            .show(where, Balloon.Position.above)
    }

    /** Sin el plugin *MCP Server* no hay página de ajustes a la que llevar, y el enlace no sale. */
    private fun hasMcpServer(): Boolean = Configurable.APPLICATION_CONFIGURABLE.extensionList.any { it.id == MCP_SETTINGS }

    private fun openMcpSettings(project: Project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            { (it as? SearchableConfigurable)?.id == MCP_SETTINGS },
            null,
        )
    }

    private const val HINT_FADEOUT_MS = 8_000L
}
