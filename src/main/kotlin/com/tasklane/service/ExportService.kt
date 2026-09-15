package com.tasklane.service

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.data.sqlite.TasksXmlWriter
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.export.ExportFormat
import com.tasklane.domain.export.TaskExporter
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.ui.toolwindow.TasklanePanel
import java.awt.datatransfer.StringSelection
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * Sacar tareas del plugin: al portapapeles, a un fichero, al formato de intercambio, y
 * «Exportar y quitar».
 *
 * El texto lo produce [TaskExporter], que es dominio puro y está cubierto por tests; aquí
 * queda hablar con el portapapeles, con los diálogos y con el disco.
 *
 * ## Lo que la Fase 5 cambió aquí
 *
 * Exportar es la única operación del plugin que es O(n) **por definición** —exportar un
 * millón de tareas es leer un millón de tareas—, así que no se puede hacer más barata:
 * se puede hacer **cancelable, medida, en segundo plano y con memoria constante**. Es la
 * Fase 5 del `docs/plan-escala.md`:
 *
 * 1. **En *streaming*.** Hasta la 2.1 las tareas se leían enteras a una lista y el texto
 *    se construía entero en un `String`. Ahora se leen a tandas y se escriben según
 *    llegan: lo retenido es una tanda.
 * 2. **El portapapeles tiene tope** ([CLIPBOARD_LIMIT]). Por encima se ofrece un fichero:
 *    un portapapeles de 3 GB no es una función, es un cuelgue — del IDE y de donde se
 *    pegue.
 * 3. **Es una foto.** Lo que sale es la pestaña **en el momento de pulsar**, aunque se
 *    siga editando mientras se escribe. Ver `TaskPager.snapshot`.
 */
@Service(Service.Level.PROJECT)
class ExportService(private val project: Project) {

    private val workspace = TasklaneWorkspaceService.getInstance(project)

    /**
     * Formato de salida. Es del espacio de trabajo y no de la configuración del
     * proyecto: es una preferencia de quien copia, no una decisión del equipo.
     */
    var format: ExportFormat
        get() = workspace.exportFormat
        set(value) {
            workspace.exportFormat = value
        }

    // ------------------------------------------------------------ una pestaña

    /**
     * Exporta lo que pide la pestaña. **Desde el EDT**, y lo caro va en segundo plano.
     *
     * Decide el destino antes de leer nada, con las cuentas que la pestaña ya tiene:
     * hasta [CLIPBOARD_LIMIT] tareas, al portapapeles; por encima, se pregunta si se
     * quiere un fichero. Preguntar después de leerlo todo sería hacer esperar a alguien
     * para decirle que lo que ha esperado no se puede usar.
     */
    internal fun copy(request: TasklanePanel.ExportRequest) {
        val total = request.size
        if (total == 0) {
            notify(TasklaneBundle.message("export.empty"), NotificationType.INFORMATION)
            return
        }
        if (total <= CLIPBOARD_LIMIT) {
            toClipboard(request)
            return
        }
        val save = MessageDialogBuilder
            .yesNo(
                TasklaneBundle.message("export.tooMany.title"),
                TasklaneBundle.message("export.tooMany.question", total, CLIPBOARD_LIMIT),
            )
            .yesText(TasklaneBundle.message("export.tooMany.save"))
            .ask(project)
        if (!save) return
        val file = chooseFile(request.name) ?: return
        toFile(request, file)
    }

