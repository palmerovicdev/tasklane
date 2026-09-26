package com.tasklane.code

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.tasklane.domain.model.AnchorMarkerStyle
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.service.TaskService
import com.tasklane.ui.common.FirstStepTips
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Point

/**
 * El *Got It* de la primera ancla (P35), en el editor y debajo de la línea anclada: que la
 * línea lleva ahora una marca, qué hace la marca y que el ancla sigue al código.
 *
 * **Sale donde se está mirando o no sale.** Una tarea anclada desde el editor —*from Here*,
 * `Alt+Enter` sobre un `TODO`— tiene su fichero delante; una anclada desde el diálogo, al
 * soltar un fichero en la lista o al importar los `TODO`, sólo si su fichero es el del editor
 * activo y la línea se ve. Si no, se espera a la siguiente: abrir un fichero o desplazar el
 * editor para enseñar una pista sería mover a alguien de donde estaba.
 *
 * Con las marcas apagadas no hay nada que señalar, y no sale.
 */
internal class AnchorGotIt(
    private val project: Project,
    private val parent: Disposable,
    private val style: () -> AnchorMarkerStyle,
) {

    /** Escucha hasta que se diga *Got It*; si ya se dijo, ni empieza. */
    fun start(scope: CoroutineScope) {
        if (!FirstStepTips.canShow(FirstStepTips.ANCHOR)) return
        scope.launch {
            TaskService.getInstance(project).added
                .filter { it.anchors.isNotEmpty() }
                .takeWhile { withContext(Dispatchers.EDT) { FirstStepTips.canShow(FirstStepTips.ANCHOR) } }
                // Sin modalidad a propósito: con el diálogo de la tarea aún abierto el editor
                // no se ve, y lo que toca es esperar a que se cierre.
                .collect { additions -> withContext(Dispatchers.EDT) { show(additions.anchors) } }
        }
    }

    private fun show(anchors: List<CodeAnchor>) {
        if (style() == AnchorMarkerStyle.OFF) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        if (editor.isDisposed || editor.editorKind != EditorKind.MAIN_EDITOR) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val path = CodeAnchors.pathOf(project, file)
        val anchor = anchors.firstOrNull { it.path == path } ?: return
        FirstStepTips.show(FirstStepTips.ANCHOR, project, parent, editor.contentComponent) { pointAt(editor, anchor) }
    }

    /**
     * Debajo de la línea: al principio del texto con la marca en el margen, y en la columna
     * anclada con la pastilla, que es donde está. La línea es la que se acaba de capturar, así
     * que no hace falta volver a resolverla.
     */
    private fun pointAt(editor: Editor, anchor: CodeAnchor): Point? {
        if (editor.isDisposed) return null
        val document = editor.document
        if (document.lineCount <= 0) return null
        val line = anchor.line.coerceIn(0, document.lineCount - 1)
        val width = document.getLineEndOffset(line) - document.getLineStartOffset(line)
        val column = if (style() == AnchorMarkerStyle.INLINE) anchor.column.coerceIn(0, width) else 0
        val xy = editor.logicalPositionToXY(LogicalPosition(line, column))
        return Point(xy.x, xy.y + editor.lineHeight)
    }
}
