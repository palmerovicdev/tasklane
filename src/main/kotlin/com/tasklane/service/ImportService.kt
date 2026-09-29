package com.tasklane.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.tasklane.TasklaneBundle
import com.tasklane.data.sqlite.ImportMerge
import com.tasklane.data.sqlite.TaskArchive
import com.tasklane.data.sqlite.TasksXmlReader
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import java.nio.file.Files
import java.nio.file.Path
import java.text.DateFormat
import java.util.Date

/**
 * *Import ▸ Archive…*: meter en el repositorio activo un `.zip` de [TaskArchive] que se
 * elige del disco.
 *
 * Tres pasos, y el usuario decide entre el segundo y el tercero:
 *
 * 1. **Leer y contar**, sin tocar nada: se abre el zip, se comprueba, y cada tarea se
 *    clasifica contra lo que ya hay —[ImportMerge]—. Es una pasada de lectura.
 * 2. **Un resumen** con las cuentas: cuántas nuevas, cuántas se actualizan, cuántas ya
 *    estaban. Si no hay nada que escribir se dice y ahí se acaba.
 * 3. **Importar**: primero una copia de la base (`tasklane.db.before-import`), después las
 *    tareas a tandas y por último las imágenes que aún no estén. Cancelable: lo escrito
 *    hasta ahí es válido, porque cada tanda es una transacción y la regla por id hace que
 *    repetir la importación complete lo que falte sin duplicar nada.
 *
 * Nada de esto sustituye ni vacía el repositorio: importar **suma**.
 */
@Service(Service.Level.PROJECT)
class ImportService(private val project: Project) {

    /** Lo que se leyó del archivo en el paso 1 y el paso 3 necesita. */
    private class Prepared(val archive: Path, val manifest: TaskArchive.Manifest, val counts: ImportMerge.Counts)

    /** Desde el EDT: pregunta qué archivo y arranca el paso 1. */
    fun importArchive(ref: RepositoryRef) {
        val service = TaskService.getInstance(project)
        if (service.migrationPending(ref.key) || service.isReadOnly(ref.key)) {
            notify(TasklaneBundle.message("import.unavailable"), NotificationType.WARNING)
            return
        }
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("zip")
            .withTitle(TasklaneBundle.message("import.choose.title"))
            .withDescription(TasklaneBundle.message("import.choose.description"))
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        inspect(ref, Path.of(chosen.path))
    }

    // ------------------------------------------------------------------ 1. leer

