package com.tasklane.ui.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * [DumbAware] porque las tareas no dependen de los índices del IDE: la ventana
 * debe seguir siendo usable mientras el proyecto indexa.
 *
 * El contenido lo gobierna [StateTabs]: una pestaña por estado, reconstruidas
 * cuando cambia la configuración.
 */
internal class TasklaneToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        Disposer.register(toolWindow.disposable, StateTabs(project, toolWindow))
    }
}
