package com.tasklane.domain.text

import com.tasklane.domain.model.Task

/**
 * Texto de una tarea para copiarla fuera del IDE.
 *
 * No es una exportación de tareas: no añade casillas, guiones, etiquetas ni
 * metadatos. Parte del cuerpo completo y sólo quita lo que pertenece a la sintaxis
 * de Markdown-lite o al almacenamiento de imágenes del plugin.
 */
object TaskCopyText {

    fun plain(task: Task): String {
        val body = task.body
        if (body.isEmpty()) return ""

        val plain = stripMarkup(task)
        val sourceLines = body.lines()
        val plainLines = plain.lines()

        return plainLines
            .mapIndexedNotNull { index, line ->
                val source = sourceLines.getOrNull(index).orEmpty()
                // Una referencia de imagen sola no deja una línea vacía en la copia.
                if (source.isNotBlank() && line.isBlank()) null else line.trimEnd()
            }
            .dropWhile(String::isEmpty)
            .dropLastWhile(String::isEmpty)
            .joinToString("\n")
    }

    /** Sustituye enlaces Markdown y referencias de imagen antes de quitar el énfasis. */
    private fun stripMarkup(task: Task): String {
        val body = task.body
        val replacements = buildList {
            ImageRefParser.parse(body).forEach { add(Replacement(it.range, "")) }
            LinkExtractor.extract(body)
                .filter { body.getOrNull(it.range.first) == '[' }
                .forEach { add(Replacement(it.range, it.display)) }
        }.sortedBy { it.range.first }

        val withoutLinksAndImages = if (replacements.isEmpty()) {
            body
        } else {
            buildString {
                var cursor = 0
                for (replacement in replacements) {
                    if (replacement.range.first < cursor) continue
                    append(body, cursor, replacement.range.first)
                    append(replacement.text)
                    cursor = replacement.range.last + 1
                }
                append(body, cursor, body.length)
            }
        }
        return InlineMarkdown.strip(withoutLinksAndImages)
    }

    private data class Replacement(val range: IntRange, val text: String)
}
