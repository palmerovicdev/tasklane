package com.tasklane.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Grupo de fecha al que cae una tarea dentro de un estado que agrupa.
 *
 * Es un **valor**, no un texto: el dominio decide la partición y la UI decide cómo
 * se escribe. Esa separación es la que permite formatear con `DateFormatUtil` para
 * respetar el locale sin meter Swing —ni el locale— dentro del dominio.
 *
 * El orden es el de lectura: lo más reciente arriba. Se compara por ([band],
 * [within]) en vez de por un único entero para que no haya que reservar rangos
 * numéricos ni preocuparse de colisiones entre días y meses.
 */
sealed interface DateGroup : Comparable<DateGroup> {

    /** Familia del grupo. Ordena los bloques entre sí. */
    val band: Int

    /** Desempate dentro de la banda. Menor = más reciente. */
    val within: Long

    /**
     * El día del que habla el grupo, o `null` si no habla de uno solo.
     *
     * «Hoy» y «ayer» son días concretos, pero cuáles depende de cuándo se pregunte: por
     * eso entra [today] en vez de leerse el reloj aquí, igual que en [DateGrouper]. Una
     * semana, un mes y «sin fecha» no tienen día, y devolver uno inventado —el lunes, el
     * día 1— sería escribir en una exportación una fecha que nadie eligió.
     *
     * Lo usa la exportación en Markdown, que encabeza cada grupo con su fecha en ISO.
     */
    fun dayOn(today: LocalDate): LocalDate?

    override fun compareTo(other: DateGroup): Int =
        compareValuesBy(this, other, { it.band }, { it.within })

    data object Today : DateGroup {
        override val band = 0
        override val within = 0L
        override fun dayOn(today: LocalDate): LocalDate = today
    }

    data object Yesterday : DateGroup {
        override val band = 1
        override val within = 0L
        override fun dayOn(today: LocalDate): LocalDate = today.minusDays(1)
    }

    /** Desde el primer día de la semana hasta anteayer. Puede quedar vacío a principio de semana. */
    data object ThisWeek : DateGroup {
        override val band = 2
        override val within = 0L
        override fun dayOn(today: LocalDate): LocalDate? = null
    }

    /** Un día suelto del año en curso: «Sep 10». */
    data class Day(val date: LocalDate) : DateGroup {
        override val band = 3
        override val within get() = -date.toEpochDay()
        override fun dayOn(today: LocalDate): LocalDate = date
    }

    /** Un mes de un año anterior: «Sep 2025». */
    data class Month(val month: YearMonth) : DateGroup {
        override val band = 4
        override val within get() = -(month.year * 12L + month.monthValue)
        override fun dayOn(today: LocalDate): LocalDate? = null
    }

    /**
     * La tarea no tiene la fecha que pide el anclaje del estado — típicamente un
     * estado que agrupa por `completedAt` y tareas que nunca se completaron.
     * Va al final en vez de desaparecer.
     */
    data object Undated : DateGroup {
        override val band = 5
        override val within = 0L
        override fun dayOn(today: LocalDate): LocalDate? = null
    }
}

/**
 * Reparte tareas en [DateGroup]s. Kotlin puro: se testea sin arrancar un IDE.
 *
 * [firstDayOfWeek] entra por parámetro en vez de leerse de `Locale.getDefault()`
 * porque el dominio no debe depender del entorno: la UI pasa el del usuario y los
 * tests pasan el que necesiten.
 */
object DateGrouper {

    /** Fecha de la que cuelga la agrupación de un estado, según su [DateAnchor]. */
    fun anchorOf(task: Task, anchor: DateAnchor): Instant? = when (anchor) {
        DateAnchor.CREATED -> task.createdAt
        DateAnchor.UPDATED -> task.updatedAt
        DateAnchor.COMPLETED -> task.completedAt
    }

    fun groupOf(
        instant: Instant?,
        today: LocalDate,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): DateGroup {
        if (instant == null) return DateGroup.Undated
        val date = instant.atZone(zone).toLocalDate()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        return when {
            // Una fecha futura sólo puede venir de un reloj desajustado o de un
            // fichero editado a mano. Se trata como «ahora» en vez de inventarle
            // un grupo que quedaría por encima de Today.
            !date.isBefore(today) -> DateGroup.Today
            date == today.minusDays(1) -> DateGroup.Yesterday
            !date.isBefore(weekStart) -> DateGroup.ThisWeek
            date.year == today.year -> DateGroup.Day(date)
            else -> DateGroup.Month(YearMonth.from(date))
        }
    }
}
