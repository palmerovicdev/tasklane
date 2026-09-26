package com.tasklane.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.rows
import com.tasklane.TasklaneBundle
import com.tasklane.agent.AgentHandOff
import com.tasklane.agent.AgentTerminal
import com.tasklane.data.config.AgentSettings

/**
 * El grupo *AI agent* de los ajustes (P26): la orden, la petición y el botón que copia las
 * instrucciones para `CLAUDE.md` o `AGENTS.md`.
 *
 * Va aparte de [TasklaneConfigurable] porque no comparte nada con el resto de la página: es
 * de la aplicación y no del proyecto —ver [AgentSettings]— y el `bind` del DSL se ocupa solo
 * de Apply, Cancel y de saber si hay cambios.
 */
internal fun Panel.agentGroup(project: Project) {
    val settings = AgentSettings.getInstance()
    group(TasklaneBundle.message("settings.agent.title")) {
        row(TasklaneBundle.message("settings.agent.command")) {
            textField()
                .bindText({ settings.command }, { settings.command = it })
                .columns(COLUMNS_LARGE)
                .align(AlignX.FILL)
                .comment(TasklaneBundle.message("settings.agent.command.comment"))
        }
        // Sin el plugin Terminal la orden no se usa: que lo diga aquí, y no que la acción
        // desaparezca del menú sin explicación.
        if (AgentTerminal.find() == null) {
            row { comment(TasklaneBundle.message("settings.agent.noTerminal")) }
        }
        row(TasklaneBundle.message("settings.agent.prompt")) {
            textArea()
                .rows(PROMPT_ROWS)
                .applyToComponent {
                    lineWrap = true
                    wrapStyleWord = true
                }
                .bindText({ settings.prompt }, { settings.prompt = it })
                .align(AlignX.FILL)
                .comment(TasklaneBundle.message("settings.agent.prompt.comment"))
        }
        row {
            val status = JBLabel()
            button(TasklaneBundle.message("settings.agent.instructions")) {
                status.icon = null
                status.text = ""
                AgentHandOff.copyInstructions(project) { copied ->
                    status.icon = if (copied) AllIcons.General.InspectionsOK else AllIcons.General.Error
                    status.text = TasklaneBundle.message(if (copied) "settings.agent.instructions.copied" else "agent.copy.failed")
                }
            }
            cell(status)
        }
        row { comment(TasklaneBundle.message("settings.agent.instructions.comment")) }
        row { comment(TasklaneBundle.message("settings.agent.comment")) }
    }
}

private const val PROMPT_ROWS = 3
