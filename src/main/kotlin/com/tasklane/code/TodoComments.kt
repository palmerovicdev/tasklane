package com.tasklane.code

import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.PsiTodoSearchHelper
import com.intellij.psi.search.TodoItem
import com.intellij.psi.util.PsiTreeUtil
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.text.TodoText

/**
 * Los comentarios `TODO` del código, leídos para convertirlos en tareas (2.8.0).
 *
 * **Se usa el índice de TODO del IDE**, no una búsqueda propia: es el mismo que llena la
 * ventana *TODO*, con los patrones que el usuario tenga configurados —`FIXME`, `HACK`,
 * los suyos—, y ya sabe qué es un comentario en cada lenguaje. Una regex nuestra
 * encontraría `TODO` dentro de cadenas y se perdería los patrones propios.
 *
 * Todo lo que lee va **dentro de una read action que pide quien llama**, salvo [all],
 * que corre en segundo plano y se las pide fichero a fichero para no bloquear las
 * escrituras del IDE durante el barrido entero.
 */
object TodoComments {

    /** Un TODO encontrado: dónde está, qué dice y qué habría que borrar para quitarlo. */
    class Found(
        val file: VirtualFile,
        /** Ruta como la guarda [CodeAnchor]: relativa al proyecto. */
        val path: String,
        /** Línea 0-based donde empieza el TODO. */
        val line: Int,
        val column: Int,
        /** El texto de esa línea, para el ancla y para reconocer lo ya importado. */
        val lineText: String,
        /** La tarea: el TODO sin la palabra clave. Ver [TodoText.clean]. */
        val text: String,
        /** `todo`, `fixme`…: la etiqueta. */
        val keyword: String,
        /**
         * Lo que hay que quitar del documento para quitar el comentario, de atrás
         * adelante. Vacío == el comentario lleva algo más que el TODO y no se toca.
         */
        val removal: List<TextRange>,
        /** El documento tal y como se leyó. Si cambia antes de borrar, no se borra. */
        val stamp: Long,
    ) {
        val removable: Boolean get() = removal.isNotEmpty()

        /** El ancla del TODO en su sitio, sin borrar nada. */
        val anchor: CodeAnchor get() = CodeAnchor.of(path, line, column, lineText)
    }

    /** El TODO de la línea del cursor, si lo hay. Dentro de una read action. */
    fun at(project: Project, file: PsiFile, offset: Int): Found? {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        if (offset !in 0..document.textLength) return null
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val items = PsiTodoSearchHelper.getInstance(project).findTodoItemsLight(file, start, end)
        // El que empieza en la línea; si no, uno que la cruce —la segunda línea de un
        // TODO partido en dos—.
        val item = items.firstOrNull { it.textRange.startOffset in start..end }
            ?: items.firstOrNull { item -> item.additionalTextRanges.any { it.startOffset in start..end } }
            ?: return null
        return found(project, file, document, item)
    }

