package com.tasklane.domain.query

import com.tasklane.domain.text.TextNormalizer
import java.time.Clock
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Convierte lo que hay escrito en el campo de búsqueda en una [TaskQuery].
 *
 * El contrato que gobierna todas las decisiones de aquí es que **esto se ejecuta
 * mientras el usuario teclea**: una consulta a medio escribir no puede vaciar la
 * lista de golpe ni hacer desaparecer lo que el usuario está buscando.
 *
 * De ahí las reglas menos obvias:
 *
 * - Un operador sin valor (`state:`, justo antes de escribir el nombre) se **ignora**
 *   en vez de filtrar por la cadena vacía. Lo mismo un valor de `is:`, `has:` o de
 *   fecha que todavía puede acabar siendo uno válido (`is:ov`, `due:<7`, `due:2026-0`)
 *   y un `-` o un `-#` sueltos (2.21.0).
 * - Un prefijo desconocido (`https:`, `TODO:`) se trata como texto libre, con los dos
 *   puntos incluidos. Pegar una URL en el campo busca la URL, que es lo que se
 *   esperaba; no un filtro por un operador que no existe. Un valor que ya no puede
 *   llegar a ser válido (`is:quizas`) también vuelve a ser texto.
 * - Un valor con espacios se entrecomilla —`state:"In Review"`— y las comillas
 *   pueden abrirse en cualquier punto del token.
 * - Un `-` delante niega el token entero (2.21.0): `-#wip`, `-p:low`, `-is:done`,
 *   `-borrador`. Cada token negado es su propia condición: ver [TaskQuery.excluded].
 */
object QueryParser {

    /**
     * [clock] y [firstDayOfWeek] sólo importan a lo que habla de fechas —`is:overdue`,
     * `due:week`…—. Entran como parámetro por lo mismo que en `DueDates`: hoy y el primer
     * día de la semana no se dan por sabidos, y los tests fijan los dos.
     */
    fun parse(
        raw: String,
        clock: Clock = Clock.systemDefaultZone(),
        firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
    ): TaskQuery {
        val calendar = QueryCalendar.of(clock, firstDayOfWeek)
        val query = Clauses(calendar)
        val excluded = mutableListOf<TaskQuery>()

        for (token in tokenize(raw)) {
            if (token.isEmpty()) continue
            if (token.startsWith(NOT)) {
                val negated = token.substring(NOT.length)
                // `-` y `-#` sueltos son el principio de una negación, no una búsqueda.
                if (negated.isEmpty() || negated == TAG) continue
                val clause = Clauses(calendar).apply { accept(negated) }.build()
                if (!clause.isEmpty) excluded += clause
                continue
            }
            query.accept(token)
        }
        return query.build().copy(excluded = excluded)
    }

    /** Las condiciones que se van reuniendo, token a token. */
    private class Clauses(private val calendar: QueryCalendar) {
        val terms = mutableListOf<String>()
        val states = mutableSetOf<String>()
        val priorities = mutableSetOf<String>()
        val repos = mutableSetOf<String>()
        val tags = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        val has = mutableSetOf<TaskQuery.Facet>()
        val dates = mutableListOf<DateFilter>()
        var done: Boolean? = null
        var overdue = false

        fun accept(token: String) {
            if (token.startsWith(TAG) && token.length > TAG.length) {
                tags += TextNormalizer.normalize(token.removePrefix(TAG))
                return
            }

            val colon = token.indexOf(':')
            val key = if (colon > 0) token.take(colon).lowercase() else null
            val value = if (colon > 0) TextNormalizer.normalize(token.substring(colon + 1)) else ""

            // Un operador conocido pero todavía sin valor no filtra: el usuario está
            // a mitad de escribirlo y la lista no debe parpadear.
            if (key != null && value.isEmpty() && key in KEYS) return

            when (key) {
                "state" -> states += value
                "p", "priority" -> priorities += value
                "repo" -> repos += value
                "file" -> files += value
                "is" -> when (value) {
                    "done", "closed" -> done = true
                    "open", "todo" -> done = false
                    "overdue" -> overdue = true
                    "bookmarked", "bookmark" -> has += TaskQuery.Facet.BOOKMARKED
                    else -> unknown(token, value, IS_VALUES)
                }

                "has" -> when (value) {
                    "link", "links" -> has += TaskQuery.Facet.LINK
                    "image", "images" -> has += TaskQuery.Facet.IMAGE
                    "code", "anchor" -> has += TaskQuery.Facet.CODE
                    "broken-anchor", "broken-anchors", "broken" -> has += TaskQuery.Facet.BROKEN_ANCHOR
                    "due" -> has += TaskQuery.Facet.DUE
                    "checklist" -> has += TaskQuery.Facet.CHECKLIST
                    "tag", "tags" -> has += TaskQuery.Facet.TAG
                    else -> unknown(token, value, HAS_VALUES)
                }

                else -> {
                    val field = key?.let(DATE_KEYS::get)
                    if (field != null) date(token, value, field) else terms += TextNormalizer.normalize(token)
                }
            }
        }

        private fun date(token: String, value: String, field: DateField) {
            val filter = QueryDates.resolve(value, field, calendar)
            if (filter != null) dates += filter
            else if (!QueryDates.isPrefix(value)) terms += TextNormalizer.normalize(token)
        }

        /** A medio escribir no filtra; lo que ya no puede ser un valor vuelve a ser texto. */
        private fun unknown(token: String, value: String, known: List<String>) {
            if (known.none { it.startsWith(value) }) terms += TextNormalizer.normalize(token)
        }

        fun build() = TaskQuery(
            terms = terms.filter(String::isNotEmpty),
            states = states,
            priorities = priorities,
            repos = repos,
            tags = tags,
            files = files,
            done = done,
            has = has,
            dates = dates,
            overdue = if (overdue) calendar.now else null,
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
    private const val NOT = "-"

    /** Los valores de `is:` y `has:` que se reconocen, con sus sinónimos. Los usa también el autocompletado. */
    internal val IS_VALUES = listOf("open", "done", "overdue", "bookmarked", "closed", "todo", "bookmark")
    internal val HAS_VALUES = listOf(
        "due", "checklist", "tag", "link", "image", "code", "broken-anchor",
        "tags", "links", "images", "anchor", "broken-anchors", "broken",
    )

    internal val DATE_KEYS = mapOf(
        "due" to DateField.DUE,
        "closed" to DateField.CLOSED,
        "created" to DateField.CREATED,
        "updated" to DateField.UPDATED,
    )

    private val KEYS = setOf("state", "p", "priority", "repo", "file", "is", "has") + DATE_KEYS.keys
}
