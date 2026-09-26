package com.tasklane.ui.toolwindow

import com.intellij.CommonBundle
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.StatusBar
import com.tasklane.TasklaneBundle
import com.tasklane.code.CodeAnchors
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.text.PastedLines
import com.tasklane.service.AttachmentService
import com.tasklane.service.TaskService
import com.tasklane.ui.editor.ImageInserter
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import javax.swing.JComponent
import com.intellij.openapi.progress.Task as ProgressTask

/**
 * Pegar y soltar **en la lista** (2.19.0): lo que llega se convierte en tareas nuevas
 * del estado que se está mirando, sin pasar por el diálogo.
 *
 * Tasklane existe para capturar, y hasta aquí la lista no aceptaba nada: `⌘V` sobre ella no
 * hacía nada y soltar algo tampoco. Lo que se entiende:
 *
 * - **Texto**: una tarea por línea, sin viñetas, números ni casillas. Ver [PastedLines]. Una
 *   URL es una línea más, y la tarea nace con su enlace.
 * - **Una captura** —píxeles en el portapapeles—: una tarea con ella.
 * - **Ficheros**, copiados o soltados desde el Finder o la vista del proyecto: una imagen da
 *   una tarea con la captura, y un fichero de texto, una tarea anclada a él, con su nombre
 *   por título. Las carpetas y los binarios que no son imagen se saltan sin avisar, como en
 *   la franja del diálogo: soltar un PDF no es un error.
 *
 * **Sin preguntar, salvo si son muchas.** Lo creado queda seleccionado —un `Enter` y se le
 * escribe el título— y se deshace entero con un `⌘Z`. Por encima de [ASK_ABOVE] se pregunta
 * antes, porque unas decenas de tareas de golpe suelen ser un párrafo o un log pegado donde
 * no era; al texto se le ofrece entonces también **una sola tarea** con todo.
 *
 * El orden de lo que se lee es el del diálogo —ver `ClipboardImage`—: ficheros, píxeles y
 * por último texto. Con la regla escrita dos veces, pegar acabaría haciendo cosas distintas
 * según dónde se pegue.
 */
