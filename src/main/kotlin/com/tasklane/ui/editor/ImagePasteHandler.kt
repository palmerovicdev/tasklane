package com.tasklane.ui.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.service.AttachmentService
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import javax.imageio.ImageIO

/**
 * Pegar una imagen en el editor de una tarea.
 *
 * Se engancha al atajo de pegar **del keymap del usuario** en vez de a `⌘V` a pelo, y
 * cuando lo que hay en el portapapeles no es una imagen delega en el pegado normal de
 * la plataforma. Es lo que hace que el atajo siga siendo uno solo: el usuario pega, y
 * el plugin decide si eso era texto o una captura.
 *
 * El trabajo caro —normalizar, re-codificar a PNG y escribir— ocurre **fuera del
 * EDT**. Sólo la inserción del texto vuelve al hilo de UI, y va dentro de un
 * [WriteCommandAction] con nombre propio: sin eso, `⌘Z` no desharía el pegado, que es
 * justo el criterio de la fase.
 *
 * El blob se escribe antes de saber si la tarea llegará a guardarse. Es deliberado —
 * la referencia tiene que poder insertarse ya—, y lo que queda si el usuario cancela
 * o deshace es un huérfano, que es precisamente lo que recoge
 * [com.tasklane.data.attachment.AttachmentGc] pasado el periodo de gracia.
 */
internal class ImagePasteHandler(
    private val project: Project,
    private val repo: RepoKey,
    private val editor: EditorEx,
    private val onAttached: (AttachmentId) -> Unit,
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /**
     * La acción sólo existe cuando el portapapeles trae una imagen. Deshabilitarla
     * en el resto de los casos —en vez de aceptar el atajo y reenviarlo— hace que
     * el evento caiga en la acción de pegar de la plataforma, que es la que sabe de
     * todos los formatos que el IDE admite. Interponerse siempre significaba decidir
     * por ella, y lo que no se reconocía como imagen se perdía.
     */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = clipboardHasImage()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val image = clipboardImage()
        if (image == null) {
            delegateToPlatform(event)
            return
        }
        attach(image)
    }

    /**
     * Si el portapapeles trae algo que **pueda** ser una imagen, sin llegar a
     * decodificarla: [update] corre en el EDT cada vez que se pulsa el atajo, y leer
     * un PNG de disco ahí se notaría.
     */
    private fun clipboardHasImage(): Boolean {
        val contents: Transferable = CopyPasteManager.getInstance().contents ?: return false
        return runCatching {
            contents.isDataFlavorSupported(DataFlavor.imageFlavor) || imageFile(contents) != null
        }.getOrDefault(false)
    }

    /**
     * Lo que se acepta: una imagen en el portapapeles —lo que deja una captura de
     * pantalla— o un fichero de imagen copiado del explorador. El segundo caso cuesta
     * cuatro líneas y evita la pregunta obvia de por qué no funciona.
     */
    private fun clipboardImage(): Image? {
        val contents: Transferable = CopyPasteManager.getInstance().contents ?: return null
        runCatching {
            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                return contents.getTransferData(DataFlavor.imageFlavor) as? Image
            }
            return imageFile(contents)?.let(ImageIO::read)
        }
        return null
    }

    /**
     * El fichero de imagen del portapapeles, o `null`.
     *
     * `isFile` no sobra: macOS publica una URL copiada del navegador como lista de
     * ficheros, así que sin comprobar que existe, pegar un enlace entraba por aquí y
     * se quedaba por el camino.
     */
    private fun imageFile(contents: Transferable): File? {
        if (!contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
        @Suppress("UNCHECKED_CAST")
        val files = contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
        return files?.firstOrNull()
            ?.takeIf { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
    }

    private fun attach(image: Image) {
        val offset = editor.caretModel.offset
        val service = AttachmentService.getInstance(project)

        ApplicationManager.getApplication().executeOnPooledThread {
            val id = service.attach(repo, image)
            ApplicationManager.getApplication().invokeLater(
                { if (id != null) insert(id, offset) },
                // El diálogo es modal: sin `any()` la inserción esperaría a que se
                // cerrara, que es exactamente cuando ya no sirve de nada.
                ModalityState.any(),
            )
        }
    }

    private fun insert(id: AttachmentId, offset: Int) {
        if (project.isDisposed || editor.isDisposed) return
        val document = editor.document
        // El documento pudo encoger mientras se escribía el blob.
        val at = offset.coerceIn(0, document.textLength)
        val text = AttachmentService.getInstance(project).reference(id)

        WriteCommandAction.writeCommandAction(project)
            .withName(TasklaneBundle.message("editor.image.paste.command"))
            .run<RuntimeException> {
                // El salto de línea detrás no es cosmético: deja el cursor FUERA de la
                // referencia, y una región plegada con el cursor dentro se despliega
                // sola y volvería a enseñar el SHA.
                document.insertString(at, "$text\n")
            }
        editor.caretModel.moveToOffset((at + text.length + 1).coerceAtMost(document.textLength))
        onAttached(id)
    }

    /**
     * Lo que no es una imagen se pega como siempre: se llama al manejador de pegado
     * del editor, el mismo que ejecutaría la plataforma si esta acción no existiera.
     */
    private fun delegateToPlatform(event: AnActionEvent) {
        EditorActionManager.getInstance()
            .getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
            .execute(editor, editor.caretModel.currentCaret, event.dataContext)
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    }
}
