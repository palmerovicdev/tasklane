package com.tasklane.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * La rejilla de un mes: seis semanas de siete días, con los de los meses vecinos
 * rellenando los huecos de los extremos.
 *
 * Está en el dominio —y no dentro del diálogo que la pinta— por lo de siempre: es
 * aritmética de calendario, es donde se equivoca uno, y aquí se puede probar sin
 * abrir una ventana. Ver [DueDates], que existe por el mismo motivo.
 *
 * **Siempre seis filas**, incluso cuando el mes cabe en cinco. Un calendario que
 * cambia de alto al pasar de mes hace saltar el diálogo entero bajo el ratón, y el
 * botón que se iba a pulsar se mueve.
 *
 * [firstDayOfWeek] entra por parámetro porque la semana no empieza en lunes en todas
 * partes y el dominio no debe leer el `Locale` del entorno.
 */
object MonthGrid {

    const val WEEKS = 6
    const val DAYS = 7

    fun weeks(month: YearMonth, firstDayOfWeek: DayOfWeek): List<List<LocalDate>> {
        val first = month.atDay(1)
        // Cuántos días hay que retroceder para empezar la rejilla en el primer día de
        // la semana del usuario. Con `floorMod` no hace falta tratar el cruce de año
        // ni los valores negativos.
        val lead = Math.floorMod(first.dayOfWeek.value - firstDayOfWeek.value, DAYS)
        val start = first.minusDays(lead.toLong())
        return (0 until WEEKS).map { week ->
            (0 until DAYS).map { day -> start.plusDays((week * DAYS + day).toLong()) }
        }
    }

    /** Los siete días de la semana empezando por [firstDayOfWeek], para las cabeceras. */
    fun headers(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
        (0 until DAYS).map { firstDayOfWeek.plus(it.toLong()) }
}
