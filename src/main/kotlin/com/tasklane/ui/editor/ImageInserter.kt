package com.tasklane.ui.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.service.AttachmentService
import java.awt.Image
import java.io.File

/**
 * Adjuntar una imagen al cuerpo que se está editando: guardarla y dejar la referencia
 * donde esté el cursor.
 *
 * Es un sitio único a propósito. Hay tres gestos que acaban aquí —pegar, soltar un
 * fichero y elegirlo con el botón— y los tres tienen que hacer exactamente lo mismo:
 * guardar **fuera del EDT**, volver al hilo de UI sólo para insertar el texto, y
 * hacerlo dentro de un [WriteCommandAction] con nombre propio para que `⌘Z` deshaga
 * el gesto entero. Repartido por tres clases, cualquiera de las tres se saltaría una
 * de las tres cosas.
 *
 * El blob se escribe antes de saber si la tarea llegará a guardarse. Es deliberado
 * —la referencia tiene que poder insertarse ya— y lo que queda si el usuario cancela
 * es un huérfano, que es lo que recoge
 * [com.tasklane.data.attachment.AttachmentGc] pasado el periodo de gracia.
 */
internal class ImageInserter(
    private val project: Project,
    private val repo: RepoKey,
    private val editor: EditorEx,
    private val onAttached: (AttachmentId) -> Unit,
) {

    fun attach(image: Image) {
        val offset = editor.caretModel.offset
        val service = AttachmentService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val id = service.attach(repo, image)
            insertLater(id, offset)
        }
    }

    /**
     * Lo mismo desde un fichero, que desde la 2.3 **se guarda tal cual**: ni se
     * descodifica ni se reescala. También va al hilo de fondo: leer un fichero de varios
     * megas en el EDT congelaría el diálogo justo cuando el usuario acaba de soltarlo.
     */
    fun attachFile(file: File) {
        val offset = editor.caretModel.offset
        val service = AttachmentService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            insertLater(service.attachFile(repo, file.toPath()), offset)
        }
    }

    private fun insertLater(id: AttachmentId?, offset: Int) {
        ApplicationManager.getApplication().invokeLater(
            { if (id != null) insert(id, offset) },
            // El diálogo es modal: sin `any()` la inserción esperaría a que se
            // cerrara, que es exactamente cuando ya no sirve de nada.
            ModalityState.any(),
        )
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

    companion object {
        /**
         * Lo que se acepta soltar o elegir. Se guarda con su formato; que se pueda leer lo
         * comprueba el servicio por el contenido, no por la extensión.
         */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

        fun isImage(file: File): Boolean = file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS
    }
}
