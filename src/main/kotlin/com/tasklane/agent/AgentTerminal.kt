package com.tasklane.agent

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.tasklane.domain.agent.Shell

/**
 * Quien abre una pestaña de la terminal del IDE y escribe en ella la orden del agente (P26).
 *
 * Es un punto de extensión por lo mismo que `RepositoryProvider`: la terminal es un plugin, y
 * se puede desactivar. La implementación vive detrás de `tasklane-terminal.xml`, que sólo se
 * carga con el plugin *Terminal* activo; sin él no hay extensión, *Hand Off to Agent* no sale
 * y queda *Copy Agent Prompt*.
 */
interface AgentTerminal {

    /**
     * Abre la pestaña [tabName] en [directory] y escribe y ejecuta la orden que devuelva
     * [command] para la shell con la que arranque. **En el EDT.**
     *
     * La orden se pide cuando ya se sabe la shell, y no antes, porque las comillas dependen de
     * ella: lo que en zsh es una cadena literal en PowerShell no lo es.
     */
    fun open(project: Project, directory: String?, tabName: String, command: (Shell) -> String)

    companion object {
        val EP: ExtensionPointName<AgentTerminal> = ExtensionPointName.create("com.tasklane.agentTerminal")

        /** La terminal, si el plugin está activo. */
        fun find(): AgentTerminal? = EP.extensionList.firstOrNull()
    }
}
