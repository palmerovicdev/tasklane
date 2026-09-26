package com.tasklane.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.service.TaskService

/**
 * «Enséñame esta tarea» desde fuera de la ventana.
 *
 * Existe por un detalle que sólo aparece la primera vez: la tool window se construye
 * **cuando se abre**, así que en una sesión en la que nadie la ha abierto todavía no hay
 * ninguna pestaña escuchando, y pedir que se enseñe una tarea no lo atendería nadie. El
 * clic sobre una marca del editor es justo el caso: es la forma de llegar a la lista sin
 * haber pasado por la lista.
 *
 * Así que primero se abre la ventana —lo que crea las pestañas— y la petición va en el
 * `Runnable` que la plataforma ejecuta después. Sin robar el foco: quien pulsa una marca
 * está leyendo código, y llevárselo al árbol le cortaría lo que estaba haciendo. Ver
 * también el `replay` de `TaskService.reveal`, que cubre el otro lado de la carrera.
 */
internal object TaskReveal {

    fun show(project: Project, tasks: List<Task>) {
        if (tasks.isEmpty()) return
        val reveal = Runnable { TaskService.getInstance(project).revealTasks(tasks) }
        val window = ToolWindowManager.getInstance(project).getToolWindow(TasklanePanel.TOOL_WINDOW_ID)
        if (window == null) reveal.run() else window.activate(reveal, false)
    }

    /**
     * Abre la ventana en la pestaña que tuviera. Aquí sí con el foco: lo pide un clic en
     * la barra de estado (2.14.0), que es ir a la lista a propósito.
     */
    fun open(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(TasklanePanel.TOOL_WINDOW_ID)?.activate(null)
    }

    /**
     * Abre la ventana en la pestaña de [state]. Por el mismo camino que [show]: la ventana
     * que no existía se crea al abrirla, y la pestaña se pide en el `Runnable`, cuando ya
     * hay a quién pedírsela.
     */
    fun showState(project: Project, state: StateId) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TasklanePanel.TOOL_WINDOW_ID) ?: return
        window.activate({
            window.contentManager.contents
                .firstNotNullOfOrNull { it.getUserData(TasklaneWindow.KEY) }
                ?.select(state)
        })
    }
}