internal class ListIntake(
    private val project: Project,
    /** Dónde nacen: el repositorio activo y el estado de esta lista. `null` si no se puede escribir. */
    private val target: () -> Target?,
    /** Las tareas recién creadas, para seleccionarlas. */
    private val onCreated: (Set<TaskId>) -> Unit,
    /** Si hay encima algo que se puede soltar: la lista se recuadra mientras tanto. */
    private val onHover: (Boolean) -> Unit,
) {

    data class Target(val repo: RepoKey, val state: StateId)

    /** Lo que trae el portapapeles o lo que se suelta, ya leído. */
    sealed interface Taken {
        data class Files(val files: List<File>) : Taken

        data class Pixels(val image: Image) : Taken

        data class Text(val text: String) : Taken
    }

    /** Lo que ha salido de un fichero o de una captura, antes de ser tarea. */
    private data class Draft(val body: String, val anchors: List<CodeAnchor> = emptyList())

    /**
     * Si `⌘V` tiene algo que hacer aquí. Sólo mira los formatos, sin leer nada: se pregunta
     * en el EDT cada vez que se pulsa el atajo.
     */
    fun canPaste(): Boolean {
        if (target() == null) return false
        val contents = CopyPasteManager.getInstance().contents ?: return false
        return runCatching { accepts(contents.transferDataFlavors) }.getOrDefault(false)
    }

    fun paste() {
        val target = target() ?: return
        val taken = CopyPasteManager.getInstance().contents?.let(::read) ?: return
        take(taken, target)
    }

    /**
     * Hace de [component] un sitio donde soltar.
     *
     * Un `DropTarget` a pelo, como la franja del diálogo, porque hace falta saber cuándo entra
     * y sale algo para recuadrar la lista. Recibe lo que se arrastra desde el Finder y
     * también desde la vista del proyecto, que publica sus ficheros como lista de ficheros.
     * Arrastrar una tarjeta por su asa no pasa por aquí: ese arrastre es del ratón, no de
     * AWT. Ver [CardReorder].
     */
    fun install(component: JComponent) {
        component.dropTarget = DropTarget(
            component,
            DnDConstants.ACTION_COPY,
            object : DropTargetAdapter() {
                override fun dragEnter(event: DropTargetDragEvent) = consider(event)

                override fun dragOver(event: DropTargetDragEvent) = consider(event)

                override fun dragExit(event: DropTargetEvent) = onHover(false)

                override fun drop(event: DropTargetDropEvent) {
                    onHover(false)
                    val target = target()
                    if (target == null || !accepts(event.currentDataFlavors)) {
                        event.rejectDrop()
                        return
                    }
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    val taken = read(event.transferable)
                    event.dropComplete(taken != null)
                    // Después y no aquí dentro: la pregunta de «¿tantas?» es modal, y
                    // abrirla sin haber cerrado el arrastre deja al Finder esperando.
                    if (taken != null) ApplicationManager.getApplication().invokeLater({ take(taken, target) }, project.disposed)
                }
            },
        )
    }

    private fun consider(event: DropTargetDragEvent) {
        val ok = target() != null && accepts(event.currentDataFlavors)
        if (ok) event.acceptDrag(DnDConstants.ACTION_COPY) else event.rejectDrag()
        onHover(ok)
    }

    private fun take(taken: Taken, target: Target) {
        when (taken) {
            is Taken.Text -> fromText(taken.text, target)
            is Taken.Files -> fromFiles(taken.files, target)
            is Taken.Pixels -> inBackground(target, 1) {
                val attachments = AttachmentService.getInstance(project)
                listOfNotNull(attachments.attach(target.repo, taken.image)?.let { Draft(attachments.reference(it)) })
            }
        }
    }

    private fun fromText(text: String, target: Target) {
        val lines = PastedLines.split(text)
        if (lines.isEmpty()) return
        val bodies = if (lines.size <= ASK_ABOVE) {
            lines
        } else {
            val answer = MessageDialogBuilder
                .yesNoCancel(
                    TasklaneBundle.message("intake.ask.title"),
                    TasklaneBundle.message("intake.ask.lines", lines.size),
                )
                .yesText(TasklaneBundle.message("intake.ask.many", lines.size))
                .noText(TasklaneBundle.message("intake.ask.one"))
                .cancelText(CommonBundle.getCancelButtonText())
                .show(project)
            when (answer) {
                Messages.YES -> lines
                Messages.NO -> listOf(text.trim())
                else -> return
            }
        }
        create(target, bodies.map(::Draft))
    }

    private fun fromFiles(files: List<File>, target: Target) {
        val usable = files.filter(File::isFile)
        if (usable.isEmpty()) return
        if (usable.size > ASK_ABOVE) {
            val go = MessageDialogBuilder
                .yesNo(
                    TasklaneBundle.message("intake.ask.title"),
                    TasklaneBundle.message("intake.ask.files", usable.size),
                )
                .yesText(TasklaneBundle.message("intake.ask.many", usable.size))
                .noText(CommonBundle.getCancelButtonText())
                .ask(project)
            if (!go) return
        }
        inBackground(target, usable.size) { indicator ->
            usable.mapNotNull { file ->
                indicator.checkCanceled()
                fromFile(file, target.repo)
            }
        }
    }

    /**
     * La tarea de un fichero. **Bloqueante**: guarda la imagen o busca el fichero en el VFS.
     *
     * El ancla es la del fichero por su principio, como al crear desde la vista del proyecto:
     * ver `CodeAnchors.capture`. El nombre va de título porque una tarea no puede nacer vacía,
     * y es lo primero que se va a querer cambiar.
     */
    private fun fromFile(file: File, repo: RepoKey): Draft? {
        if (ImageInserter.isImage(file)) {
            val attachments = AttachmentService.getInstance(project)
            return attachments.attachFile(repo, file.toPath())?.let { Draft(attachments.reference(it)) }
        }
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return null
        val binary = ReadAction.computeBlocking<Boolean, RuntimeException> { virtual.isDirectory || virtual.fileType.isBinary }
        if (binary) return null
        return Draft(virtual.name, listOf(CodeAnchor.of(CodeAnchors.pathOf(project, virtual), line = 0)))
    }

    /** Guardar imágenes y leer el VFS, fuera del EDT; crear, de vuelta en él. */
    private fun inBackground(target: Target, count: Int, work: (ProgressIndicator) -> List<Draft>) {
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("intake.progress", count), true) {
                private var drafts = emptyList<Draft>()

                override fun run(indicator: ProgressIndicator) {
                    drafts = work(indicator)
                }

                override fun onSuccess() = create(target, drafts)
            },
        )
    }

    /**
     * **Una** transacción para todas, en el orden en que llegaron, y un solo paso de `⌘Z`.
     * Los ids se ponen aquí para poder seleccionarlas cuando aparezcan.
     *
     * La barra de estado dice cuántas y dónde porque pueden no verse: con una búsqueda o un
     * filtro puestos, lo recién creado no tiene por qué casar.
     */
    private fun create(target: Target, drafts: List<Draft>) {
        if (drafts.isEmpty()) return
        val service = TaskService.getInstance(project)
        if (service.isReadOnly(target.repo)) return
        val creates = drafts.map { draft ->
            TaskCommand.Create(
                repo = target.repo,
                body = draft.body,
                stateId = target.state,
                anchors = draft.anchors,
                id = TaskId.random(),
            )
        }
        service.apply(TaskCommand.CreateMany(target.repo, creates))
        onCreated(creates.mapNotNullTo(HashSet()) { it.id })
        val state = service.snapshot.value.config.stateOrDefault(target.state).name
        StatusBar.Info.set(TasklaneBundle.message("intake.created", creates.size, state), project)
    }

    companion object {
        /** Hasta cuántas tareas se crean sin preguntar. */
        const val ASK_ABOVE = 10

        /** Si hay algo que leer, por los formatos que se anuncian. */
        fun accepts(flavors: Array<DataFlavor>): Boolean =
            FileCopyPasteUtil.isFileListFlavorAvailable(flavors) ||
                flavors.any { it == DataFlavor.imageFlavor || it == DataFlavor.stringFlavor }

        /**
         * Lo que hay: ficheros que existen, píxeles o texto, por ese orden.
         *
         * Que los ficheros **existan** no sobra: macOS publica una URL copiada del navegador
         * también como lista de ficheros, y sin comprobarlo esa URL no llegaría nunca a ser
         * texto. Ver `ClipboardImage`.
         */
        fun read(contents: Transferable): Taken? = runCatching {
            FileCopyPasteUtil.getFileList(contents)?.filter(File::exists)?.takeIf { it.isNotEmpty() }?.let {
                return Taken.Files(it)
            }
            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                (contents.getTransferData(DataFlavor.imageFlavor) as? Image)?.let { return Taken.Pixels(it) }
            }
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                (contents.getTransferData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotBlank() }?.let {
                    return Taken.Text(it)
                }
            }
            null
        }.getOrNull()
    }
}
