package com.tasklane.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Los vencimientos que se ofrecen sin pedir una fecha concreta. */
enum class DuePreset { TODAY, TOMORROW, END_OF_WEEK, NEXT_WEEK }

/**
 * Traduce un [DuePreset] a un instante, y un instante de vuelta al preajuste que lo
 * produciría.
 *
 * Vive en el dominio y no en el diálogo porque es aritmética de calendario con dos
 * decisiones que hay que poder probar sin abrir una ventana:
 *
 * - **Vence al acabar el día, no al empezarlo.** Con `atStartOfDay` una tarea puesta
 *   para hoy nacería ya vencida, que es exactamente lo que nadie quiere de un
 *   «Hoy» recién elegido.
 * - **El fin de semana depende del locale.** La semana no empieza en lunes en todas
 *   partes, así que el primer día entra como parámetro en vez de darse por sabido.
 *
 * La vuelta ([presetOf]) existe para que al editar una tarea el desplegable salga ya
 * en la opción que corresponde en vez de en «sin fecha», que haría perder el
 * vencimiento a quien sólo quería cambiar el título.
 */
object DueDates {

    fun resolve(preset: DuePreset, today: LocalDate, zone: ZoneId, firstDayOfWeek: DayOfWeek): Instant =
        endOfDay(dateOf(preset, today, firstDayOfWeek), zone)

    /** El preajuste que daría exactamente [due], o `null` si ninguno lo hace. */
    fun presetOf(due: Instant, today: LocalDate, zone: ZoneId, firstDayOfWeek: DayOfWeek): DuePreset? =
        DuePreset.entries.firstOrNull { resolve(it, today, zone, firstDayOfWeek) == due }

    private fun dateOf(preset: DuePreset, today: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate = when (preset) {
        DuePreset.TODAY -> today
        DuePreset.TOMORROW -> today.plusDays(1)
        DuePreset.END_OF_WEEK -> endOfWeek(today, firstDayOfWeek)
        DuePreset.NEXT_WEEK -> today.plusWeeks(1)
    }

    /**
     * El último día de la semana en curso. Si hoy ya es ese día el resultado es hoy:
     * «para el final de la semana» estando en domingo no significa el domingo que
     * viene.
     */
    private fun endOfWeek(today: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
        val elapsed = Math.floorMod(today.dayOfWeek.value - firstDayOfWeek.value, DAYS_IN_WEEK)
        return today.plusDays((DAYS_IN_WEEK - 1 - elapsed).toLong())
    }

    /**
     * El instante en que acaba [date]. Es público porque la fecha concreta que se
     * elige en el calendario tiene que vencer igual que un preajuste: al acabar el
     * día, no al empezarlo.
     */
    fun atEndOfDay(date: LocalDate, zone: ZoneId): Instant = endOfDay(date, zone)

    private fun endOfDay(date: LocalDate, zone: ZoneId): Instant =
        date.atTime(LocalTime.MAX).atZone(zone).toInstant()

    private const val DAYS_IN_WEEK = 7
}