    /**
     * Todos los TODO del contenido del proyecto, en orden de fichero y de línea.
     *
     * En segundo plano y **esperando a que acabe la indexación**: el índice de TODO no
     * se puede leer mientras se construye, y un barrido a medias diría que no hay los
     * que sí hay.
     */
    fun all(project: Project, indicator: ProgressIndicator): List<Found> {
        val helper = PsiTodoSearchHelper.getInstance(project)
        val fileIndex = ProjectFileIndex.getInstance(project)
        val files = smartRead<List<VirtualFile>>(project, indicator) {
            val out = ArrayList<VirtualFile>()
            helper.processFilesWithTodoItems { psi ->
                psi.virtualFile?.takeIf { it.isInLocalFileSystem && fileIndex.isInContent(it) }?.let(out::add)
                true
            }
            out.sortedBy { it.path }
        }
        val found = ArrayList<Found>()
        files.forEachIndexed { index, file ->
            indicator.checkCanceled()
            indicator.fraction = index.toDouble() / files.size.coerceAtLeast(1)
            indicator.text2 = file.name
            found += smartRead<List<Found>>(project, indicator) {
                val psi = file.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) }
                    ?: return@smartRead emptyList()
                val document = PsiDocumentManager.getInstance(project).getDocument(psi)
                    ?: return@smartRead emptyList()
                helper.findTodoItemsLight(psi)
                    .sortedBy { it.textRange.startOffset }
                    .mapNotNull { found(project, psi, document, it) }
            }
        }
        return found
    }

    /**
     * Una read action que **espera a que acabe la indexación** y cede ante las escrituras
     * del IDE: si una llega a mitad, se aparta y [read] vuelve a empezar, así que no puede
     * tener efectos. Es lo que hacía `DumbService.runReadActionInSmartMode`, deprecada
     * porque no garantiza nada dentro de otra read action.
     */
    private fun <T> smartRead(project: Project, indicator: ProgressIndicator, read: () -> T): T =
        ReadAction.nonBlocking<T> { read() }
            .inSmartMode(project)
            .wrapProgress(indicator)
            .executeSynchronously()

    /**
     * Quita el comentario de [found] del documento y devuelve el ancla que le toca a la
     * tarea **después**: la línea de código que el comentario tenía debajo —o el resto
     * de su línea, si iba detrás de código—, con su texto de ahora.
     *
     * Dentro de una write action que pide quien llama. `null` == el documento cambió
     * desde que se leyó y no se ha borrado nada: borrar por offsets viejos se llevaría
     * otra cosa.
     */
    fun remove(document: Document, found: Found): CodeAnchor? {
        if (!found.removable || document.modificationStamp != found.stamp) return null
        val first = found.removal.minOf { it.startOffset }
        var line = document.getLineNumber(first)
        for (range in found.removal.sortedByDescending { it.startOffset }) {
            document.deleteString(range.startOffset, range.endOffset)
        }
        if (document.lineCount == 0) return CodeAnchor.of(found.path, 0)
        line = line.coerceAtMost(document.lineCount - 1)
        // Si debajo del comentario había una línea en blanco, el ancla va a la
        // siguiente con algo: apuntar a un hueco no dice de qué habla la tarea.
        var target = line
        while (target < document.lineCount - 1 && target - line < LOOKAHEAD && textOf(document, target).isBlank()) target++
        if (textOf(document, target).isBlank()) target = line
        val text = textOf(document, target)
        return CodeAnchor.of(found.path, target, text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0), text)
    }

    // ------------------------------------------------------------------ internos

    private fun found(project: Project, file: PsiFile, document: Document, item: TodoItem): Found? {
        val vf = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        val ranges = listOf(item.textRange) + item.additionalTextRanges
        val parts = ranges.map { document.getText(it) }
        val text = TodoText.clean(parts)
        if (text.isBlank()) return null
        val start = item.textRange.startOffset
        val line = document.getLineNumber(start)
        return Found(
            file = vf,
            path = CodeAnchors.pathOf(project, vf),
            line = line,
            column = start - document.getLineStartOffset(line),
            lineText = textOf(document, line),
            text = text,
            keyword = TodoText.keyword(parts.first()),
            removal = removalOf(file, document, ranges),
            stamp = document.modificationStamp,
        )
    }

    /**
     * Qué borrar para quitar el TODO, o nada si el comentario lleva más cosas. Cada
     * tramo del TODO tiene que caer en un comentario, y cada comentario tocado tiene
     * que ser **sólo** TODO: ver [TodoText.isOnlyTodo].
     */
    private fun removalOf(file: PsiFile, document: Document, ranges: List<TextRange>): List<TextRange> {
        val comments = ranges.map { range ->
            PsiTreeUtil.getParentOfType(file.findElementAt(range.startOffset), PsiComment::class.java, false)
                ?: return emptyList()
        }.distinct()
        for (comment in comments) {
            val box = comment.textRange
            val inside = ranges.filter { box.contains(it) }.map { (it.startOffset - box.startOffset) until (it.endOffset - box.startOffset) }
            if (!TodoText.isOnlyTodo(comment.text, inside, markersOf(comment))) return emptyList()
        }
        return comments.map { lineAware(document, it.textRange) }
    }

    private fun markersOf(comment: PsiComment): List<String> {
        val commenter = LanguageCommenters.INSTANCE.forLanguage(comment.language) ?: return emptyList()
        return buildList {
            add(commenter.lineCommentPrefix)
            add(commenter.blockCommentPrefix)
            add(commenter.blockCommentSuffix)
            if (commenter is CodeDocumentationAwareCommenter) {
                add(commenter.documentationCommentPrefix)
                add(commenter.documentationCommentLinePrefix)
                add(commenter.documentationCommentSuffix)
            }
        }.filterNotNull()
    }

    /**
     * El tramo de un comentario ampliado a lo que quedaría feo: si ocupa sus líneas
     * enteras, las líneas con su salto; si va detrás de código, el espacio que lo
     * separaba. Un comentario en medio de una línea se quita tal cual.
     */
    private fun lineAware(document: Document, range: TextRange): TextRange {
        val chars = document.charsSequence
        val firstLine = document.getLineNumber(range.startOffset)
        val lastLine = document.getLineNumber(range.endOffset)
        val lineStart = document.getLineStartOffset(firstLine)
        val lineEnd = document.getLineEndOffset(lastLine)
        val before = chars.subSequence(lineStart, range.startOffset)
        val after = chars.subSequence(range.endOffset, lineEnd)
        if (before.isBlank() && after.isBlank()) {
            return when {
                lastLine + 1 < document.lineCount -> TextRange(lineStart, document.getLineStartOffset(lastLine + 1))
                lineStart > 0 -> TextRange(lineStart - 1, lineEnd)
                else -> TextRange(lineStart, lineEnd)
            }
        }
        if (after.isBlank()) {
            var from = range.startOffset
            while (from > lineStart && (chars[from - 1] == ' ' || chars[from - 1] == '\t')) from--
            return TextRange(from, lineEnd)
        }
        return range
    }

    private fun textOf(document: Document, line: Int): String =
        document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))

    /** Cuántas líneas en blanco se saltan como mucho buscando el código de debajo. */
    private const val LOOKAHEAD = 3
}