    private fun inspect(ref: RepositoryRef, archive: Path) {
        val service = TaskService.getInstance(project)
        var prepared: Prepared? = null
        var failure: Throwable? = null

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("import.reading", archive.fileName), true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        withTasks(archive) { reader, tasks ->
                            var counts = ImportMerge.Counts()
                            val total = reader.manifest.tasks.coerceAtLeast(1)
                            var seen = 0
                            val result = TasksXmlReader.read(tasks, ref.key, cancelled = { indicator.isCanceled }) { chunk ->
                                service.planImport(ref.key, chunk)?.let { counts += it.counts }
                                seen += chunk.size
                                indicator.fraction = seen.toDouble() / total
                            }
                            if (result is TasksXmlReader.Result.Cancelled) return@withTasks
                            check(result is TasksXmlReader.Result.Done) { describe(result) }
                            prepared = Prepared(archive, reader.manifest, counts)
                        }
                    } catch (e: Throwable) {
                        if (e is com.intellij.openapi.diagnostic.ControlFlowException) throw e
                        failure = e
                    }
                }

                override fun onFinished() {
                    failure?.let { return fail(it) }
                    prepared?.let { confirm(ref, it) }
                }
            },
        )
    }

    // ------------------------------------------------------------- 2. resumen

    private fun confirm(ref: RepositoryRef, prepared: Prepared) {
        val counts = prepared.counts
        if (counts.total == 0) {
            notify(TasklaneBundle.message("import.empty"), NotificationType.INFORMATION)
            return
        }
        val exported = prepared.manifest.exportedAt.toEpochMilli().takeIf { it > 0 }
            ?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "?"
        val summary = TasklaneBundle.message(
            "import.confirm.summary",
            prepared.manifest.repo.ifEmpty { "?" },
            exported,
            counts.total,
            counts.added,
            counts.updated,
            counts.same + counts.kept,
            ref.displayName,
        )
        if (counts.writes == 0) {
            notify(TasklaneBundle.message("import.nothing", counts.total), NotificationType.INFORMATION)
            return
        }
        val go = MessageDialogBuilder
            .yesNo(TasklaneBundle.message("import.confirm.title"), summary)
            .yesText(TasklaneBundle.message("import.confirm.yes"))
            .ask(project)
        if (go) run(ref, prepared)
    }

    // ------------------------------------------------------------- 3. importar

    private fun run(ref: RepositoryRef, prepared: Prepared) {
        val service = TaskService.getInstance(project)
        val attachments = AttachmentService.getInstance(project)
        var counts = ImportMerge.Counts()
        var images = 0
        var failedImages = 0
        var backup: Path? = null
        var failure: Throwable? = null

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("import.progress", ref.displayName), true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = false
                        backup = service.backupBeforeImport()
                        withTasks(prepared.archive) { reader, tasks ->
                            val total = reader.manifest.tasks.coerceAtLeast(1)
                            var seen = 0
                            val result = TasksXmlReader.read(tasks, ref.key, cancelled = { indicator.isCanceled }) { chunk ->
                                service.planImport(ref.key, chunk)?.let {
                                    service.applyImport(it)
                                    counts += it.counts
                                }
                                seen += chunk.size
                                indicator.fraction = seen.toDouble() / total * 0.8
                            }
                            if (result is TasksXmlReader.Result.Cancelled) return@withTasks
                            check(result is TasksXmlReader.Result.Done) { describe(result) }

                            indicator.text = TasklaneBundle.message("import.images")
                            val temp = Files.createTempDirectory("tasklane-import-img")
                            try {
                                val ids = reader.attachmentIds()
                                ids.forEachIndexed { i, id ->
                                    indicator.checkCanceled()
                                    indicator.fraction = 0.8 + 0.2 * (i + 1) / ids.size
                                    if (attachments.file(ref.key, id) != null) return@forEachIndexed
                                    val file = temp.resolve(id.value)
                                    reader.extractAttachment(id, file)
                                    // Se guarda por sus bytes: el id sale del contenido, así que tiene que
                                    // salir el mismo. Si no, la tarea que la nombra seguiría rota.
                                    if (attachments.attachFile(ref.key, file) == id) images++ else failedImages++
                                    Files.deleteIfExists(file)
                                }
                            } finally {
                                temp.toFile().deleteRecursively()
                            }
                        }
                    } catch (e: Throwable) {
                        if (e is com.intellij.openapi.diagnostic.ControlFlowException) throw e
                        failure = e
                    }
                }

                override fun onFinished() {
                    failure?.let { return fail(it) }
                    val note = if (failedImages > 0) " " + TasklaneBundle.message("import.done.imagesFailed", failedImages) else ""
                    notify(
                        TasklaneBundle.message("import.done", counts.added, counts.updated, images) + note,
                        NotificationType.INFORMATION,
                        backup,
                    )
                }
            },
        )
    }

    // ---------------------------------------------------------------- común

    /** Abre el archivo, saca `tasks.xml` a un temporal y lo borra al terminar. */
    private fun withTasks(archive: Path, block: (TaskArchive.Reader, Path) -> Unit) {
        val temp = Files.createTempFile("tasklane-import", ".xml")
        try {
            TaskArchive.Reader.open(archive).use { reader ->
                reader.extractTasks(temp)
                block(reader, temp)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun describe(result: TasksXmlReader.Result): String = when (result) {
        is TasksXmlReader.Result.FutureVersion -> TasklaneBundle.message("import.failed.future", result.version)
        is TasksXmlReader.Result.Broken -> TasklaneBundle.message("import.failed.broken", result.tasks, result.cause.message.orEmpty())
        else -> result.toString()
    }

    private fun fail(error: Throwable) {
        thisLogger().warn("Tasklane: no se pudo importar el archivo", error)
        val why = when (error) {
            is TaskArchive.Invalid -> TasklaneBundle.message("import.invalid.${error.reason.name.lowercase()}")
            else -> error.message.orEmpty()
        }
        notify(TasklaneBundle.message("import.failed", why), NotificationType.ERROR)
    }

    private fun notify(content: String, type: NotificationType, reveal: Path? = null) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(TaskService.NOTIFICATION_GROUP)
            .createNotification(content, type)
        if (reveal != null && Files.exists(reveal)) {
            notification.addAction(
                NotificationAction.createSimple(TasklaneBundle.message("import.done.revealBackup")) { RevealFileAction.openFile(reveal) },
            )
        }
        notification.notify(project)
    }

    companion object {
        fun getInstance(project: Project): ImportService = project.service()
    }
}