    private fun toClipboard(request: TasklanePanel.ExportRequest) {
        val config = TaskService.getInstance(project).snapshot.value.config
        val format = format
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("export.progress", request.size), true) {
                private val text = StringBuilder()
                private var written = 0

                override fun run(indicator: ProgressIndicator) {
                    written = write(request, TaskExporter.Stream(text, config, format), indicator)
                }

                override fun onSuccess() {
                    if (written == 0) {
                        notify(TasklaneBundle.message("export.empty"), NotificationType.INFORMATION)
                        return
                    }
                    CopyPasteManager.getInstance().setContents(StringSelection(text.toString()))
                    notify(TasklaneBundle.message("export.copied", written), NotificationType.INFORMATION)
                }
            },
        )
    }

    private fun toFile(request: TasklanePanel.ExportRequest, target: Path) {
        val config = TaskService.getInstance(project).snapshot.value.config
        val format = format
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(
                project,
                TasklaneBundle.message("export.file.progress", request.size),
                true,
            ) {
                private var written = 0

                override fun run(indicator: ProgressIndicator) {
                    written = writeAtomically(target) { out -> write(request, TaskExporter.Stream(out, config, format), indicator) }
                }

                override fun onSuccess() = notifyFile(TasklaneBundle.message("export.file.done", written, target), target)

                override fun onThrowable(error: Throwable) {
                    thisLogger().warn("Tasklane: no se pudo exportar a $target", error)
                    notify(TasklaneBundle.message("export.file.failed", error.message.orEmpty()), NotificationType.ERROR)
                }
            },
        )
    }

    /**
     * Las secciones de la pestaña, sobre **una foto** del paginador, a tandas. Cada tanda
     * mira si se canceló y mueve la barra: son las dos cosas que una operación de minutos
     * le debe a quien espera.
     */
    private fun write(request: TasklanePanel.ExportRequest, stream: TaskExporter.Stream, indicator: ProgressIndicator): Int {
        indicator.isIndeterminate = false
        val total = request.size.coerceAtLeast(1)
        request.pager.snapshot { pager ->
            for (section in request.sections) {
                indicator.checkCanceled()
                stream.section(section.heading, section.date)
                section.read(pager) { chunk ->
                    indicator.checkCanceled()
                    chunk.forEach(stream::task)
                    indicator.fraction = stream.written.toDouble() / total
                }
            }
        }
        return stream.written
    }

    // ------------------------------------------------------------ tasks.xml

    /**
     * Vuelca las tareas de un repositorio a un `tasks.xml` en el formato de siempre.
     *
     * Es la promesa del §3.3 del plan de escala: *se conserva la exportación a XML para
     * que el dato no quede secuestrado dentro de un `.db`*. Una base SQLite se puede
     * abrir con cualquier visor, pero el formato que otra versión de este plugin sabe
     * **leer** es el XML, y tener la puerta de salida a un fichero de texto es lo que
     * hace que migrar a la base no sea un viaje de ida.
     *
     * Escribe donde estaba el original —`.idea/tasklane/repos/<repo>/tasks.xml`—, que es
     * justamente donde la migración lo buscaría: renombrar el `.migrated` y borrar la
     * base es la vuelta atrás completa.
     *
     * **En *streaming* desde la Fase 5**: [TasksXmlWriter] sobre una foto del repositorio,
     * a un temporal que sólo sustituye al fichero cuando está entero y en disco.
     *
     * **No mientras ese `tasks.xml` esté a medio importar.** Sería escribir encima del
     * fichero que se está leyendo —o del que una migración cancelada va a retomar— con lo
     * poco que ya hay en la base: el original se perdería.
     */
    fun exportXml(repo: RepoKey) {
        val layout = StorageLayout.forProject(project) ?: return
        val service = TaskService.getInstance(project)
        if (service.migrationPending(repo)) {
            notify(TasklaneBundle.message("export.xml.pending"), NotificationType.WARNING)
            return
        }
        val target = layout.tasksFile(repo)

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("export.xml.progress"), true) {
                private var written = 0
                private var replaced = 0

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    Files.createDirectories(target.parent)
                    layout.ensureIgnored()
                    service.snapshotOf(repo) { snapshot ->
                        // ANTES de que el fichero aparezca, no después: la detección de
                        // repositorios puede pasar en cualquier momento, y un `tasks.xml`
                        // sin su marca de importado es uno que se intentaría importar
                        // encima de lo que ya hay. Ver `TaskService.markExported`.
                        service.markExported(repo, snapshot.total)
                        val total = snapshot.total.coerceAtLeast(1)
                        writeAtomically(target, text = false) { _, stream ->
                            TasksXmlWriter(stream, repo).let { xml ->
                                snapshot.eachInOrder { chunk ->
                                    indicator.checkCanceled()
                                    xml.write(chunk)
                                    indicator.fraction = xml.written.toDouble() / total
                                }
                                xml.finish()
                                written = xml.written
                                replaced = xml.replaced
                            }
                        }
                    }
                }

                override fun onSuccess() {
                    val note = if (replaced > 0) " " + TasklaneBundle.message("export.xml.replaced", replaced) else ""
                    notifyFile(TasklaneBundle.message("export.xml.done", written, target) + note, target)
                }

                override fun onThrowable(error: Throwable) {
                    thisLogger().warn("Tasklane: no se pudo escribir $target", error)
                    notify(TasklaneBundle.message("export.xml.failed", error.message.orEmpty()), NotificationType.ERROR)
                }
            },
        )
    }

    // ------------------------------------------------------- exportar y quitar

    /**
     * «Exportar y quitar» para un repositorio que ya no está en disco.
     *
     * Es la salida que hace honesto el trato del catálogo con los repositorios
     * ausentes: sus tareas nunca se ocultan ni se borran solas, pero renombrar una
     * carpeta no puede condenar a arrastrar la entrada para siempre. Se exporta todo, se
     * comprueba, y sólo entonces se borra.
     *
     * ## A escala (Fase 5)
     *
     * Hasta la 2.1 esto leía **en el EDT** todas las tareas del repositorio, construía el
     * texto entero, lo copiaba y borraba el repositorio en una única transacción. Con un
     * millón de tareas eran tres congelaciones seguidas. Ahora:
     *
     * 1. **Se confirma con la cuenta de `counter`**, sin leer nada. Por encima de
     *    [CLIPBOARD_LIMIT] el destino es un fichero, y se dice en la pregunta.
     * 2. **El repositorio pasa a solo lectura** mientras dura: lo que se borra es lo que se
     *    exportó, y no puede colarse nada en medio.
     * 3. **Se exporta sobre una foto** y se comprueba que salieron **todas** las que la foto
     *    contaba. Si no cuadra, no se borra nada y se dice.
     * 4. **Sólo con lo exportado a salvo** —el fichero en disco, o el texto en el
     *    portapapeles— se borra, **por tandas**: ver [TaskService.removeRepo].
     *
     * La exportación se cancela; el borrado, no. Ver el porqué en [TaskService.removeRepo].
     */
    fun exportAndRemove(ref: RepositoryRef) {
        val service = TaskService.getInstance(project)
        val repo = ref.key
        if (service.migrationPending(repo)) {
            notify(TasklaneBundle.message("export.xml.pending"), NotificationType.WARNING)
            return
        }
        val total = service.countOf(repo)
        val toFile = total > CLIPBOARD_LIMIT

        val confirmed = MessageDialogBuilder
            .yesNo(
                TasklaneBundle.message("export.remove.title", ref.displayName),
                if (toFile) TasklaneBundle.message("export.remove.question.file", total)
                else TasklaneBundle.message("export.remove.question", total),
            )
            .yesText(
                if (toFile) TasklaneBundle.message("export.remove.confirm.file")
                else TasklaneBundle.message("export.remove.confirm"),
            )
            .ask(project)
        if (!confirmed) return

        val target = if (toFile) chooseFile("${ref.displayName}-tasks") ?: return else null
        if (!service.beginRemoval(repo)) {
            notify(TasklaneBundle.message("export.remove.busy", ref.displayName), NotificationType.WARNING)
            return
        }

        val config = service.snapshot.value.config
        val format = format
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(
                project,
                TasklaneBundle.message("export.remove.progress", ref.displayName),
                true,
            ) {
                private var written = 0
                private var removed = false

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false

                    // 1. Exportar, sobre una foto. Cancelable.
                    val text = if (target == null) StringBuilder() else null
                    val export: (Appendable) -> Int = { out ->
                        service.snapshotOf(repo) { snapshot ->
                            val stream = TaskExporter.Stream(out, config, format)
                            val expected = snapshot.total.coerceAtLeast(1)
                            for (state in snapshot.states()) {
                                val name = config.state(state)?.name ?: state.value
                                stream.section("${ref.displayName} · $name")
                                snapshot.eachInState(state) { chunk ->
                                    indicator.checkCanceled()
                                    chunk.forEach(stream::task)
                                    indicator.fraction = stream.written.toDouble() / expected
                                }
                            }
                            // La comprobación que sostiene el borrado de después. Ver el KDoc.
                            if (stream.written != snapshot.total) {
                                throw IncompleteExport(stream.written, snapshot.total)
                            }
                            stream.written
                        } ?: 0
                    }
                    written = if (target != null) writeAtomically(target, write = export) else export(text!!)

                    // 2. Dejar lo exportado a salvo ANTES de borrar nada.
                    if (text != null && text.isNotEmpty()) {
                        ApplicationManager.getApplication().invokeAndWait(
                            { CopyPasteManager.getInstance().setContents(StringSelection(text.toString())) },
                            ModalityState.any(),
                        )
                    }

                    // 3. Borrar. Ya no se cancela: ver `TaskService.removeRepo`.
                    indicator.fraction = 0.0
                    indicator.text = TasklaneBundle.message("export.remove.removing")
                    ProgressManager.getInstance().executeNonCancelableSection {
                        service.removeRepo(repo) { phase, n ->
                            when (phase) {
                                TaskService.Removal.TASKS -> indicator.fraction = n.toDouble() / written.coerceAtLeast(1)
                                TaskService.Removal.FILES -> indicator.text2 =
                                    TasklaneBundle.message("export.remove.files", n)
                            }
                        }
                    }
                    removed = true
                }

                override fun onSuccess() {
                    if (target != null) {
                        notifyFile(TasklaneBundle.message("export.remove.done.file", ref.displayName, written, target), target)
                    } else {
                        notify(
                            TasklaneBundle.message("export.remove.done", ref.displayName, written),
                            NotificationType.INFORMATION,
                        )
                    }
                }

                /**
                 * Pulsar cancelar con el borrado ya en marcha no lo para —ver `run`—, pero la
                 * plataforma sigue llamando aquí y no a [onSuccess]. Lo que se hizo, se dice.
                 */
                override fun onCancel() {
                    if (removed) onSuccess() else service.cancelRemoval(repo)
                }

                override fun onThrowable(error: Throwable) {
                    if (!removed) service.cancelRemoval(repo)
                    val message = when (error) {
                        is IncompleteExport ->
                            TasklaneBundle.message("export.remove.incomplete", ref.displayName, error.written, error.expected)
                        else -> TasklaneBundle.message("export.file.failed", error.message.orEmpty())
                    }
                    thisLogger().warn("Tasklane: «Exportar y quitar» no terminó para $repo", error)
                    notify(message, NotificationType.ERROR)
                }
            },
        )
    }

    /** Lo exportado no cuadra con lo que la foto contaba: no se borra nada. */
    private class IncompleteExport(val written: Int, val expected: Int) :
        IllegalStateException("exportadas $written de $expected")

    // ---------------------------------------------------------------- disco

    /**
     * Pregunta dónde guardar. Markdown o texto según el formato elegido, que es lo que se
     * va a escribir dentro.
     */
    private fun chooseFile(name: String): Path? {
        val extension = if (format == ExportFormat.MARKDOWN) "md" else "txt"
        val descriptor = FileSaverDescriptor(
            TasklaneBundle.message("export.file.title"),
            TasklaneBundle.message("export.file.description"),
            extension,
        )
        val base: Path? = project.basePath?.let { Path.of(it) }
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]+"), "-").trim('-', ' ').ifEmpty { "tasks" }
        return FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(base, "$safe-${LocalDate.now()}.$extension")
            ?.file
            ?.toPath()
    }

    /**
     * Escribe [target] **entero o nada**: a un temporal en el mismo directorio, forzado a
     * disco, y movido encima al terminar. Si se cancela o falla a mitad, el temporal se
     * borra y lo que hubiera en [target] sigue ahí.
     */
    private fun writeAtomically(target: Path, write: (Appendable) -> Int): Int {
        var written = 0
        writeAtomically(target, text = true) { out, _ -> written = write(out!!) }
        return written
    }

    private fun writeAtomically(target: Path, text: Boolean, write: (Appendable?, FileOutputStream) -> Unit) {
        Files.createDirectories(target.toAbsolutePath().parent)
        val tmp = Files.createTempFile(target.toAbsolutePath().parent, ".${target.fileName}", ".part")
        try {
            FileOutputStream(tmp.toFile()).use { stream ->
                if (text) {
                    val writer = OutputStreamWriter(stream, StandardCharsets.UTF_8).buffered(1 shl 16)
                    write(writer, stream)
                    writer.flush()
                } else {
                    write(null, stream)
                }
                stream.fd.sync()
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun notifyFile(content: String, file: Path) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(TaskService.NOTIFICATION_GROUP)
            .createNotification(content, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openFile(file) })
            .notify(project)
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(TaskService.NOTIFICATION_GROUP)
            .createNotification(content, type)
            .notify(project)
    }

    companion object {
        /**
         * Hasta cuántas tareas se copian al portapapeles. El número del plan, y con
         * holgura: diez mil tareas de la especificación son ~20 MB de texto, que ya es más
         * de lo que ningún destino de un pegado aguanta con gracia. Por encima se ofrece
         * un fichero.
         */
        const val CLIPBOARD_LIMIT = 10_000

        fun getInstance(project: Project): ExportService = project.service()
    }
}
