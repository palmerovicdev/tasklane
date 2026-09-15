package com.tasklane.data.sqlite

import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Escribe un `tasks.xml` **en streaming**, con memoria constante.
 *
 * Es la otra mitad de [TasksXmlReader], y existe por la misma razón al revés:
 * `TaskFileStore.write` construye el árbol de JDOM entero, lo pasa a un `String` y el
 * `String` a bytes —tres copias del fichero vivas a la vez—, y antes de eso necesita la
 * lista con todas las tareas. A un millón son 12,9 GB de `Task` más 2,9 GB por cada copia
 * del texto. Aquí las tareas llegan a tandas y salen a disco según llegan.
 *
 * **Escribe exactamente el formato de [TasksCodec]**: los mismos atributos, en el mismo
 * orden, sólo cuando dicen algo; los que escribió una versión futura —`Task.extra`—
 * vuelven a salir tal cual; las anclas como hijos y el cuerpo al final.
 * `TasksXmlWriterTest` lo fija leyendo lo escrito con los **dos** lectores, el de JDOM y el
 * de StAX: una exportación que no se pueda volver a importar no es una exportación.
 *
 * **A mano y no con `XMLStreamWriter`**, que era lo obvio, porque el de la JDK no escapa
 * lo que un lector de XML **normaliza** al leer: un `\r` dentro del texto vuelve como
 * `\n`, y un salto de línea dentro de un atributo vuelve como un espacio. Un cuerpo
 * escrito en Windows perdería sus `\r\n` en el viaje de ida y vuelta sin que nada
 * fallara. Aquí van como referencias de carácter, que es lo que hace JDOM.
 *
 * **Lo único que no puede escribir tal cual son los caracteres que XML 1.0 prohíbe.** Un
 * cuerpo con la salida de una terminal pegada trae `ESC[31m` y compañía, y ese `ESC` no
 * cabe en ningún documento XML, ni escapado. El códec de JDOM se negaba —la exportación
 * fallaba entera por una tarea—; aquí se cambia cada uno por `U+FFFD`, se cuentan en
 * [replaced] y quien exporta lo dice. Perder un carácter de control es mucho mejor que
 * perder la exportación de un millón de tareas.
 */
internal class TasksXmlWriter(output: OutputStream, repo: RepoKey) : AutoCloseable {

    private val out = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8), 1 shl 16)

    /** Cuántas tareas se han escrito. */
    var written: Int = 0
        private set

    /** Cuántos caracteres prohibidos por XML 1.0 se han cambiado por `U+FFFD`. */
    var replaced: Int = 0
        private set

    private var finished = false

    init {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write("<tasks")
        attribute("version", TasksCodec.CURRENT_VERSION.toString())
        attribute("repoKey", repo.value)
        out.write(">\n")
    }

    fun write(tasks: List<Task>) = tasks.forEach(::write)

    fun write(task: Task) {
        out.write("  <task")
        attribute("id", task.id.value)
        attribute("state", task.stateId.value)
        attribute("priority", task.priorityId.value)
        attribute("order", task.order.toString())
        attribute("createdAt", task.createdAt.toEpochMilli().toString())
        attribute("updatedAt", task.updatedAt.toEpochMilli().toString())
        task.completedAt?.let { attribute("completedAt", it.toEpochMilli().toString()) }
        if (task.tags.isNotEmpty()) attribute("tags", task.tags.joinToString(","))
        task.dueDate?.let { attribute("dueDate", it.toEpochMilli().toString()) }
        // Sólo cuando es cierto, igual que el códec: ver `TasksCodec.encode`.
        if (task.bookmarked) attribute("bookmarked", "true")
        for ((k, v) in task.extra) if (k !in TasksCodec.KNOWN_ATTRS && isName(k)) attribute(k, v)
        out.write(">\n")

        for (anchor in task.anchors) {
            out.write("    <anchor")
            attribute("path", anchor.path)
            attribute("line", anchor.line.toString())
            if (anchor.column > 0) attribute("column", anchor.column.toString())
            if (anchor.text.isNotEmpty()) attribute("text", anchor.text)
            out.write(" />\n")
        }

        out.write("    <body>")
        escape(task.body, attribute = false)
        out.write("</body>\n  </task>\n")
        written++
    }

    /**
     * Cierra el documento y vacía el búfer. **No** fuerza a disco: eso es cosa de quien
     * tiene el fichero, que es quien sabe si hay un `fsync` que pedir.
     */
    fun finish() {
        if (finished) return
        finished = true
        out.write("</tasks>\n")
        out.flush()
    }

    override fun close() {
        out.close()
    }

    private fun attribute(name: String, value: String) {
        out.write(" ")
        out.write(name)
        out.write("=\"")
        escape(value, attribute = true)
        out.write("\"")
    }

    /**
     * El texto escapado, carácter a carácter.
     *
     * Los tres de siempre —`&`, `<`, `>`— y, en un atributo, la comilla. `\r` va siempre
     * como referencia y `\n` y `\t` también dentro de un atributo: son los que un lector
     * normalizaría. Los pares sustitutos bien formados se conservan —un emoji es un
     * carácter válido—; uno suelto, no.
     */
    private fun escape(text: String, attribute: Boolean) {
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            when {
                ch == '&' -> out.write("&amp;")
                ch == '<' -> out.write("&lt;")
                ch == '>' -> out.write("&gt;")
                ch == '"' && attribute -> out.write("&quot;")
                ch == '\r' -> out.write("&#xD;")
                ch == '\n' -> if (attribute) out.write("&#xA;") else out.write('\n'.code)
                ch == '\t' -> if (attribute) out.write("&#x9;") else out.write('\t'.code)
                Character.isHighSurrogate(ch) && i + 1 < n && Character.isLowSurrogate(text[i + 1]) -> {
                    out.write(ch.code)
                    out.write(text[i + 1].code)
                    i++
                }
                ch < ' ' || Character.isSurrogate(ch) || ch == '￾' || ch == '￿' -> {
                    out.write('�'.code)
                    replaced++
                }
                else -> out.write(ch.code)
            }
            i++
        }
    }

    /**
     * Un atributo de una versión futura que no es un nombre XML no se puede escribir: el
     * fichero entero dejaría de leerse. No puede venir de un `tasks.xml` —ahí ya era un
     * nombre— y sólo llegaría de una base escrita a mano. Se descarta ése, no la tarea.
     */
    private fun isName(name: String): Boolean =
        name.isNotEmpty() && (name[0].isLetter() || name[0] == '_') &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
}
