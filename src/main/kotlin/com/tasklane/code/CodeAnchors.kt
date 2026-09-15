package com.tasklane.code

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AnchorResolver
import com.tasklane.domain.model.CodeAnchor

/**
 * Las dos mitades del ancla de código: capturarla desde el IDE y volver a ella.
 *
 * Viven juntas porque son **la misma correspondencia leída en los dos sentidos** —de
 * `VirtualFile` a ruta relativa al proyecto y de vuelta—, y separarlas garantizaría que
 * alguna vez dejaran de coincidir. Es también la única frontera entre [CodeAnchor], que
 * es dominio puro, y la plataforma.
 */
object CodeAnchors {

    /**
     * Dónde está el cursor, si es que está en algún sitio anclable.
     *
     * Con editor, la línea del cursor y su texto. Sin editor pero con un fichero
     * seleccionado —el árbol del proyecto—, el fichero por su principio: apuntar a un
     * fichero entero también es apuntar a algo. Un directorio no, y un fichero sin
     * respaldo en disco tampoco.
     */
    fun capture(project: Project, e: AnActionEvent): CodeAnchor? {
        val file = anchorableFile(e) ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR)
        // La posición *lógica* y no el offset: la columna que interesa es la que se ve,
        // que es en la que se dibuja después la marca dentro del texto.
        val caret = editor?.caretModel?.logicalPosition
        val line = caret?.line ?: 0
        return CodeAnchor.of(
            path = pathOf(project, file),
            line = line,
            column = caret?.column ?: 0,
            text = editor?.let { lineText(it, line) }.orEmpty(),
        )
    }

    /**
     * Si hay algo que anclar, sin mirar el editor.
     *
     * Es lo que pregunta el `update` de la acción, que corre **fuera del EDT**: el
     * fichero llega ya resuelto en el `DataContext`, pero el cursor y el texto de la
     * línea son estado vivo del editor y no se leen desde otro hilo. Para decidir si la
     * entrada del menú se ve, con saber que hay fichero basta.
     */
    fun isAnchorable(e: AnActionEvent): Boolean = anchorableFile(e) != null

    private fun anchorableFile(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { !it.isDirectory && it.isInLocalFileSystem }

    /**
     * Lo que hay seleccionado en el editor, que es lo que se ofrece como cuerpo de la
     * tarea. Se recorta la sangría común: pegada dentro de una nota, la sangría
     * original del fichero sólo empuja el texto hacia la derecha.
     */
    fun selection(e: AnActionEvent): String {
        val text = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: return ""
        return text.trimIndent().trim()
    }

    /**
     * Abre el ancla. `false` == el fichero ya no está donde decía, y entonces se avisa:
     * un clic que no hace nada se lee como un fallo del plugin, no como un fichero que
     * alguien movió.
     *
     * La línea se recalcula con [AnchorResolver] contra el contenido de ahora. Se pide
     * el `Document` y no se lee el fichero a mano porque el `Document` es lo que está
     * viendo el usuario: con cambios sin guardar, el disco diría otra cosa.
     */
    fun open(project: Project, anchor: CodeAnchor): Boolean {
        val file = find(project, anchor)
        if (file == null) {
            notifyMissing(project, anchor)
            return false
        }
        val (line, column) = positionOf(anchor, file)
        OpenFileDescriptor(project, file, line, column).navigate(true)
        return true
    }

    /**
     * Dónde cae el ancla **ahora**, línea y columna.
     *
     * La línea la decide [AnchorResolver] contra el contenido de ahora; la columna es
     * la que se guardó, acotada a lo que mida esa línea. Acotar y no descartar: si la
     * línea se reescribió más corta, el principio de la línea buena sigue siendo el
     * sitio, y dejar el cursor pasado el final lo colocaría en un hueco virtual que el
     * usuario no escribió.
     *
     * **Dentro de una read action**, y la pide ella misma. Quien llega aquí es el clic
     * sobre la tarjeta, desde un `MouseListener` del EDT, y desde la 2026.x el EDT ya no
     * trae lectura implícita: `getDocument` lanzaba *Read access is allowed from inside
     * read-action only* y el clic no llevaba a ningún sitio. Pedirla aquí y no en quien
     * llama es lo que evita que el próximo que la use vuelva a olvidarse; anidada dentro
     * de otra no cuesta nada.
     */
    fun positionOf(anchor: CodeAnchor, file: VirtualFile): Pair<Int, Int> = ReadAction.compute<Pair<Int, Int>, RuntimeException> {
        // Un fichero binario o demasiado grande no da `Document`. Ahí no hay nada que
        // reencontrar y se va al número guardado, que es lo que se sabe.
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return@compute anchor.line to anchor.column
        val line = AnchorResolver.resolve(anchor, document.lineCount) { index ->
            document.getText(TextRange(document.getLineStartOffset(index), document.getLineEndOffset(index)))
        }
        val length = document.getLineEndOffset(line) - document.getLineStartOffset(line)
        line to anchor.column.coerceIn(0, length)
    }

    /**
     * Relativa a la raíz del proyecto siempre que se pueda, que es lo que hace que
     * mover el proyecto no rompa las anclas. Un fichero de fuera guarda su ruta
     * absoluta: es lo único que se puede guardar de él, y ya era igual de frágil.
     *
     * Es pública porque la correspondencia se lee **en los dos sentidos**: al capturar
     * un ancla y al preguntar «¿qué tareas apuntan al fichero que está abierto?», que
     * es lo que hace `AnchorMarkers`. Calcularla dos veces por su cuenta es justo lo
     * que garantizaría que alguna vez dejaran de coincidir.
     */
    fun pathOf(project: Project, file: VirtualFile): String {
        val base = project.basePath?.trimEnd('/') ?: return file.path
        val path = file.path
        return if (path.startsWith("$base/")) path.substring(base.length + 1) else path
    }

    // ------------------------------------------------------------------ internos

    private fun lineText(editor: Editor, line: Int): String {
        val document = editor.document
        if (line !in 0 until document.lineCount) return ""
        return document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    }

    private fun find(project: Project, anchor: CodeAnchor): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        val base = project.basePath?.trimEnd('/')
        // Relativa primero: es lo que se guarda salvo excepción, y buscar la absoluta
        // antes haría que una ruta como `src/Main.kt` pudiera resolverse contra el
        // directorio de trabajo del proceso, que no tiene nada que ver con el proyecto.
        val relative = base?.let { fs.findFileByPath("$it/${anchor.path}") }
        return relative ?: fs.findFileByPath(anchor.path)
    }

    private fun notifyMissing(project: Project, anchor: CodeAnchor) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(
                TasklaneBundle.message("notification.anchor.missing.title"),
                TasklaneBundle.message("notification.anchor.missing.content", anchor.path),
                NotificationType.WARNING,
            )
            .notify(project)
    }

    private const val GROUP = "Tasklane"
}
