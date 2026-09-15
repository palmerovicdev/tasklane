package com.tasklane.startup

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.tasklane.TasklaneBundle
import com.tasklane.diagnostics.TasklaneDiagnostics
import com.tasklane.service.AttachmentService
import com.tasklane.service.TaskService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.text.DateFormat
import java.util.Date
import kotlin.time.Duration.Companion.seconds

/**
 * El mantenimiento de fondo, una vez por apertura del proyecto: primero **la base** y
 * después **los adjuntos**.
 *
 * Hasta la 2.1 era `AttachmentMaintenanceActivity`, y sólo miraba las imágenes. La Fase 5
 * le pone delante las dos tareas de la base, **en el orden en que tienen que ocurrir**:
 *
 * 1. **Comprobar la integridad**, sólo si la sesión anterior no cerró la base —ver
 *    `TaskDb.dirty`—. Va primero porque lo que decide depende de ello: una base con
 *    daños no se copia.
 * 2. **Copiar la base** a `tasklane.db.backup`, como mucho una vez al día y sólo si
 *    cambió. Es el sustituto del `.bak` que se escribía en cada guardado.
 * 3. **Trasladar** lo que quede en el directorio plano de adjuntos al árbol fragmentado
 *    (§4.1). No depende de nada: mover ficheros no necesita saber qué está referenciado.
 * 4. **Recolectar** lo que ya no nombra ninguna tarea (§4.2), pero **sólo en los
 *    repositorios cuyas referencias están completas** — ver `TaskService.referencesComplete`.
 * 5. **Reconciliar** disco y tabla (§4.3), como mucho una vez por semana. Después de
 *    recolectar para no adoptar lo que se acaba de tirar.
 * 6. **Mirar la cuota** (§4.5), al final, con las cifras ya puestas al día.
 *
 * **Todo en segundo plano, con progreso y cancelable** entre paso y paso. Ninguna toca la
 * lista, así que la ventana responde mientras tanto. Las dos de la base son sentencias
 * únicas que la plataforma no deja interrumpir: cancelar espera a que acabe la que esté
 * en marcha.
 */
internal class MaintenanceActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val tasks = TaskService.getInstance(project)

        // Esperar a que haya repositorios detectados —no a que estén cargados, que
        // desde la Fase 3 ya no es una cosa que ocurra—. Sin repositorios no hay nada
        // que mantener, y lo que decide si se puede recolectar cada uno es su propia
        // comprobación de referencias, no esta espera.
        val ready = withTimeoutOrNull(WAIT) {
            tasks.snapshot.first { it.repositories.isNotEmpty() }
        }
        if (ready == null) return

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("maintenance.progress"), true) {
                override fun run(indicator: ProgressIndicator) {
                    val service = AttachmentService.getInstance(project)
                    val cancelled = { indicator.isCanceled }
                    indicator.isIndeterminate = true

                    // Cada tarea por separado y sin que una se lleve a las demás: esto
                    // es mantenimiento en segundo plano sobre el disco de alguien, y un
                    // volumen de red que desaparece a media pasada no puede convertirse
                    // en un error del IDE ni impedir que se avise de la cuota.
                    indicator.text = TasklaneBundle.message("maintenance.integrity")
                    step("comprobar la base") { tasks.checkIntegrityIfNeeded() }?.let { report(project, tasks, it) }

                    if (!indicator.isCanceled) {
                        indicator.text = TasklaneBundle.message("maintenance.backup")
                        step("copiar la base") { tasks.backupIfDue() }?.let { backup ->
                            if (backup.skipped == null) {
                                thisLogger().info(
                                    "Tasklane: copia de la base en ${backup.file} " +
                                        "(${TasklaneDiagnostics.humanBytes(backup.bytes)})",
                                )
                            }
                        }
                    }

                    indicator.text = TasklaneBundle.message("attachments.relocating")
                    val moved = step("trasladar") { service.relocate(cancelled) } ?: 0

                    indicator.text = TasklaneBundle.message("attachments.collecting")
                    val deleted = step("recoger") { service.collectGarbage(cancelled) } ?: 0

                    indicator.text = TasklaneBundle.message("attachments.reconciling")
                    val fsck = step("reconciliar") { service.reconcile(cancelled = cancelled) }

                    if (moved > 0 || deleted > 0 || (fsck != null && (fsck.adopted > 0 || fsck.missing > 0))) {
                        thisLogger().info(
                            "Tasklane: adjuntos · $moved trasladados, $deleted recogidos, " +
                                "${fsck?.adopted ?: 0} adoptados, ${fsck?.missing ?: 0} ausentes, " +
                                "${fsck?.temporaries ?: 0} temporales",
                        )
                    }

                    if (!indicator.isCanceled) step("cuota") { service.checkQuota() }
                }

                private fun <T> step(name: String, block: () -> T): T? = runCatching(block)
                    .onFailure { thisLogger().warn("Tasklane: fallo al $name", it) }
                    .getOrNull()
            },
        )
    }

    /**
     * Lo que dijo la comprobación. Sana, al registro y nada más: el usuario no pidió
     * nada y no hay nada que contarle. Con daños, **se avisa**, con los primeros problemas
     * y con dónde está la última copia buena — que es lo único útil que se puede hacer
     * con esa noticia hasta que la recuperación de la Fase 6 lo haga sola.
     */
    private fun report(project: Project, tasks: TaskService, result: TaskService.Integrity) {
        if (result.problems.isEmpty()) {
            thisLogger().info("Tasklane: la base se comprobó tras un cierre sucio y está sana")
            return
        }
        thisLogger().warn("Tasklane: la base tiene daños: ${result.problems.joinToString(" | ")}")

        val backup = tasks.backupFile()?.takeIf { Files.exists(it) }
        val where = backup?.let {
            val at = DateFormat.getDateTimeInstance().format(Date(Files.getLastModifiedTime(it).toMillis()))
            TasklaneBundle.message("notification.integrity.backup", it, at)
        } ?: TasklaneBundle.message("notification.integrity.noBackup")

        NotificationGroupManager.getInstance()
            .getNotificationGroup(TaskService.NOTIFICATION_GROUP)
            .createNotification(
                TasklaneBundle.message("notification.integrity.title"),
                TasklaneBundle.message("notification.integrity.content", result.problems.first(), where),
                NotificationType.ERROR,
            )
            .apply {
                if (backup != null) {
                    addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) {
                        RevealFileAction.openFile(backup)
                    })
                }
            }
            .notify(project)
    }

    private companion object {
        val WAIT = 60.seconds
    }
}
