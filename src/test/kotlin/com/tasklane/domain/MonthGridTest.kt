package com.tasklane.domain

import com.tasklane.domain.model.MonthGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class MonthGridTest {

    @Test
    fun `seis semanas de siete dias, siempre`() {
        // Febrero de 2027 empieza en lunes y tiene 28 días: cabe justo en cuatro
        // semanas, y aun así la rejilla tiene que medir seis.
        val weeks = MonthGrid.weeks(YearMonth.of(2027, 2), DayOfWeek.MONDAY)

        assertEquals(MonthGrid.WEEKS, weeks.size)
        assertTrue(weeks.all { it.size == MonthGrid.DAYS })
    }

    @Test
    fun `la rejilla empieza en el primer dia de la semana del usuario`() {
        val month = YearMonth.of(2026, 9)

        val lunes = MonthGrid.weeks(month, DayOfWeek.MONDAY).first().first()
        val domingo = MonthGrid.weeks(month, DayOfWeek.SUNDAY).first().first()

        assertEquals(DayOfWeek.MONDAY, lunes.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, domingo.dayOfWeek)
        // El 1 de septiembre de 2026 es martes: con la semana en lunes se retrocede
        // un día, y con la semana en domingo, dos.
        assertEquals(LocalDate.of(2026, 8, 31), lunes)
        assertEquals(LocalDate.of(2026, 8, 30), domingo)
    }

    @Test
    fun `un mes que empieza justo en el primer dia no arrastra el mes anterior`() {
        val weeks = MonthGrid.weeks(YearMonth.of(2026, 6), DayOfWeek.MONDAY)

        assertEquals(LocalDate.of(2026, 6, 1), weeks.first().first())
    }

    @Test
    fun `los dias son consecutivos de principio a fin`() {
        val days = MonthGrid.weeks(YearMonth.of(2026, 12), DayOfWeek.MONDAY).flatten()

        assertEquals(MonthGrid.WEEKS * MonthGrid.DAYS, days.size)
        days.zipWithNext { a, b -> assertEquals(a.plusDays(1), b) }
    }

    @Test
    fun `las cabeceras siguen al primer dia de la semana`() {
        assertEquals(
            listOf(
                DayOfWeek.SUNDAY,
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
                DayOfWeek.SATURDAY,
            ),
            MonthGrid.headers(DayOfWeek.SUNDAY),
        )
    }
}
