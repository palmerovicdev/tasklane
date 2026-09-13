package com.tasklane.ui.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.EditorEx

/**
 * Pegar una imagen en el editor de una tarea.
 *
 * Se engancha al atajo de pegar **del keymap del usuario** en vez de a `⌘V` a pelo, y
 * cuando lo que hay en el portapapeles no es una imagen delega en el pegado normal de
 * la plataforma. Es lo que hace que el atajo siga siendo uno solo: el usuario pega, y
 * el plugin decide si eso era texto o una captura.
 *
 * Aquí sólo se decide **si lo del portapapeles es una imagen**; guardarla e
 * insertarla es cosa de [ImageInserter], que es el camino común de los tres gestos
 * que adjuntan.
 */
internal class ImagePasteHandler(
    private val editor: EditorEx,
    private val inserter: ImageInserter,
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
        event.presentation.isEnabled = ClipboardImage.available()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val image = ClipboardImage.read()
        if (image == null) {
            delegateToPlatform(event)
            return
        }
        inserter.attach(image)
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

}
