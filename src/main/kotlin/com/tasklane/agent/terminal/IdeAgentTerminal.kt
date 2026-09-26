package com.tasklane.agent.terminal

import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.tasklane.agent.AgentTerminal
import com.tasklane.domain.agent.Shell
import kotlinx.coroutines.launch

/**
 * La terminal del IDE, con la API de pestañas de la terminal nueva (P26).
 *
 * Sólo se carga desde `tasklane-terminal.xml`, así que aquí sí se puede importar el plugin
 * *Terminal*. Es la API que la plataforma recomienda desde la 2026.1 —la de
 * `TerminalToolWindowManager` está deprecada en la 261 y en la 262— y es experimental, no
 * interna: el Plugin Verifier la da por buena.
 *
 * La orden no se escribe en cuanto se crea la pestaña sino **cuando se sabe con qué shell
 * arrancó**: `startupOptionsDeferred` lo dice, y de eso dependen las comillas. Lo escrito antes
 * de que la shell esté lista lo guarda la propia terminal y lo manda después, así que no hay
 * que esperar al prompt.
 */
internal class IdeAgentTerminal : AgentTerminal {

    override fun open(project: Project, directory: String?, tabName: String, command: (Shell) -> String) {
        val view = TerminalToolWindowTabsManager.getInstance(project)
            .createTabBuilder()
            .workingDirectory(directory)
            .tabName(tabName)
            .requestFocus(true)
            .createTab()
            .view
        // El ámbito de la vista: si se cierra la pestaña antes de arrancar, no se escribe nada.
        view.coroutineScope.launch {
            val shell = Shell.of(view.startupOptionsDeferred.await().shellCommand.firstOrNull().orEmpty())
            view.createSendTextBuilder().shouldExecute().send(command(shell))
        }
    }
}
