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
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AnchorReference
import com.tasklane.domain.model.AnchorResolver
import com.tasklane.domain.model.AnchorSnippet
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
     *
     * **Con una selección de varias líneas, el bloque entero** (2.15.0): de la primera
     * línea seleccionada a la última, y la columna donde empieza la selección. Ver
     * [selectedLines].
     */
    fun capture(project: Project, e: AnActionEvent): CodeAnchor? {
        val file = anchorableFile(e) ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR)
        val block = editor?.let(::selectedLines)
        if (editor != null && block != null) {
            val start = editor.offsetToLogicalPosition(editor.selectionModel.selectionStart)
            return CodeAnchor.of(
                path = pathOf(project, file),
                line = block.first,
                column = start.column,
                text = lineText(editor, block.first),
                span = block.last - block.first,
            )
        }
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
     * Las líneas que cubre la selección del editor, si abarca **más de una** (2.15.0).
     *
     * Una selección que acaba justo al principio de una línea no la cuenta: es lo que deja
     * seleccionar líneas enteras con `⇧↓` o arrastrando por el margen, y nadie piensa en
     * esa línea al hacerlo —está ahí porque la selección llega hasta su borde—.
     */
    fun selectedLines(editor: Editor): IntRange? {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return null
        val document = editor.document
        val first = document.getLineNumber(selection.selectionStart)
        var last = document.getLineNumber(selection.selectionEnd)
        if (last > first && selection.selectionEnd == document.getLineStartOffset(last)) last--
        return if (last > first) first..last else null
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
     *
     * Desde la 2.15.0 sólo lo usa una selección **dentro de una línea**: la de varias es
     * un bloque de código, se ancla entero y la tarjeta ya lo enseña. Ver [capture].
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
     *
     * `computeBlocking` y no `compute`, que la 261 ya marca deprecada: es la misma read
     * action síncrona, con nombre explícito. La alternativa, `nonBlocking`, se reinicia si
     * llega una escritura y está pensada para hilos de fondo, no para un clic en el EDT.
     */
    fun positionOf(anchor: CodeAnchor, file: VirtualFile): Pair<Int, Int> = ReadAction.computeBlocking<Pair<Int, Int>, RuntimeException> {
        // Un fichero binario o demasiado grande no da `Document`. Ahí no hay nada que
        // reencontrar y se va al número guardado, que es lo que se sabe.
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return@computeBlocking anchor.line to anchor.column
        val line = AnchorResolver.resolve(anchor, document.lineCount) { index ->
            document.getText(TextRange(document.getLineStartOffset(index), document.getLineEndOffset(index)))
        }
        val length = document.getLineEndOffset(line) - document.getLineStartOffset(line)
        line to anchor.column.coerceIn(0, length)
    }

    /**
     * En qué línea (0-based) está **hoy** lo anclado, o `null` si el fichero ya no está.
     *
     * Lo que ve el usuario al pulsar el ancla, sin abrir nada: lo piden las herramientas
     * MCP (2.12.0), que le dan al agente el sitio actual y no el que se guardó. Un agente
     * que abre `Auth.kt:42` cuando lo anclado ya bajó a la 57 lee otra cosa.
     */
    fun currentLine(project: Project, anchor: CodeAnchor): Int? {
        val file = find(project, anchor)?.takeUnless { it.isDirectory } ?: return null
        // Un agente edita el fichero **desde fuera** del IDE y pregunta enseguida: el
        // vigilante de disco tarda unos segundos en avisar, y hasta entonces el
        // `Document` es el de antes de la edición. Refrescar este fichero, y sólo éste,
        // es lo que hace que la respuesta hable del disco. Síncrono y fuera del EDT.
        VfsUtil.markDirtyAndRefresh(false, false, false, file)
        return if (file.isValid) positionOf(anchor, file).first else null
    }

    /**
     * El código del ancla tal como está ahora (2.15.0), o `null` si el fichero ya no está
     * o no tiene texto —un binario, uno enorme—. Del `Document` por lo mismo que
     * [positionOf]: con cambios sin guardar, es lo que el usuario tiene delante.
     *
     * **Bloqueante, y nunca desde el EDT**: el `Document` de un fichero que no está abierto
     * se lee del disco al pedirlo. Lo llama `CardSnippets` desde su hilo de fondo.
     */
    fun snippetOf(project: Project, anchor: CodeAnchor): AnchorSnippet? {
        val file = find(project, anchor)?.takeUnless { it.isDirectory } ?: return null
        return ReadAction.computeBlocking<AnchorSnippet?, RuntimeException> {
            if (!file.isValid) return@computeBlocking null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@computeBlocking null
            AnchorSnippet.of(anchor, document.lineCount) { index ->
                document.getText(TextRange(document.getLineStartOffset(index), document.getLineEndOffset(index)))
            }
        }
    }

    /**
     * Si una ruta anclada ya no lleva a ningún fichero (2.13.0): es lo que pinta el ancla
     * como rota y lo que busca `has:broken-anchor`. El mismo criterio que [open], para que
     * lo que la tarjeta avisa sea lo que el clic se va a encontrar.
     *
     * Sin refrescar: lo pregunta [AnchorFiles] al llegarle un evento del sistema de
     * ficheros, y a esas alturas el sistema virtual ya dice lo que hay.
     */
    fun isMissing(project: Project, path: String): Boolean =
        find(project, path)?.takeUnless { it.isDirectory } == null

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
    fun pathOf(project: Project, file: VirtualFile): String = CodeAnchor.pathOf(project.basePath, file.path)

    /** Lo que sale de [fromReference]: el ancla, o por qué no la hay. */
    sealed interface Lookup {
        data class Found(val anchor: CodeAnchor) : Lookup
        data class Missing(val path: String) : Lookup
        data class Folder(val path: String) : Lookup
        /** [line] es la que no está, 0-based: el principio, o el final de un rango. */
        data class OutOfRange(val path: String, val lines: Int, val line: Int) : Lookup
    }

    /**
     * Convierte una ruta escrita a mano en el mismo ancla que habría salido de poner el
     * cursor ahí: ruta relativa al proyecto, línea, columna **y el texto de la línea**.
     * Lo último es lo que importa: sin él, [AnchorResolver] no puede reencontrar la
     * línea cuando el fichero cambie, y el ancla escrita envejecería peor que la
     * capturada.
     *
     * Busca **refrescando** el sistema de ficheros virtual. El caso para el que existe
     * es un fichero que otro programa acaba de escribir —un plan que deja un agente, un
     * informe de un test— y que el IDE todavía no ha visto: sin refrescar, diría que no
     * existe un fichero que el usuario tiene delante en el Finder. Por eso se llama
     * desde el EDT y **fuera** de una read action, que un refresco síncrono no admite.
     *
     * Una línea más allá del final es un error y no se acota: quien escribe `:280` en
     * un fichero de 28 líneas se ha equivocado de fichero o de número, y colocar el
     * ancla en la última línea en silencio escondería el error hasta que alguien la
     * pulsara.
     */
    fun fromReference(project: Project, reference: AnchorReference): Lookup {
        val fs = LocalFileSystem.getInstance()
        val base = project.basePath?.trimEnd('/')
        val absolute = reference.path.startsWith("/") || WINDOWS_ROOT.containsMatchIn(reference.path)
        val file = if (absolute) {
            fs.refreshAndFindFileByPath(reference.path)
        } else {
            base?.let { fs.refreshAndFindFileByPath("$it/${reference.path}") }
        } ?: return Lookup.Missing(reference.path)
        if (file.isDirectory) return Lookup.Folder(reference.path)

        val path = pathOf(project, file)
        return ReadAction.computeBlocking<Lookup, RuntimeException> {
            // Sin `Document` —binario o enorme— no hay líneas que contar: vale apuntar
            // al fichero, pero no a una línea que no se puede comprobar.
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: return@computeBlocking if (reference.endLine == 0) {
                    Lookup.Found(CodeAnchor.of(path, 0))
                } else {
                    Lookup.OutOfRange(path, 0, reference.endLine)
                }
            val lines = maxOf(document.lineCount, 1)
            // El final de un rango, igual que una línea suelta: `:20-80` en un fichero de
            // 40 líneas es un número mal escrito, no «hasta donde llegue».
            if (reference.line >= lines) return@computeBlocking Lookup.OutOfRange(path, lines, reference.line)
            if (reference.endLine >= lines) return@computeBlocking Lookup.OutOfRange(path, lines, reference.endLine)
            val text = if (document.lineCount == 0) "" else document.getText(
                TextRange(document.getLineStartOffset(reference.line), document.getLineEndOffset(reference.line)),
            )
            Lookup.Found(
                CodeAnchor.of(path, reference.line, reference.column.coerceAtMost(text.length), text, reference.span),
            )
        }
    }

    // ------------------------------------------------------------------ internos

    private val WINDOWS_ROOT = Regex("""^[A-Za-z]:/""")

    private fun lineText(editor: Editor, line: Int): String {
        val document = editor.document
        if (line !in 0 until document.lineCount) return ""
        return document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    }

    private fun find(project: Project, anchor: CodeAnchor): VirtualFile? = find(project, anchor.path)

    private fun find(project: Project, path: String): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        val base = project.basePath?.trimEnd('/')
        // Relativa primero: es lo que se guarda salvo excepción, y buscar la absoluta
        // antes haría que una ruta como `src/Main.kt` pudiera resolverse contra el
        // directorio de trabajo del proceso, que no tiene nada que ver con el proyecto.
        val relative = base?.let { fs.findFileByPath("$it/$path") }
        return relative ?: fs.findFileByPath(path)
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
