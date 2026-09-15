package com.tasklane.ui.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.RepoKey
import java.awt.Image
import java.io.File

/**
 * El cuerpo de una tarea, editado en un editor de verdad del IDE.
 *
 * Por qué [EditorTextField] y no el `JBTextArea` que traía la Fase 1: el editor de la
 * plataforma trae gratis deshacer, selección múltiple, el keymap del usuario y —lo
 * que hace falta aquí— `InlayModel` y `FoldingModel`, que son las dos piezas sobre
 * las que se apoya la vista previa de las imágenes.
 *
 * **El resaltado de Markdown es opcional a propósito.** Si el plugin *Markdown* está
 * instalado se usa su tipo de fichero y el cuerpo sale coloreado; si no, texto plano.
 * No se declara ninguna dependencia sobre él porque el cuerpo de una tarea no es un
 * documento Markdown: es texto que *admite* marcas. Quien escriba en plano no ve
 * nunca una.
 */
internal class MarkdownField(
    private val project: Project,
    private val repo: RepoKey,
    initialText: String,
    private val parent: Disposable,
) {

    /**
     * `component` y no `field`: en Kotlin, `field` dentro de un accesor nombra el
     * campo de respaldo de la propiedad, así que un miembro con ese nombre sería
     * invisible desde el `get`/`set` de [text].
     */
    val component: EditorTextField = object : EditorTextField(
        EditorFactory.getInstance().createDocument(initialText),
        project,
        markdownOrPlainText(),
        false,
        false,
    ) {
        /**
         * `EditorTextField` crea y libera su editor al entrar y salir de la jerarquía
         * de componentes, así que todo lo que cuelga del editor —inlays, plegado, la
         * acción de pegar— se instala aquí y no en el constructor: en el constructor
         * todavía no hay editor al que engancharse.
         */
        override fun createEditor(): EditorEx = super.createEditor().also(::configure)
    }.apply {
        setOneLineMode(false)
        preferredSize = JBUI.size(WIDTH, HEIGHT)
    }

    /**
     * Quien adjunta imágenes. Es nulo hasta que la plataforma crea el editor —lo hace
     * al entrar el campo en la jerarquía de componentes—, así que los tres gestos que
     * adjuntan comprueban antes de disparar en vez de guardar una referencia temprana
     * que sería siempre la de un editor que ya no existe.
     */
    private var inserter: ImageInserter? = null

    var text: String
        get() = component.text
        set(value) {
            component.text = value
        }

    /**
     * Envuelve la selección entre [prefix] y [suffix] —o deja el cursor en medio si
     * no hay selección—, que es lo que hacen los botones de negrita, cursiva, código
     * y enlace.
     *
     * Va dentro de un [WriteCommandAction] con nombre propio por lo mismo que el
     * pegado de imágenes: sin él `⌘Z` no desharía el gesto, y un botón de formato que
     * no se puede deshacer es peor que no tenerlo.
     */
    fun wrapSelection(prefix: String, suffix: String = prefix) {
        val editor = component.editor ?: return
        edit(editor) { document ->
            val caret = editor.caretModel.currentCaret
            val from = caret.selectionStart
            val to = caret.selectionEnd
            document.insertString(to, suffix)
            document.insertString(from, prefix)
            // El cursor queda dentro de las marcas: escribir a continuación es lo
            // que espera quien pulsó el botón sin nada seleccionado.
            if (from == to) {
                caret.moveToOffset(from + prefix.length)
            } else {
                caret.setSelection(from + prefix.length, to + prefix.length)
            }
        }
    }

    /**
     * Antepone [marker] a cada línea tocada por la selección. [numbered] renumera
     * desde uno en vez de repetir el mismo marcador, que es lo que distingue una
     * lista ordenada de una de puntos.
     */
    fun prefixLines(marker: String, numbered: Boolean = false) {
        val editor = component.editor ?: return
        edit(editor) { document ->
            val caret = editor.caretModel.currentCaret
            val first = document.getLineNumber(caret.selectionStart)
            val last = document.getLineNumber(caret.selectionEnd)
            // De abajo arriba: insertar en una línea desplaza las de después, y
            // recorrer al revés deja intactos los offsets que quedan por usar.
            for (line in last downTo first) {
                val text = if (numbered) "${line - first + 1}. " else marker
                document.insertString(document.getLineStartOffset(line), text)
            }
        }
    }

    private fun edit(editor: Editor, block: (Document) -> Unit) {
        WriteCommandAction.writeCommandAction(project)
            .withName(TasklaneBundle.message("editor.format.command"))
            .run<RuntimeException> { block(editor.document) }
        component.requestFocusInWindow()
    }

    /** Adjunta una imagen ya decodificada —la del portapapeles— en el cursor. */
    fun attachImage(image: Image) {
        inserter?.attach(image)
    }

    /** Adjunta ficheros soltados en la zona de arrastre. */
    fun attachFiles(files: List<File>) {
        val inserter = inserter ?: return
        files.forEach(inserter::attachFile)
    }

    /**
     * Abre el selector de ficheros del IDE y adjunta lo que se elija.
     *
     * Se usa el del IDE y no un `JFileChooser` porque respeta el tema, recuerda la
     * última carpeta y en macOS sale nativo. El filtro por extensión es el mismo que
     * acepta el pegado: una sola lista de formatos.
     */
    fun chooseImage() {
        if (inserter == null) return
        // `withFileFilter` filtra con la misma lista que el pegado. `withExtensionFilter`,
        // que ya existe en el suelo (261), ocultaria ademas lo que no es imagen en el
        // selector nativo: cambiarlo seria un cambio de comportamiento, no de compatibilidad.
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle(TasklaneBundle.message("dialog.task.attach.choose"))
            .withFileFilter { file -> file.extension?.lowercase() in ImageInserter.IMAGE_EXTENSIONS }
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        attachFiles(listOf(File(chosen.path)))
    }

    private fun configure(editor: EditorEx) {
        editor.settings.apply {
            isUseSoftWraps = true
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isCaretRowShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        editor.setVerticalScrollbarVisible(true)
        editor.setBorder(JBUI.Borders.empty(2))

        // Ambos cuelgan del disposable del diálogo, así que se van con él. No hace
        // falta guardarlos: el editor es quien los tiene enganchados.
        val inlays = ImageInlays(project, repo, editor, parent)
        val inserter = ImageInserter(project, repo, editor) { inlays.invalidate(it) }
        this.inserter = inserter
        val paste = ImagePasteHandler(editor, inserter)
        // El atajo sale del keymap y no de una constante: quien haya movido «pegar»
        // espera que siga siendo el suyo también aquí.
        ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE)?.shortcutSet?.let {
            paste.registerCustomShortcutSet(it, editor.contentComponent, parent)
        }
    }

    /**
     * El tipo de fichero del plugin *Markdown* si está instalado; texto plano si no.
     * `UnknownFileType` es lo que devuelve la plataforma cuando nadie ha registrado la
     * extensión, y pasárselo al editor dejaría un campo que no se puede ni escribir.
     */
    private fun markdownOrPlainText(): FileType =
        FileTypeManager.getInstance().getFileTypeByExtension(MARKDOWN_EXTENSION)
            .takeUnless { it is UnknownFileType }
            ?: PlainTextFileType.INSTANCE

    private companion object {
        const val MARKDOWN_EXTENSION = "md"
        const val WIDTH = 520
        const val HEIGHT = 220
    }
}
