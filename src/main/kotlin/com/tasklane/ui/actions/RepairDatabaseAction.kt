package com.tasklane.ui.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.tasklane.TasklaneBundle
import com.tasklane.service.TaskService

/**
 * *Maintenance ▸ Repair Database…*: comprobar la base **ahora**, y si algo falla dejarla
 * marcada para recuperarla y ofrecer reabrir el proyecto.
 *
 * Existe porque la base dañada no siempre se ve sola: la comprobación de integridad sólo
 * corre tras un cierre sucio, y hay daños que sólo ven las conexiones nuevas —una
 * exportación—. Con esto, «no me deja exportar» tiene una salida que no es esperar.
 *
 * **No repara en caliente**, a propósito y por lo mismo que [TaskService.markDamaged]: la
 * recuperación se hace en la apertura siguiente, que es el camino probado, y **no borra
 * nada**: el fichero dañado se guarda en cuarentena.
 */
internal class RepairDatabaseAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = TaskService.getInstance(project)
        var problems: List<String> = emptyList()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, TasklaneBundle.message("repair.progress"), false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    problems = service.checkHealthNow()
                }

                override fun onSuccess() {
                    // Si hay problemas, `markDamaged` ya avisó con su botón de reabrir y reparar.
                    if (problems.isNotEmpty()) return
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(TaskService.NOTIFICATION_GROUP)
                        .createNotification(TasklaneBundle.message("repair.healthy"), NotificationType.INFORMATION)
                        .notify(project)
                }
            },
        )
    }
}
