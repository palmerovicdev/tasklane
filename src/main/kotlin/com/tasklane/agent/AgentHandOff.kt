package com.tasklane.agent

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.AgentSettings
import com.tasklane.domain.agent.AgentRequest
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.service.ExportService
import com.tasklane.service.TaskService

/**
 * Encargar una tarea a un agente (P26): lo que hacen *Hand Off to Agent*, *Copy Agent Prompt*
 * y *Copy Agent Instructions*, sin la parte de Swing.
 *
 * Qué se escribe y a qué estado pasa lo decide [AgentRequest], que es dominio puro; aquí queda
 * juntarlo con los ajustes, la terminal y el portapapeles.
 */
internal object AgentHandOff {

    /** Si *Hand Off to Agent* puede abrir algo: con terminal y con una orden. */
    fun canOpen(): Boolean = AgentTerminal.find() != null && AgentSettings.getInstance().command.isNotBlank()

    /**
     * Abre una pestaña de la terminal en el repositorio de [task], con el agente trabajando en
     * ella. Devuelve si la abrió: sólo entonces pasa la tarea a *en curso*.
     *
     * Se abre en **la raíz del repositorio de la tarea** y no en la del proyecto: con varios
     * repositorios, el agente tiene que empezar donde está el código del que habla la tarea.
     */
    fun open(project: Project, task: Task): Boolean {
        val terminal = AgentTerminal.find() ?: return false
        val settings = AgentSettings.getInstance()
        val template = settings.command
        if (template.isBlank()) return false
        val prompt = AgentRequest.prompt(settings.prompt, task)
        return try {
            terminal.open(project, directoryOf(project, task), AgentRequest.tabName(template, task)) { shell ->
                AgentRequest.command(template, prompt, task, shell).orEmpty()
            }
            true
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            thisLogger().warn("Tasklane: no se pudo abrir la terminal para el agente", e)
            notify(project, TasklaneBundle.message("agent.open.failed", e.message.orEmpty()), NotificationType.ERROR)
            false
        }
    }

    /** A qué estado pasa [task] al encargarla, o `null` si se queda. Ver [AgentRequest.workingState]. */
    fun workingState(project: Project, task: Task): StateId? {
        if (!AgentSettings.getInstance().moveToWorking) return null
        return AgentRequest.workingState(TaskService.getInstance(project).snapshot.value.config, task.stateId)
    }

    /** La petición al portapapeles, para pegarla en el chat de un agente sin terminal. */
    fun copyPrompt(project: Project, task: Task) {
        val prompt = AgentRequest.prompt(AgentSettings.getInstance().prompt, task)
        ExportService.getInstance(project).copyText(prompt) { copied ->
            if (copied) {
                notify(project, TasklaneBundle.message("agent.prompt.copied"), NotificationType.INFORMATION)
            } else {
                notify(project, TasklaneBundle.message("agent.copy.failed"), NotificationType.ERROR)
            }
        }
    }

    /** El párrafo para `CLAUDE.md` o `AGENTS.md`, al portapapeles; [done] dice si llegó. */
    fun copyInstructions(project: Project, done: (Boolean) -> Unit) {
        ExportService.getInstance(project).copyText(AgentRequest.INSTRUCTIONS, done)
    }

    private fun directoryOf(project: Project, task: Task): String? =
        TaskService.getInstance(project).snapshot.value.repositories
            .firstOrNull { it.key == task.repo && it.available }
            ?.rootPath
            ?: project.basePath

    fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(TaskService.NOTIFICATION_GROUP)
            .createNotification(content, type)
            .notify(project)
    }
}
