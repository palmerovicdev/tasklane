package com.tasklane.domain

import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.DateGrouper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DateGrouperTest {

    private val zone: ZoneId = ZoneId.of("Europe/Madrid")

    /** Jueves: hasta la 2.2 el lunes y el martes de esta semana compartían cabecera. */
    private val today: LocalDate = LocalDate.of(2026, 9, 10)

    private fun groupOf(date: LocalDate?) = DateGrouper.groupOf(
        instant = date?.atTime(LocalTime.NOON)?.atZone(zone)?.toInstant(),
        today = today,
        zone = zone,
    )

    @Test
    fun `hoy tiene grupo propio`() {
        assertEquals(DateGroup.Today, groupOf(today))
    }

    /**
     * Lo que pidió la 2.3.0: **ningún** grupo junta días. Ayer ya no es «Yesterday», el
     * martes ya no es «This week» y un día de 2025 ya no se pierde dentro de su mes.
     */
    @Test
    fun `cualquier otro dia es su propio grupo con su fecha`() {
        for (date in listOf(
            today.minusDays(1),
            LocalDate.of(2026, 9, 8),
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 1, 2),
            LocalDate.of(2025, 9, 30),
            LocalDate.of(2025, 9, 1),
        )) {
            assertEquals(DateGroup.Day(date), groupOf(date))
        }
    }

    @Test
    fun `dos dias del mismo mes no comparten grupo`() {
        assertTrue(groupOf(LocalDate.of(2025, 9, 30)) != groupOf(LocalDate.of(2025, 9, 1)))
    }

    @Test
    fun `sin fecha ancla la tarea no desaparece`() {
        assertEquals(DateGroup.Undated, groupOf(null))
    }

    @Test
    fun `una fecha futura se trata como hoy`() {
        // Reloj desajustado o fichero editado a mano: no debe inventarse un grupo
        // por encima de Today.
        assertEquals(DateGroup.Today, groupOf(today.plusDays(3)))
    }

    /**
     * El dia lo decide la zona: a las 23:30 en Madrid ya es el dia siguiente en UTC, y
     * la cabecera tiene que ser la del dia en que el usuario hizo la tarea.
     */
    @Test
    fun `el dia se cuenta en la zona de quien mira`() {
        val lateNight = LocalDate.of(2026, 9, 8).atTime(23, 30).atZone(zone).toInstant()
        assertEquals(DateGroup.Day(LocalDate.of(2026, 9, 8)), DateGrouper.groupOf(lateNight, today, zone))
    }

    /**
     * El intervalo es la inversa del reparto: todo lo que cae dentro es de ese grupo y lo
     * de justo fuera no. Con el dia del cambio de hora, que mide 25 horas y es donde un
     * «mas 24 horas» se habria dejado la ultima fuera.
     */
    @Test
    fun `el intervalo de un dia es exactamente ese dia aunque cambie la hora`() {
        val change = LocalDate.of(2025, 10, 26)
        val range = DateGrouper.rangeOf(DateGroup.Day(change), today, zone)!!
        val first = Instant.ofEpochMilli(range.first)
        val last = Instant.ofEpochMilli(range.last)

        assertEquals(DateGroup.Day(change), DateGrouper.groupOf(first, today, zone))
        assertEquals(DateGroup.Day(change), DateGrouper.groupOf(last, today, zone))
        assertEquals(DateGroup.Day(change.plusDays(1)), DateGrouper.groupOf(last.plusMillis(1), today, zone))
        assertEquals(DateGroup.Day(change.minusDays(1)), DateGrouper.groupOf(first.minusMillis(1), today, zone))
        assertEquals(25 * 3_600_000L, range.last - range.first + 1)
    }

    @Test
    fun `hoy llega hasta el infinito y sin fecha no es un intervalo`() {
        assertEquals(Long.MAX_VALUE, DateGrouper.rangeOf(DateGroup.Today, today, zone)!!.last)
        assertNull(DateGrouper.rangeOf(DateGroup.Undated, today, zone))
    }

    /** La exportacion en Markdown encabeza cada grupo con su dia. */
    @Test
    fun `hoy se resuelve contra el calendario que se le pasa`() {
        assertEquals(today, DateGroup.Today.dayOn(today))
        assertEquals(LocalDate.of(2026, 1, 2), DateGroup.Day(LocalDate.of(2026, 1, 2)).dayOn(today))
    }

    /** Un dia inventado acabaria escrito en una exportacion. */
    @Test
    fun `lo que no tiene fecha no es un dia`() {
        assertNull(DateGroup.Undated.dayOn(today))
    }

    @Test
    fun `el orden natural es de mas reciente a mas antiguo`() {
        val shuffled = listOf(
            DateGroup.Day(LocalDate.of(2025, 9, 1)),
            DateGroup.Undated,
            DateGroup.Day(LocalDate.of(2026, 1, 2)),
            DateGroup.Today,
            DateGroup.Day(LocalDate.of(2025, 12, 31)),
            DateGroup.Day(LocalDate.of(2026, 9, 9)),
            DateGroup.Day(LocalDate.of(2026, 9, 6)),
        )

        assertEquals(
            listOf(
                DateGroup.Today,
                DateGroup.Day(LocalDate.of(2026, 9, 9)),
                DateGroup.Day(LocalDate.of(2026, 9, 6)),
                DateGroup.Day(LocalDate.of(2026, 1, 2)),
                DateGroup.Day(LocalDate.of(2025, 12, 31)),
                DateGroup.Day(LocalDate.of(2025, 9, 1)),
                DateGroup.Undated,
            ),
            shuffled.sorted(),
        )
    }
}
