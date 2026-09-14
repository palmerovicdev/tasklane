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
    /** Cuerpo completo, más las etiquetas y las rutas ancladas: lo que recorre el texto libre. */
    val haystack: String,
    val tags: List<String>,
    /** Las rutas de las anclas, normalizadas. Es lo que evalúa `file:`. */
    val files: List<String>,
) {
    companion object {
        fun of(task: Task): SearchDocument {
            val tags = task.tags.map(TextNormalizer::normalize)
            val files = task.anchors.map { TextNormalizer.normalize(it.path) }
            val body = TextNormalizer.normalize(task.body)
            return SearchDocument(
                title = TextNormalizer.normalize(task.title),
                // Las etiquetas y las rutas entran también en el texto libre: buscar
                // `api` debe encontrar una tarea etiquetada `api` aunque no lo diga el
                // cuerpo, y escribir `AuthService` debe encontrar lo que apunta a ese
                // fichero aunque la nota lo llame de otra manera.
                haystack = (listOf(body) + tags + files).joinToString(" "),
                tags = tags,
                files = files,
            )
        }
    }
}
