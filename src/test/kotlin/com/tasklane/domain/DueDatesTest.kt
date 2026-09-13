package com.tasklane.domain

import com.tasklane.domain.model.DueDates
import com.tasklane.domain.model.DuePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DueDatesTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val monday = DayOfWeek.MONDAY

    /** Domingo 13 de septiembre de 2026. */
    private val today = LocalDate.of(2026, 9, 13)

    private fun resolve(preset: DuePreset, on: LocalDate = today, first: DayOfWeek = monday) =
        DueDates.resolve(preset, on, zone, first)

    private fun dateOf(preset: DuePreset, on: LocalDate = today, first: DayOfWeek = monday): LocalDate =
        ZonedDateTime.ofInstant(resolve(preset, on, first), zone).toLocalDate()

    @Test
    fun `vencer hoy es al final del dia y no al principio`() {
        // Con `atStartOfDay` una tarea puesta para hoy nacería ya vencida.
        val due = resolve(DuePreset.TODAY)
        assertTrue("hoy no puede nacer vencida", due.isAfter(today.atTime(12, 0).atZone(zone).toInstant()))
        assertEquals(today, dateOf(DuePreset.TODAY))
    }

    @Test
    fun `manana es el dia siguiente`() {
        assertEquals(LocalDate.of(2026, 9, 14), dateOf(DuePreset.TOMORROW))
    }

    @Test
    fun `el fin de semana depende del primer dia del locale`() {
        // 13/09/2026 es domingo. Con la semana empezando en lunes, el domingo YA es
        // el último día: «para el final de la semana» no significa dentro de siete.
        assertEquals(today, dateOf(DuePreset.END_OF_WEEK, first = DayOfWeek.MONDAY))
        // Con la semana empezando en domingo, ese mismo día es el primero y el
        // último es el sábado siguiente.
        assertEquals(LocalDate.of(2026, 9, 19), dateOf(DuePreset.END_OF_WEEK, first = DayOfWeek.SUNDAY))
    }

    @Test
    fun `fin de semana a mitad de semana cae en el ultimo dia`() {
        val miercoles = LocalDate.of(2026, 9, 16)
        assertEquals(LocalDate.of(2026, 9, 20), dateOf(DuePreset.END_OF_WEEK, on = miercoles))
    }

    @Test
    fun `la semana que viene son siete dias`() {
        assertEquals(LocalDate.of(2026, 9, 20), dateOf(DuePreset.NEXT_WEEK))
    }

    @Test
    fun `la vuelta reconoce el preajuste que produjo la fecha`() {
        // Es lo que hace que editar una tarea con vencimiento no lo pierda por salir
        // el desplegable en «sin fecha».
        //
        // Lo que se comprueba es que el preajuste devuelto da **la misma fecha**, no
        // que sea el mismo nombre: dos preajustes pueden coincidir —un domingo con la
        // semana empezando en lunes, «hoy» y «fin de semana» son el mismo día— y
        // entonces cualquiera de los dos es una respuesta correcta.
        for (preset in DuePreset.entries) {
            val due = resolve(preset)
            val back = DueDates.presetOf(due, today, zone, monday)
            assertEquals("$preset tiene que reconocerse", due, back?.let { resolve(it) })
        }
    }

    @Test
    fun `un miercoles los cuatro preajustes son cuatro dias distintos`() {
        val miercoles = LocalDate.of(2026, 9, 16)
        val fechas = DuePreset.entries.map { dateOf(it, on = miercoles) }
        assertEquals("sin solapes no puede haber ambiguedad", fechas.size, fechas.toSet().size)
        for (preset in DuePreset.entries) {
            assertEquals(preset, DueDates.presetOf(resolve(preset, miercoles), miercoles, zone, monday))
        }
    }

    @Test
    fun `una fecha que no da ningun preajuste no se inventa uno`() {
        val dentroDeUnMes = today.plusMonths(1).atTime(9, 30).atZone(zone).toInstant()
        assertNull(DueDates.presetOf(dentroDeUnMes, today, zone, monday))
    }

    @Test
    fun `una fecha concreta vence al acabar el dia, como los preajustes`() {
        val date = LocalDate.of(2026, 9, 20)

        val exact = DueDates.atEndOfDay(date, zone)

        // El mismo instante que daría el preajuste si ese día fuese hoy: si no, una
        // tarea puesta a mano para hoy nacería vencida.
        assertEquals(DueDates.resolve(DuePreset.TODAY, date, zone, DayOfWeek.MONDAY), exact)
        assertEquals(date, exact.atZone(zone).toLocalDate())
    }
}
