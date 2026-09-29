package com.tasklane.ui.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.tasklane.TasklaneBundle
import com.tasklane.agent.AgentHandOff
import com.tasklane.domain.agent.Agent

/**
 * *Hand Off to Agent* (P26): abrir una pestaña de la terminal con el agente trabajando en la
 * tarea.
 *
 * Una tarea cada vez, a propósito: varias abrirían varios agentes a la vez sobre el mismo
 * repositorio, pisándose los ficheros. Sólo se ve con terminal y con algún agente configurado;
 * si no, queda [CopyAgentPromptAction].
 *
 * No la mueve de estado (2.25.1): se queda donde estaba y el agente la cierra al acabar, con
 * `tasklane_complete_task`. Ver [com.tasklane.domain.agent.AgentRequest].
 *
 * Con varios agentes (2.27.0) ésta es la del Keymap y la de *Find Action*, y **pregunta a cuál**
 * con la misma lista que el submenú. En el menú contextual no sale ésta sino
 * [HandOffToAgentGroup], que con uno solo la pone a ella y con varios es el submenú.
 */
internal class HandOffToAgentAction : PanelAction() {

    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isVisible = panel != null && AgentHandOff.agents().isNotEmpty()
        e.presentation.isEnabled = panel?.selectedTasks()?.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val task = panelOf(e)?.selectedTasks()?.singleOrNull() ?: return
        val agents = AgentHandOff.agents()
        if (agents.size <= 1) {
            agents.firstOrNull()?.let { AgentHandOff.open(project, task, it) }
            return
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                TasklaneBundle.message("agent.choose"),
                DefaultActionGroup(agents.map(::HandOffToOneAgentAction)),
                e.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
            .showInBestPositionFor(e.dataContext)
    }
}

/**
 * *Hand Off to Agent* en el menú contextual (2.27.0): con un agente, la entrada de siempre
 * —[HandOffToAgentAction], con su atajo—; con dos o más, un submenú con uno por agente, en el
 * orden de los ajustes.
 *
 * Es un grupo que decide en cada apertura si es submenú o no: los agentes son ajustes de la
 * aplicación y pueden haber cambiado desde la vez anterior. No sale en *Find Action*, donde ya
 * está [HandOffToAgentAction] con el mismo nombre.
 */
internal class HandOffToAgentGroup : ActionGroup(), DumbAware {

    init {
        isSearchable = false
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val panel = TasklaneDataKeys.PANEL.getData(e.dataContext)
        val agents = AgentHandOff.agents()
        e.presentation.isVisible = panel != null && agents.isNotEmpty()
        e.presentation.isPopupGroup = agents.size > 1
        e.presentation.isEnabled = panel?.selectedTasks()?.size == 1
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val agents = AgentHandOff.agents()
        if (agents.size > 1) return agents.map(::HandOffToOneAgentAction).toTypedArray()
        return listOfNotNull(ActionManager.getInstance().getAction(HAND_OFF_ACTION)).toTypedArray()
    }

    private companion object {
        const val HAND_OFF_ACTION = "Tasklane.HandOffToAgent"
    }
}

/** Una entrada del submenú: el nombre del agente, y su orden como descripción. */
private class HandOffToOneAgentAction(private val agent: Agent) : PanelAction() {

    init {
        // Sin mnemónico: un nombre como «my_agent» se enseña tal cual.
        templatePresentation.setText(agent.label, false)
        templatePresentation.description = agent.command
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panelOf(e)?.selectedTasks()?.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val task = panelOf(e)?.selectedTasks()?.singleOrNull() ?: return
        AgentHandOff.open(project, task, agent)
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
