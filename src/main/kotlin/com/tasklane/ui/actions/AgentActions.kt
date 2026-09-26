package com.tasklane.ui.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.tasklane.TasklaneBundle
import com.tasklane.agent.AgentHandOff

/**
 * *Hand Off to Agent* (P26): abrir una pestaña de la terminal con el agente trabajando en la
 * tarea, y pasarla a *en curso*.
 *
 * Una tarea cada vez, a propósito: varias abrirían varios agentes a la vez sobre el mismo
 * repositorio, pisándose los ficheros. Sólo se ve con terminal y con una orden configurada;
 * si no, queda [CopyAgentPromptAction].
 *
 * Pasar a *en curso* va por [com.tasklane.ui.toolwindow.TasklanePanel.moveSelectedTo], que es
 * *Move To ▸*: un paso de `⌘Z`, y en el tablero la tarjeta se va seleccionada a su columna.
 */
internal class HandOffToAgentAction : PanelAction() {

    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isVisible = panel != null && AgentHandOff.canOpen()
        e.presentation.isEnabled = panel?.selectedTasks()?.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = panelOf(e) ?: return
        val task = panel.selectedTasks().singleOrNull() ?: return
        if (!AgentHandOff.open(project, task)) return
        if (!panel.isSelectionEditable()) return
        AgentHandOff.workingState(project, task)?.let(panel::moveSelectedTo)
    }
}

/**
 * La misma petición, al portapapeles: para el chat de un agente que no vive en la terminal, o
 * para quien no quiere que se abra nada. No mueve la tarea: copiar no es encargar.
 */
internal class CopyAgentPromptAction : PanelAction() {

    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isVisible = panel != null
        e.presentation.isEnabled = panel?.selectedTasks()?.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val task = panelOf(e)?.selectedTasks()?.singleOrNull() ?: return
        AgentHandOff.copyPrompt(project, task)
    }
}

/**
 * El párrafo para `CLAUDE.md` o `AGENTS.md` que le dice al agente «lo pendiente, a Tasklane, no
 * a `// TODO`». No es de una tarea: está en *Search Everywhere* y en los ajustes del agente.
 */
internal class CopyAgentInstructionsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AgentHandOff.copyInstructions(project) { copied ->
            if (copied) {
                AgentHandOff.notify(project, TasklaneBundle.message("agent.instructions.copied"), NotificationType.INFORMATION)
            } else {
                AgentHandOff.notify(project, TasklaneBundle.message("agent.copy.failed"), NotificationType.ERROR)
            }
        }
    }
}
