package com.tasklane.search

import com.tasklane.domain.model.Task
import com.tasklane.domain.text.TextNormalizer

/**
 * La forma buscable de una tarea: su texto ya en minúsculas y sin diacríticos.
 *
 * Existe para que normalizar se pague **una vez por edición** y no una vez por
 * pulsación de tecla. El título va aparte del cuerpo porque una coincidencia en el
 * título vale más que una enterrada en el detalle, y eso es lo que ordena los
 * resultados.
 */
internal class SearchDocument(
    val title: String,
    /** Cuerpo completo más las etiquetas: lo que recorre el texto libre. */
    val haystack: String,
    val tags: List<String>,
) {
    companion object {
        fun of(task: Task): SearchDocument {
            val tags = task.tags.map(TextNormalizer::normalize)
            val body = TextNormalizer.normalize(task.body)
            return SearchDocument(
                title = TextNormalizer.normalize(task.title),
                // Las etiquetas entran también en el texto libre: buscar `api` debe
                // encontrar una tarea etiquetada `api` aunque no lo diga el cuerpo.
                haystack = if (tags.isEmpty()) body else tags.joinToString(" ", prefix = "$body "),
                tags = tags,
            )
        }
    }
}
