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

        // El código entre vallas se copia literal y sin las vallas (2.8.0): quitarle el
        // «Markdown» se comería los `*` de un puntero o las `` ` `` de una plantilla.
        // Lo demás se copia por tramos de prosa, como antes.
        val lines = body.lines()
        val roles = CodeFence.roles(lines)
        val out = mutableListOf<String>()
        var start = 0
        while (start < lines.size) {
            val role = roles[start]
            var end = start + 1
            while (end < lines.size && roles[end] == role) end++
            val chunk = lines.subList(start, end)
            when (role) {
                CodeFence.Role.PROSE -> out += plainProse(chunk.joinToString("\n"))
                CodeFence.Role.CODE -> out += chunk.map(String::trimEnd)
                CodeFence.Role.FENCE -> Unit
            }
            start = end
        }
        return out
            .dropWhile(String::isEmpty)
            .dropLastWhile(String::isEmpty)
            .joinToString("\n")
    }

    private fun plainProse(prose: String): List<String> {
        val plain = stripMarkup(prose)
        val sourceLines = prose.lines()
        return plain.lines().mapIndexedNotNull { index, line ->
            val source = sourceLines.getOrNull(index).orEmpty()
            // Una referencia de imagen sola no deja una línea vacía en la copia.
            if (source.isNotBlank() && line.isBlank()) null else line.trimEnd()
        }
    }

    /** Sustituye enlaces Markdown y referencias de imagen antes de quitar el énfasis. */
    private fun stripMarkup(body: String): String {
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
