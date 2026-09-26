package com.tasklane.domain.query

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * El calendario con el que se interpreta una consulta: el «ahora» y el «hoy» de
 * `is:overdue`, `today` o `<7d`, y en qué día empieza la semana de `week`.
 *
 * Se fija **una vez por consulta**. Si cada filtro leyera el reloj por su cuenta, una
 * consulta tecleada a medianoche podría entender dos «hoy» distintos.
 */
class QueryCalendar(val now: Instant, val zone: ZoneId, val firstDayOfWeek: DayOfWeek) {

    val today: LocalDate = now.atZone(zone).toLocalDate()

    /** Cuándo empieza [date] en la zona del usuario: los días se cortan en su medianoche, no en la de UTC. */
    fun startOf(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

    companion object {
        fun of(clock: Clock, firstDayOfWeek: DayOfWeek) = QueryCalendar(clock.instant(), clock.zone, firstDayOfWeek)
    }
}

/**
 * Los valores de `due:`, `closed:`, `created:` y `updated:` (2.21.0), traducidos a un
 * [DateFilter]. Tres formas, y todas cortan por **días enteros** del calendario del
 * usuario, no por horas:
 *
 * - **Con nombre**: `today`, `yesterday`, `tomorrow`, `week` y `month`, la semana y el mes
 *   en curso enteros.
 * - **Una fecha**: `2026-09-01` es ese día; `>`, `>=`, `<` y `<=` delante la vuelven un
 *   límite. `>2026-09-01` empieza el día 2.
 * - **Una distancia a hoy**: `<7d`, `>2w`. `<` es «más cerca de hoy que eso» y `>`, «más
 *   lejos». Hacia dónde se mide lo decide el campo: el vencimiento mira al futuro y el
 *   resto al pasado. `created:<7d` son los últimos siete días, hoy incluido;
 *   `due:<7d` lo que vence antes de dentro de siete días, **lo ya vencido incluido**,
 *   porque quien pregunta qué le vence esta semana quiere ver también lo que se le pasó.
 *   Sin `<` ni `>` no significa nada: `due:7d` no dice si antes o después.
 */
object QueryDates {

    /** Los valores con nombre, en el orden en que los ofrece el autocompletado. */
    val NAMED = listOf("today", "tomorrow", "yesterday", "week", "month")

    /** El intervalo que [value] —ya normalizado— describe para [field], o `null` si no es una fecha. */
    fun resolve(value: String, field: DateField, calendar: QueryCalendar): DateFilter? {
        named(value, calendar)?.let { (from, until) ->
            return DateFilter(field, calendar.startOf(from), calendar.startOf(until))
        }
        val op = OPERATORS.firstOrNull(value::startsWith).orEmpty()
        val rest = value.substring(op.length)

        distance(rest)?.let { days ->
            if (op.isEmpty()) return null
            return relative(op, days, field, calendar)
        }
        val date = date(rest) ?: return null
        val start = calendar.startOf(date)
        val next = calendar.startOf(date.plusDays(1))
        return when (op) {
            "" -> DateFilter(field, start, next)
            ">" -> DateFilter(field, next, null)
            ">=" -> DateFilter(field, start, null)
            "<" -> DateFilter(field, null, start)
            else -> DateFilter(field, null, next)
        }
    }

    /**
     * ¿Puede [value] acabar siendo una fecha si se sigue escribiendo? `<`, `<7`, `2026-0`
     * o `tod` todavía no lo son, y el buscador los tiene que dejar pasar sin filtrar: ver
     * el contrato de [QueryParser].
     */
    fun isPrefix(value: String): Boolean {
        if (NAMED.any { it.startsWith(value) }) return true
        val rest = value.removePrefix(">").removePrefix("<").removePrefix("=")
        if (rest.isEmpty()) return true
        if (rest.all(Char::isDigit)) return rest.length <= YEAR_DIGITS
        val parts = rest.split('-')
        if (parts.size > DATE_PARTS || parts[0].length != YEAR_DIGITS) return false
        if (parts.any { part -> !part.all(Char::isDigit) } || parts.drop(1).any { it.length > 2 }) return false
        if (parts.drop(1).dropLast(1).any(String::isEmpty)) return false
        // Completa y aun así no es una fecha —`2026-02-30`—: ya no va a serlo.
        return parts.size < DATE_PARTS || parts.last().length < 2
    }

    /** `[desde, hasta)` en días, o `null` si [value] no tiene nombre. */
    private fun named(value: String, calendar: QueryCalendar): Pair<LocalDate, LocalDate>? {
        val today = calendar.today
        return when (value) {
            "today" -> today to today.plusDays(1)
            "yesterday" -> today.minusDays(1) to today
            "tomorrow" -> today.plusDays(1) to today.plusDays(2)
            "week" -> {
                val start = today.minusDays(Math.floorMod(today.dayOfWeek.value - calendar.firstDayOfWeek.value, DAYS_IN_WEEK).toLong())
                start to start.plusDays(DAYS_IN_WEEK.toLong())
            }
            "month" -> today.withDayOfMonth(1) to today.withDayOfMonth(1).plusMonths(1)
            else -> null
        }
    }

    /** `7d` o `2w`, en días. Cuatro cifras como mucho: nadie cuenta en decenas de miles de días. */
    private fun distance(text: String): Long? {
        if (text.length < 2 || text.length > MAX_DIGITS + 1) return null
        val unit = when (text.last()) {
            'd' -> 1L
            'w' -> DAYS_IN_WEEK.toLong()
            else -> return null
        }
        val digits = text.dropLast(1)
        if (!digits.all(Char::isDigit)) return null
        return digits.toLong() * unit
    }

    /**
     * El límite de una distancia. Se escribe una vez para los dos sentidos: [ahead] es el
     * día que queda a [days] de hoy hacia donde mira el campo, y «más cerca» es el lado de
     * hoy.
     */
    private fun relative(op: String, days: Long, field: DateField, calendar: QueryCalendar): DateFilter {
        val today = calendar.today
        if (field == DateField.DUE) {
            val ahead = today.plusDays(days)
            return when (op) {
                "<" -> DateFilter(field, null, calendar.startOf(ahead))
                "<=" -> DateFilter(field, null, calendar.startOf(ahead.plusDays(1)))
                ">" -> DateFilter(field, calendar.startOf(ahead.plusDays(1)), null)
                else -> DateFilter(field, calendar.startOf(ahead), null)
            }
        }
        val ago = today.minusDays(days)
        return when (op) {
            "<" -> DateFilter(field, calendar.startOf(ago.plusDays(1)), null)
            "<=" -> DateFilter(field, calendar.startOf(ago), null)
            ">" -> DateFilter(field, null, calendar.startOf(ago))
            else -> DateFilter(field, null, calendar.startOf(ago.plusDays(1)))
        }
    }

    /** `2026-09-01`, y también `2026-9-1`: el cero de delante no aporta nada al teclearlo. */
    private fun date(text: String): LocalDate? {
        val parts = text.split('-')
        if (parts.size != DATE_PARTS || parts[0].length != YEAR_DIGITS) return null
        if (parts.any { it.isEmpty() || it.length > YEAR_DIGITS || !it.all(Char::isDigit) }) return null
        if (parts[1].length > 2 || parts[2].length > 2) return null
        return runCatching { LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) }.getOrNull()
    }

    /** Los largos primero: `<=` tiene que ganarle a `<`. */
    private val OPERATORS = listOf(">=", "<=", ">", "<")

    private const val DAYS_IN_WEEK = 7
    private const val YEAR_DIGITS = 4
    private const val DATE_PARTS = 3
    private const val MAX_DIGITS = 4
}
