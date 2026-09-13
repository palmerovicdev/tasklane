package com.tasklane.domain.export

import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser

/**
 * Colección de tareas → texto de portapapeles.
 *
 * Vive en `domain` y no en un servicio porque no necesita nada del IDE: es una
 * función de `(tareas, configuración, formato)` a `String`, y por eso el formato de
 * salida se puede fijar con un test en vez de comprobándolo a ojo tras copiar.
 *
 * Lo que sale es **lo que se ve**: el mismo orden de la pestaña, los mismos grupos y
 * sólo lo que la búsqueda haya dejado pasar. Quien llama ya ha hecho ese trabajo
 * para pintar el árbol; aquí sólo se le da formato.
 */
object TaskExporter {

    /** Un bloque del texto: una cabecera opcional y sus tareas, ya ordenadas. */
    data class Section(val heading: String?, val tasks: List<Task>)

    private const val INDENT = "  "

    fun export(sections: List<Section>, config: TasklaneConfig, format: ExportFormat): String {
        val blocks = sections
            .filter { it.tasks.isNotEmpty() }
            .map { section -> render(section, config, format) }

        // Sin tareas no se devuelve una cabecera huérfana: quien llama distingue
        // «no había nada» de «aquí tienes» mirando si la cadena está vacía.
        if (blocks.isEmpty()) return ""
        return blocks.joinToString(separator = "\n\n", postfix = "\n")
    }

    fun export(heading: String?, tasks: List<Task>, config: TasklaneConfig, format: ExportFormat): String =
        export(listOf(Section(heading, tasks)), config, format)

    private fun render(section: Section, config: TasklaneConfig, format: ExportFormat): String = buildString {
        section.heading?.takeIf { it.isNotBlank() }?.let { heading ->
            appendLine(if (format == ExportFormat.MARKDOWN) "## $heading" else heading)
            appendLine()
        }
        section.tasks.forEach { appendTask(it, config, format) }
    }.trimEnd('\n')

    private fun StringBuilder.appendTask(task: Task, config: TasklaneConfig, format: ExportFormat) {
        val done = config.stateOrDefault(task.stateId).terminal
        append(
            when (format) {
                ExportFormat.MARKDOWN -> if (done) "- [x] " else "- [ ] "
                ExportFormat.PLAIN -> "- "
            },
        )
        append(task.title)
        // Las etiquetas van en la misma línea y no en una propia: `#api` detrás del
        // título es como se escriben, y así el texto pegado se puede volver a leer.
        task.tags.forEach { append(" #").append(it) }
        appendLine()

        // El detalle se sangra bajo su tarea. Es lo que hace que una lista pegada en
        // Markdown siga siendo una lista y no un montón de párrafos sueltos.
        for (line in detailOf(task)) {
            if (line.isEmpty()) appendLine() else appendLine("$INDENT$line")
        }
    }

    /**
     * El cuerpo menos el título, sin las líneas en blanco de los extremos. Las de en
     * medio se conservan: separan párrafos que el usuario escribió aparte.
     *
     * Las referencias a imágenes se quitan. Fuera del IDE un `![](tasklane:<sha>)` no
     * es una imagen ni un enlace: es una cadena de 64 caracteres que nadie puede
     * resolver, y lo que se exporta se pega en un ticket o en un correo. Una línea
     * que sólo contenía la referencia desaparece con ella en vez de quedarse en
     * blanco; una que la llevaba junto a texto conserva el texto.
     */
    private fun detailOf(task: Task): List<String> {
        val title = task.titleRange
        if (title.isEmpty()) return emptyList()
        val eol = task.body.indexOf('\n', title.last + 1)
        if (eol < 0) return emptyList()
        val detail = task.body.substring(eol + 1)
        return detail
            .lines()
            .mapNotNull { line ->
                val stripped = ImageRefParser.strip(line)
                if (stripped == line) line else stripped.trim().takeIf { it.isNotEmpty() }
            }
            .map { it.trimEnd() }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
    }
}
