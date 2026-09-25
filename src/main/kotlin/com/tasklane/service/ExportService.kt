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
import com.intellij.openapi.ui.Messages
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.data.sqlite.TasksXmlWriter
import com.tasklane.data.store.StorageLayout
import com.tasklane.data.sqlite.RepoSnapshot
import com.tasklane.domain.export.ExportFormat
import com.tasklane.domain.export.FileFormat
import com.tasklane.domain.export.TaskCsvWriter
import com.tasklane.domain.export.TaskExporter
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.ui.toolwindow.TasklanePanel
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
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
 * «Exportar y quitar». Y desde la 2.3.0, guardar un repositorio entero en CSV, Markdown o
 * texto, y vaciarlo —[saveRepo] y [clearRepo]—, **siempre sobre el repositorio activo**:
 * en Tasklane todo es por repositorio, y borrar también.
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
        val file = chooseFile(request.name, textExtension()) ?: return
        toFile(request, file)
    }

    private fun toClipboard(request: TasklanePanel.ExportRequest) {
        val config = TaskService.getInstance(project).snapshot.value.config
        val format = format
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("export.progress", request.size), true) {
                private val text = StringBuilder()
                private var written = 0
                private var copied = false

                override fun run(indicator: ProgressIndicator) {
                    written = write(request, TaskExporter.Stream(text, config, format), indicator)
                    if (written > 0) copied = putOnClipboard(text.toString())
                }

                /** Lo que se dice al final es lo que pasó, no lo que se pidió. Ver [putOnClipboard]. */
                override fun onSuccess() = when {
                    written == 0 -> notify(TasklaneBundle.message("export.empty"), NotificationType.INFORMATION)
                    copied -> notify(TasklaneBundle.message("export.copied", written), NotificationType.INFORMATION)
                    else -> notify(TasklaneBundle.message("export.clipboard.failed", written), NotificationType.ERROR)
                }

                override fun onThrowable(error: Throwable) {
                    thisLogger().warn("Tasklane: no se pudo copiar al portapapeles", error)
                    notify(TasklaneBundle.message("export.file.failed", error.message.orEmpty()), NotificationType.ERROR)
                }
            },
        )
    }

    /**
     * Deja [content] en el portapapeles y **comprueba que llegó**. Devuelve si lo hizo.
     *
     * Existe porque copiar puede fallar **en silencio**. `CopyPasteManager` acaba en
     * `ClipboardSynchronizer`, y ahí un portapapeles ocupado —otra aplicación lo tiene
     * tomado: un gestor de historial, un menú que se está cerrando— sale por un
     * `IllegalStateException` que la plataforma **intenta una sola vez** —su
     * `getRetries()` devuelve 1—, manda a `LOG.debug` y se traga: la llamada vuelve como
     * si nada. Hasta la 2.6.1 lo siguiente que hacía esto era anunciar «N tareas
     * copiadas», así que el aviso salía, el portapapeles seguía con lo de antes y no
     * quedaba rastro de por qué. Un aviso que miente es peor que un fallo, porque quien
     * copia cierra la ventana creyendo que tiene el texto a salvo.
     *
     * Así que se escribe, se **relee del portapapeles del sistema** —no del propio
     * `CopyPasteManager`, que devuelve lo que él mismo se apuntó y diría que sí aunque la
     * escritura no hubiera salido— y, si no está, se reintenta: primero por el camino del
     * IDE, que es el que además alimenta el historial de `⌘⇧V`, y después contra el
     * portapapeles del sistema a pelo. Entre intento e intento se espera, que es lo único
     * que arregla a quien lo tenía tomado un instante.
     *
     * Se llama **desde el hilo de fondo** y no desde `onSuccess` a propósito: al EDT sólo
     * salta la escritura, y con [ModalityState.any] —igual que en «Exportar y quitar»—
     * para que ni un diálogo ni un menú abierto la dejen esperando en la cola. La
     * relectura se queda en el hilo de fondo: son diez mil tareas como mucho, pero son
     * hasta ~20 MB, y eso no se le pone delante al hilo de interfaz por comprobar.
     */
    private fun putOnClipboard(content: String): Boolean {
        if (content.isEmpty()) return false
        repeat(CLIPBOARD_TRIES) { round ->
            if (round > 0) runCatching { Thread.sleep(CLIPBOARD_RETRY_MS) }
            // El camino del IDE primero: es el que además alimenta el historial de `⌘⇧V`.
            if (attempt(content) { CopyPasteManager.getInstance().setContents(StringSelection(content)) }) return true
            if (attempt(content) { systemClipboard()?.setContents(StringSelection(content), null) }) return true
        }
        thisLogger().warn("Tasklane: el portapapeles no admitió ${content.length} caracteres")
        return false
    }

    /** Un intento: escribir en el EDT y releer aquí. Lo que no llegó al sistema no está copiado. */
    private fun attempt(content: String, put: () -> Unit): Boolean = runCatching {
        ApplicationManager.getApplication().invokeAndWait(put, ModalityState.any())
        systemClipboard()?.getData(DataFlavor.stringFlavor) == content
    }.getOrDefault(false)

    private fun systemClipboard(): Clipboard? =
        runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()

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

        val target = if (toFile) chooseFile("${ref.displayName}-tasks", textExtension()) ?: return else null
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
                            eachOfRepo(snapshot, config, indicator, { stream.section("${ref.displayName} · $it") }, stream::task)
                            // La comprobación que sostiene el borrado de después. Ver el KDoc.
                            if (stream.written != snapshot.total) {
                                throw IncompleteExport(stream.written, snapshot.total)
                            }
                            stream.written
                        } ?: 0
                    }
                    written = if (target != null) writeAtomically(target, write = export) else export(text!!)

                    // 2. Dejar lo exportado a salvo ANTES de borrar nada, y comprobar que
                    //    está ahí: el portapapeles puede rechazar el texto sin decirlo —ver
                    //    `putOnClipboard`—, y aquí eso sería borrar las tareas después de
                    //    haber perdido la única copia. Si no llega, no se borra nada.
                    if (text != null && text.isNotEmpty() && !putOnClipboard(text.toString())) {
                        throw ClipboardRefused(written)
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
                        is ClipboardRefused -> TasklaneBundle.message("export.clipboard.failed", error.written)
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

    /** El portapapeles no admitió lo exportado: tampoco se borra nada. */
    private class ClipboardRefused(val written: Int) :
        IllegalStateException("el portapapeles rechazó $written tareas")

    /**
     * Las tareas de una foto de repositorio, **estado a estado** en el orden de las
     * pestañas y, dentro de cada uno, en el orden de la lista. [onState] recibe el nombre
     * del estado antes de sus tareas. Cada tanda mira si se canceló y mueve la barra.
     */
    private fun eachOfRepo(
        snapshot: RepoSnapshot,
        config: TasklaneConfig,
        indicator: ProgressIndicator,
        onState: (String) -> Unit,
        onTask: (Task) -> Unit,
    ): Int {
        val expected = snapshot.total.coerceAtLeast(1)
        var done = 0
        for (state in snapshot.states()) {
            onState(config.state(state)?.name ?: state.value)
            snapshot.eachInState(state) { chunk ->
                indicator.checkCanceled()
                chunk.forEach(onTask)
                done += chunk.size
                indicator.fraction = done.toDouble() / expected
            }
        }
        return done
    }

    // --------------------------------------------------- un repositorio entero (2.3.0)

    /**
     * Guarda **todas** las tareas del repositorio activo en un fichero [format]: CSV para
     * una hoja de cálculo, Markdown o texto para leerlas.
     *
     * No es «copiar este estado» con otro destino: aquello saca lo que se ve en una
     * pestaña —con su búsqueda y su filtro—, y esto saca el repositorio entero, estado a
     * estado, que es lo que se quiere tener a mano antes de vaciarlo con [clearRepo] o
     * para llevárselo a otra herramienta.
     *
     * Por el mismo camino que «Exportar y quitar»: una foto de la base, en segundo plano,
     * cancelable, a tandas, y a un temporal que sólo sustituye al fichero cuando está
     * entero. Con un millón de tareas lo retenido sigue siendo una tanda.
     */
    fun saveRepo(ref: RepositoryRef, format: FileFormat) {
        val service = TaskService.getInstance(project)
        val repo = ref.key
        if (service.migrationPending(repo)) {
            notify(TasklaneBundle.message("export.xml.pending"), NotificationType.WARNING)
            return
        }
        val total = service.countOf(repo)
        if (total == 0) {
            notify(TasklaneBundle.message("export.repo.empty", ref.displayName), NotificationType.INFORMATION)
            return
        }
        val target = chooseFile("${ref.displayName}-tasks", format.extension) ?: return
        val config = service.snapshot.value.config

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(
                project,
                TasklaneBundle.message("export.repo.progress", total, ref.displayName),
                true,
            ) {
                private var written = 0

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    written = writeAtomically(target) { out ->
                        service.snapshotOf(repo) { snapshot ->
                            when (format) {
                                FileFormat.CSV -> {
                                    val csv = TaskCsvWriter(out, config)
                                    csv.begin()
                                    eachOfRepo(snapshot, config, indicator, {}, csv::task)
                                }

                                FileFormat.MARKDOWN, FileFormat.PLAIN -> {
                                    val text = if (format == FileFormat.MARKDOWN) ExportFormat.MARKDOWN else ExportFormat.PLAIN
                                    val stream = TaskExporter.Stream(out, config, text)
                                    eachOfRepo(snapshot, config, indicator, { stream.section("${ref.displayName} · $it") }, stream::task)
                                }
                            }
                        } ?: 0
                    }
                }

                override fun onSuccess() = notifyFile(TasklaneBundle.message("export.file.done", written, target), target)

                override fun onThrowable(error: Throwable) {
                    thisLogger().warn("Tasklane: no se pudo guardar $repo en $target", error)
                    notify(TasklaneBundle.message("export.file.failed", error.message.orEmpty()), NotificationType.ERROR)
                }
            },
        )
    }

    /**
     * Vacía el repositorio activo: **borra todas sus tareas** de la base, después de
     * preguntar con el número y el nombre delante.
     *
     * Es la otra mitad de lo que se pidió en la 2.3.0 —guardar y limpiar—, y va **separada**
     * de [saveRepo] a propósito: vaciar no obliga a exportar antes. La pregunta lo recuerda
     * y el menú lo tiene justo encima, que es lo que se puede hacer sin convertir un
     * borrado en un trámite de dos pasos.
     *
     * El repositorio se queda en su sitio, vacío: ver [TaskService.clearRepo]. Mientras se
     * borra está en solo lectura, y **no se cancela**: lo que se pidió es no tener estas
     * tareas, y parar a medias dejaría una parte que nadie eligió.
     */
    fun clearRepo(ref: RepositoryRef) {
        val service = TaskService.getInstance(project)
        val repo = ref.key
        if (service.migrationPending(repo)) {
            notify(TasklaneBundle.message("export.xml.pending"), NotificationType.WARNING)
            return
        }
        val total = service.countOf(repo)
        if (total == 0) {
            notify(TasklaneBundle.message("clear.empty", ref.displayName), NotificationType.INFORMATION)
            return
        }
        val confirmed = MessageDialogBuilder
            .yesNo(
                TasklaneBundle.message("clear.title", ref.displayName),
                TasklaneBundle.message("clear.question", total, ref.displayName),
            )
            .yesText(TasklaneBundle.message("clear.confirm", total))
            .icon(Messages.getWarningIcon())
            .ask(project)
        if (!confirmed) return
        if (!service.beginRemoval(repo)) {
            notify(TasklaneBundle.message("export.remove.busy", ref.displayName), NotificationType.WARNING)
            return
        }

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(
                project,
                TasklaneBundle.message("clear.progress", ref.displayName),
                false,
            ) {
                private var removed = 0L

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    removed = service.clearRepo(repo) { n -> indicator.fraction = n.toDouble() / total }
                }

                override fun onSuccess() =
                    notify(TasklaneBundle.message("clear.done", removed, ref.displayName), NotificationType.INFORMATION)

                override fun onThrowable(error: Throwable) {
                    thisLogger().warn("Tasklane: no se pudo vaciar $repo", error)
                    notify(TasklaneBundle.message("clear.failed", ref.displayName, error.message.orEmpty()), NotificationType.ERROR)
                }
            },
        )
    }

    // ---------------------------------------------------------------- disco

    /** Pregunta dónde guardar, con la extensión de lo que se va a escribir dentro. */
    private fun chooseFile(name: String, extension: String): Path? {
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

    /** Markdown o texto según el formato de la copia, que es lo que se va a escribir. */
    private fun textExtension(): String =
        if (format == ExportFormat.MARKDOWN) FileFormat.MARKDOWN.extension else FileFormat.PLAIN.extension

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

        /**
         * Cuántas veces se intenta dejar el texto en el portapapeles, y cuánto se espera
         * entre intentos. Quien lo tiene tomado —un gestor de historial, un menú que se
         * cierra— lo suelta en cuanto termina lo suyo: tres intentos con una décima de
         * segundo por medio cubren eso sin que nadie note la espera, y lo que no entre en
         * ese margen no es un tropiezo sino un fallo, y se dice. Ver [putOnClipboard].
         */
        const val CLIPBOARD_TRIES = 3
        const val CLIPBOARD_RETRY_MS = 120L

        fun getInstance(project: Project): ExportService = project.service()
    }
}
