package com.tasklane.ui.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.ActionPlan
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.editor.actionSystem.TypedActionHandlerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.tasklane.TasklaneBundle
import com.tasklane.domain.text.MarkdownTyping
import com.tasklane.domain.text.TypingResult

/**
 * Lo que engancha [MarkdownTyping] al editor del cuerpo de una tarea (2.32.0).
 *
 * La lógica vive allí, en Kotlin puro y probada; aquí sólo se decide si le toca y se aplica el
 * resultado. Le toca sólo al editor marcado con [BODY_EDITOR] y con un solo cursor: con varios,
 * una lista y una selección por cursor no tienen un resultado único que aplicar.
 */
internal object BodyEditorHandlers {

    val BODY_EDITOR: Key<Boolean> = Key.create("tasklane.body.editor")

    fun isBody(editor: Editor): Boolean =
        editor.getUserData(BODY_EDITOR) == true && editor.caretModel.caretCount == 1 && editor.document.isWritable

    fun apply(editor: Editor, result: TypingResult) {
        for (edit in result.steps()) {
            editor.document.replaceString(edit.start, edit.end, edit.text)
        }
        val caret = editor.caretModel.currentCaret
        caret.moveToOffset(result.selectionEnd)
        if (result.selectionStart == result.selectionEnd) {
            caret.removeSelection()
        } else {
            caret.setSelection(result.selectionStart, result.selectionEnd)
        }
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
    }
}

/**
 * `Enter` en el cuerpo de una tarea: sigue la lista o la acaba. Ver [MarkdownTyping.enter].
 *
 * Una lista de autocompletado abierta no llega aquí: su `Enter` es otra acción, *Choose Lookup
 * Item*, que la plataforma atiende antes que ésta.
 */
internal class ListEnterHandler(private val original: EditorActionHandler) : EditorActionHandler() {

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        original.isEnabled(editor, caret, dataContext)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val result = if (BodyEditorHandlers.isBody(editor)) {
            val current = editor.caretModel.currentCaret
            MarkdownTyping.enter(editor.document.text, current.selectionStart, current.selectionEnd)
        } else {
            null
        }
        if (result == null) {
            original.execute(editor, caret, dataContext)
            return
        }
        ApplicationManager.getApplication().runWriteAction { BodyEditorHandlers.apply(editor, result) }
    }
}

/**
 * Una marca tecleada con texto seleccionado la rodea en vez de sustituirla. Ver
 * [MarkdownTyping.typed].
 *
 * Al nivel del carácter y no como atajo de teclado porque `*` cae en una tecla distinta en cada
 * distribución: lo que todas tienen en común es el carácter que acaban escribiendo.
 *
 * **Es el manejador «crudo» de `TypedAction`, y sólo mientras el campo existe.** No hay punto de
 * extensión que sirva: `typedHandler` exige PSI, que el cuerpo no tiene, y `editorTypedHandler`
 * está deprecado para su retirada y obliga a reiniciar el IDE al instalar el plugin. Se quita al
 * cerrarse el diálogo; si para entonces otro plugin se ha puesto encima no se le puede sacar de
 * la cadena, así que se queda de paso, sin hacer nada.
 */
internal class SurroundTypedHandler private constructor() : TypedActionHandlerEx {

    private lateinit var original: TypedActionHandler
    private var active = true

    override fun execute(editor: Editor, charTyped: Char, dataContext: DataContext) {
        val result = resultFor(editor, charTyped)
        if (result == null) {
            original.execute(editor, charTyped, dataContext)
            return
        }
        WriteCommandAction.writeCommandAction(editor.project)
            .withName(TasklaneBundle.message("editor.format.command"))
            .run<RuntimeException> { BodyEditorHandlers.apply(editor, result) }
    }

    /** La plataforma pregunta esto antes de teclear, para adelantar el pintado de la letra. */
    override fun beforeExecute(editor: Editor, c: Char, context: DataContext, plan: ActionPlan) {
        if (resultFor(editor, c) == null) (original as? TypedActionHandlerEx)?.beforeExecute(editor, c, context, plan)
    }

    private fun resultFor(editor: Editor, char: Char): TypingResult? {
        if (!active || !BodyEditorHandlers.isBody(editor) || !editor.selectionModel.hasSelection()) return null
        val caret = editor.caretModel.currentCaret
        return MarkdownTyping.typed(editor.document.text, caret.selectionStart, caret.selectionEnd, char)
    }

    companion object {
        /** Lo deja puesto hasta que se libere [parent]. */
        fun install(parent: Disposable) {
            val action = TypedAction.getInstance()
            val handler = SurroundTypedHandler()
            handler.original = action.setupRawHandler(handler)
            Disposer.register(parent) {
                handler.active = false
                if (action.rawHandler === handler) action.setupRawHandler(handler.original)
            }
        }
    }
}
