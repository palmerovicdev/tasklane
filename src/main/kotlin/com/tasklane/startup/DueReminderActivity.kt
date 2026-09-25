package com.tasklane.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.tasklane.service.DueReminders

/**
 * Arranca el aviso de vencimientos al abrir el proyecto (2.9.0). Por lo mismo que
 * [AnchorMarkerActivity]: nadie pide un aviso, aparece, y es en los días en que no se
 * abre la tool window cuando más falta hace.
 */
internal class DueReminderActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        DueReminders.getInstance(project).start()
    }
}
