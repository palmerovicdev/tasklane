package com.tasklane.domain

import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.DateGrouper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

class DateGrouperTest {

    private val zone: ZoneId = ZoneId.of("Europe/Madrid")

    /** Jueves, para que la semana en curso tenga anteayer dentro y no empiece en hoy. */
    private val today: LocalDate = LocalDate.of(2026, 9, 10)

    private fun groupOf(date: LocalDate?) = DateGrouper.groupOf(
        instant = date?.atTime(LocalTime.NOON)?.atZone(zone)?.toInstant(),
        today = today,
        zone = zone,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    @Test
    fun `hoy y ayer tienen grupo propio`() {
        assertEquals(DateGroup.Today, groupOf(today))
        assertEquals(DateGroup.Yesterday, groupOf(today.minusDays(1)))
    }

    @Test
    fun `anteayer cae en la semana en curso`() {
        // Martes 8: dentro de la semana que empieza el lunes 7, y ya no es ayer.
        assertEquals(DateGroup.ThisWeek, groupOf(LocalDate.of(2026, 9, 8)))
        assertEquals(DateGroup.ThisWeek, groupOf(LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun `antes de esta semana pero dentro del ano se agrupa por dia`() {
        // Domingo 6: la semana empieza el lunes, asi que ya queda fuera.
        assertEquals(DateGroup.Day(LocalDate.of(2026, 9, 6)), groupOf(LocalDate.of(2026, 9, 6)))
        assertEquals(DateGroup.Day(LocalDate.of(2026, 1, 2)), groupOf(LocalDate.of(2026, 1, 2)))
    }

    @Test
    fun `los anos anteriores se agrupan por mes`() {
        assertEquals(DateGroup.Month(YearMonth.of(2025, 9)), groupOf(LocalDate.of(2025, 9, 30)))
        assertEquals(DateGroup.Month(YearMonth.of(2025, 9)), groupOf(LocalDate.of(2025, 9, 1)))
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

    @Test
    fun `el primer dia de la semana lo decide quien llama`() {
        val sunday = LocalDate.of(2026, 9, 6)
        assertEquals(
            "con semana que empieza en domingo, el domingo 6 entra en la semana en curso",
            DateGroup.ThisWeek,
            DateGrouper.groupOf(
                sunday.atTime(LocalTime.NOON).atZone(zone).toInstant(),
                today,
                zone,
                DayOfWeek.SUNDAY,
            ),
        )
    }

    /**
     * La exportacion en Markdown encabeza cada grupo con su dia, y «hoy» y «ayer» solo
     * son un dia concreto contra un calendario: por eso [DateGroup.dayOn] lo recibe.
     */
    @Test
    fun `hoy y ayer se resuelven contra el calendario que se les pasa`() {
        assertEquals(today, DateGroup.Today.dayOn(today))
        assertEquals(LocalDate.of(2026, 9, 9), DateGroup.Yesterday.dayOn(today))
        assertEquals(LocalDate.of(2026, 1, 2), DateGroup.Day(LocalDate.of(2026, 1, 2)).dayOn(today))
    }

    /** Un dia inventado —el lunes, el dia 1— acabaria escrito en una exportacion. */
    @Test
    fun `una semana, un mes y lo que no tiene fecha no son un dia`() {
        assertNull(DateGroup.ThisWeek.dayOn(today))
        assertNull(DateGroup.Month(YearMonth.of(2025, 9)).dayOn(today))
        assertNull(DateGroup.Undated.dayOn(today))
    }

    @Test
    fun `el orden natural es de mas reciente a mas antiguo`() {
        val shuffled = listOf(
            DateGroup.Month(YearMonth.of(2025, 9)),
            DateGroup.Undated,
            DateGroup.Day(LocalDate.of(2026, 1, 2)),
            DateGroup.Today,
            DateGroup.Month(YearMonth.of(2025, 12)),
            DateGroup.ThisWeek,
            DateGroup.Day(LocalDate.of(2026, 9, 6)),
            DateGroup.Yesterday,
        )

        assertEquals(
            listOf(
                DateGroup.Today,
                DateGroup.Yesterday,
                DateGroup.ThisWeek,
                DateGroup.Day(LocalDate.of(2026, 9, 6)),
                DateGroup.Day(LocalDate.of(2026, 1, 2)),
                DateGroup.Month(YearMonth.of(2025, 12)),
                DateGroup.Month(YearMonth.of(2025, 9)),
                DateGroup.Undated,
            ),
            shuffled.sorted(),
        )
    }
}
