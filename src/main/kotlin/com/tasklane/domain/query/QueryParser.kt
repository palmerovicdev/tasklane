package com.tasklane.domain.query

import com.tasklane.domain.text.TextNormalizer

/**
 * Convierte lo que hay escrito en el campo de búsqueda en una [TaskQuery].
 *
 * El contrato que gobierna todas las decisiones de aquí es que **esto se ejecuta
 * mientras el usuario teclea**: una consulta a medio escribir no puede vaciar la
 * lista de golpe ni hacer desaparecer lo que el usuario está buscando.
 *
 * De ahí las tres reglas menos obvias:
 *
 * - Un operador sin valor (`state:`, justo antes de escribir el nombre) se **ignora**
 *   en vez de filtrar por la cadena vacía.
 * - Un prefijo desconocido (`https:`, `TODO:`) se trata como texto libre, con los dos
 *   puntos incluidos. Pegar una URL en el campo busca la URL, que es lo que se
 *   esperaba; no un filtro por un operador que no existe.
 * - Un valor con espacios se entrecomilla —`state:"In Review"`— y las comillas
 *   pueden abrirse en cualquier punto del token.
 */
object QueryParser {

    fun parse(raw: String): TaskQuery {
        val terms = mutableListOf<String>()
        val states = mutableSetOf<String>()
        val priorities = mutableSetOf<String>()
        val repos = mutableSetOf<String>()
        val tags = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        val has = mutableSetOf<TaskQuery.Facet>()
        var done: Boolean? = null

        for (token in tokenize(raw)) {
            if (token.isEmpty()) continue

            if (token.startsWith(TAG) && token.length > TAG.length) {
                tags += TextNormalizer.normalize(token.removePrefix(TAG))
                continue
            }

            val colon = token.indexOf(':')
            val key = if (colon > 0) token.take(colon).lowercase() else null
            val value = if (colon > 0) TextNormalizer.normalize(token.substring(colon + 1)) else ""

            // Un operador conocido pero todavía sin valor no filtra: el usuario está
            // a mitad de escribirlo y la lista no debe parpadear.
            if (key != null && value.isEmpty() && key in KEYS) continue

            when (key) {
                "state" -> states += value
                "p", "priority" -> priorities += value
                "repo" -> repos += value
                "file" -> files += value
                "is" -> when (value) {
                    "done", "closed" -> done = true
                    "open", "todo" -> done = false
                    else -> terms += TextNormalizer.normalize(token)
                }

                "has" -> when (value) {
                    "link", "links" -> has += TaskQuery.Facet.LINK
                    "image", "images" -> has += TaskQuery.Facet.IMAGE
                    "code", "anchor" -> has += TaskQuery.Facet.CODE
                    "broken-anchor", "broken-anchors", "broken" -> has += TaskQuery.Facet.BROKEN_ANCHOR
                    else -> terms += TextNormalizer.normalize(token)
                }

                else -> terms += TextNormalizer.normalize(token)
            }
        }

        return TaskQuery(
            terms = terms.filter(String::isNotEmpty),
            states = states,
            priorities = priorities,
            repos = repos,
            tags = tags,
            files = files,
            done = done,
            has = has,
        )
    }

    /**
     * Parte por espacios respetando las comillas. Las comillas se consumen: no forman
     * parte del valor, sólo dicen dónde acaba.
     */
    private fun tokenize(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false

        for (ch in raw) {
            when {
                ch == '"' -> quoted = !quoted
                ch.isWhitespace() && !quoted -> {
                    if (current.isNotEmpty()) tokens += current.toString()
                    current.setLength(0)
                }

                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private const val TAG = "#"
    private val KEYS = setOf("state", "p", "priority", "repo", "file", "is", "has")
}
