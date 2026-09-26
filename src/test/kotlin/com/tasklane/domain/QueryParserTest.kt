package com.tasklane.domain

import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.query.DateField
import com.tasklane.domain.query.DateFilter
import com.tasklane.domain.query.QueryParser
import com.tasklane.domain.query.TaskQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class QueryParserTest {

    @Test
    fun `el texto libre se normaliza`() {
        assertEquals(listOf("autenticacion", "movil"), QueryParser.parse("Autenticación MÓVIL").terms)
    }

    @Test
    fun `los operadores se separan del texto libre`() {
        val query = QueryParser.parse("state:doing p:high fallo")

        assertEquals(setOf("doing"), query.states)
        assertEquals(setOf("high"), query.priorities)
        assertEquals(listOf("fallo"), query.terms)
    }

    @Test
    fun `file acota a las tareas ancladas a un fichero`() {
        val query = QueryParser.parse("file:AuthService.kt fallo")

        assertEquals(setOf("authservice.kt"), query.files)
        assertEquals(listOf("fallo"), query.terms)
    }

    /** A medio escribir no filtra, como el resto de operadores. */
    @Test
    fun `file sin valor se ignora`() {
        assertTrue(QueryParser.parse("file:").files.isEmpty())
        assertTrue(QueryParser.parse("file:").isEmpty)
    }

    @Test
    fun `is y has se traducen a sus facetas`() {
        assertEquals(true, QueryParser.parse("is:done").done)
        assertEquals(false, QueryParser.parse("is:open").done)
        assertEquals(setOf(TaskQuery.Facet.LINK), QueryParser.parse("has:link").has)
        assertEquals(setOf(TaskQuery.Facet.IMAGE), QueryParser.parse("has:image").has)
        assertEquals(setOf(TaskQuery.Facet.CODE), QueryParser.parse("has:code").has)
        assertEquals(setOf(TaskQuery.Facet.BROKEN_ANCHOR), QueryParser.parse("has:broken-anchor").has)
        assertEquals(setOf(TaskQuery.Facet.BROKEN_ANCHOR), QueryParser.parse("has:Broken").has)
    }

    @Test
    fun `las comillas permiten valores con espacios`() {
        assertEquals(setOf("in review"), QueryParser.parse("""state:"In Review"""").states)
    }

    @Test
    fun `un operador todavia sin valor se ignora`() {
        // Se dispara en cada tecla: `state:` a medio escribir no puede vaciar la lista.
        assertEquals(TaskQuery.EMPTY, QueryParser.parse("state:"))
        assertTrue(QueryParser.parse("state:").isEmpty)
    }

    @Test
    fun `un prefijo desconocido es texto libre, dos puntos incluidos`() {
        val query = QueryParser.parse("https://example.com/x")

        assertEquals(listOf("https://example.com/x"), query.terms)
        assertTrue(query.states.isEmpty())
    }

    @Test
    fun `un valor de is o has que no se reconoce vuelve a ser texto`() {
        assertEquals(listOf("is:quizas"), QueryParser.parse("is:quizas").terms)
        assertNull(QueryParser.parse("is:quizas").done)
    }

    @Test
    fun `las etiquetas van por su propio operador`() {
        val query = QueryParser.parse("#api #urgente")
        assertEquals(setOf("api", "urgente"), query.tags)
        assertTrue(query.terms.isEmpty())
    }

    @Test
    fun `una almohadilla suelta no es una etiqueta`() {
        assertEquals(listOf("#"), QueryParser.parse("#").terms)
    }

    @Test
    fun `el mismo operador repetido acumula valores`() {
        assertEquals(setOf("todo", "doing"), QueryParser.parse("state:todo state:doing").states)
    }

    @Test
    fun `una consulta en blanco esta vacia`() {
        assertTrue(QueryParser.parse("   ").isEmpty)
    }

    // ------------------------------------------------ la consulta completa (2.21.0)

    /** Sábado 26 de septiembre de 2026, 10:00 en Madrid. La semana empieza en lunes. */
    private val zone = ZoneId.of("Europe/Madrid")
    private val clock = Clock.fixed(Instant.parse("2026-09-26T08:00:00Z"), zone)

    private fun parse(raw: String) = QueryParser.parse(raw, clock, DayOfWeek.MONDAY)

    private fun day(date: String): Instant = LocalDate.parse(date).atStartOfDay(zone).toInstant()

    @Test
    fun `is overdue lleva el instante en que se pregunto`() {
        assertEquals(clock.instant(), parse("is:overdue").overdue)
        assertNull(parse("fallo").overdue)
    }

    @Test
    fun `is bookmarked y los has nuevos son facetas`() {
        assertEquals(setOf(TaskQuery.Facet.BOOKMARKED), parse("is:bookmarked").has)
        assertEquals(setOf(TaskQuery.Facet.DUE), parse("has:due").has)
        assertEquals(setOf(TaskQuery.Facet.CHECKLIST), parse("has:checklist").has)
        assertEquals(setOf(TaskQuery.Facet.TAG), parse("has:tags").has)
    }

    /** `is:ov` va camino de `is:overdue`: filtrar por el texto `is:ov` vaciaría la lista a cada tecla. */
    @Test
    fun `un valor a medio escribir de is o has no filtra`() {
        assertTrue(parse("is:ov").isEmpty)
        assertTrue(parse("has:check").isEmpty)
        assertEquals(listOf("has:nada"), parse("has:nada").terms)
    }

    @Test
    fun `el menos niega cada token por separado`() {
        val query = parse("fallo -#wip -p:low -is:done -borrador")

        assertEquals(listOf("fallo"), query.terms)
        assertEquals(
            listOf(
                TaskQuery(tags = setOf("wip")),
                TaskQuery(priorities = setOf("low")),
                TaskQuery(done = true),
                TaskQuery(terms = listOf("borrador")),
            ),
            query.excluded,
        )
        assertEquals("lo negado no se resalta", "fallo", query.text)
    }

    @Test
    fun `lo negado entre comillas es una frase`() {
        assertEquals(listOf(TaskQuery(terms = listOf("en curso"))), parse("""-"En curso"""").excluded)
        assertEquals(listOf(TaskQuery(states = setOf("in review"))), parse("""-state:"In Review"""").excluded)
    }

    @Test
    fun `un menos suelto o a medio escribir no filtra`() {
        assertTrue(parse("-").isEmpty)
        assertTrue(parse("-#").isEmpty)
        assertTrue(parse("-p:").isEmpty)
        assertTrue(parse("-is:ov").isEmpty)
    }

    @Test
    fun `una consulta solo con negaciones no esta vacia`() {
        assertFalse(parse("-is:done").isEmpty)
    }

    @Test
    fun `los dias con nombre son dias enteros del calendario del usuario`() {
        assertEquals(DateFilter(DateField.DUE, day("2026-09-26"), day("2026-09-27")), parse("due:today").dates.single())
        assertEquals(DateFilter(DateField.DUE, day("2026-09-27"), day("2026-09-28")), parse("due:tomorrow").dates.single())
        assertEquals(DateFilter(DateField.CLOSED, day("2026-09-25"), day("2026-09-26")), parse("closed:yesterday").dates.single())
        assertEquals(DateFilter(DateField.CREATED, day("2026-09-01"), day("2026-10-01")), parse("created:month").dates.single())
    }

    /** El 26 es sábado: la semana va del lunes 21 al domingo 27, o del domingo 20 al sábado 26. */
    @Test
    fun `la semana empieza donde diga el locale`() {
        assertEquals(DateFilter(DateField.CLOSED, day("2026-09-21"), day("2026-09-28")), parse("closed:week").dates.single())
        val sunday = QueryParser.parse("closed:week", clock, DayOfWeek.SUNDAY)
        assertEquals(DateFilter(DateField.CLOSED, day("2026-09-20"), day("2026-09-27")), sunday.dates.single())
    }

    @Test
    fun `una fecha es ese dia y con comparador un limite`() {
        assertEquals(DateFilter(DateField.CREATED, day("2026-09-01"), day("2026-09-02")), parse("created:2026-09-01").dates.single())
        assertEquals(DateFilter(DateField.CREATED, day("2026-09-02"), null), parse("created:>2026-09-01").dates.single())
        assertEquals(DateFilter(DateField.CREATED, day("2026-09-01"), null), parse("created:>=2026-9-1").dates.single())
        assertEquals(DateFilter(DateField.CREATED, null, day("2026-09-01")), parse("created:<2026-09-01").dates.single())
        assertEquals(DateFilter(DateField.CREATED, null, day("2026-09-02")), parse("created:<=2026-09-01").dates.single())
    }

    /**
     * `<` es «más cerca de hoy» y `>`, «más lejos»; el vencimiento mira adelante y el resto
     * atrás. `due:<7d` deja entrar lo ya vencido: no tiene límite por abajo.
     */
    @Test
    fun `una distancia se mide hacia donde mira el campo`() {
        assertEquals(DateFilter(DateField.DUE, null, day("2026-10-03")), parse("due:<7d").dates.single())
        assertEquals(DateFilter(DateField.DUE, day("2026-10-04"), null), parse("due:>1w").dates.single())
        assertEquals(DateFilter(DateField.CREATED, day("2026-09-20"), null), parse("created:<7d").dates.single())
        assertEquals(DateFilter(DateField.UPDATED, null, day("2026-09-19")), parse("updated:>7d").dates.single())
        assertEquals(DateFilter(DateField.CLOSED, day("2026-09-26"), null), parse("closed:<1d").dates.single())
    }

    @Test
    fun `dos fechas del mismo campo son un intervalo`() {
        assertEquals(2, parse("created:>2026-09-01 created:<2026-09-10").dates.size)
    }

    @Test
    fun `una fecha a medio escribir no filtra y una imposible es texto`() {
        for (typing in listOf("due:<", "due:<7", "due:2026-0", "due:2026-09-", "due:tod", "due:>=")) {
            assertTrue("«$typing» se está escribiendo", parse(typing).isEmpty)
        }
        assertEquals(listOf("due:7d"), parse("due:7d").terms)
        assertEquals(listOf("due:manana"), parse("due:mañana").terms)
        assertEquals(listOf("created:2026-02-30"), parse("created:2026-02-30").terms)
    }

    /**
     * Lo pregunta la herramienta MCP, que esconde lo cerrado si no se le pide: con
     * `state:Done` escrito en la consulta y sin `includeClosed` devolvía cero (2.12.0).
     */
    @Test
    fun `lo cerrado se menciona con is done, closed, negando is open o con un estado terminal`() {
        val config = TasklaneConfig.DEFAULT
        assertTrue(parse("is:done").mentionsClosed(config))
        assertTrue(parse("closed:week").mentionsClosed(config))
        assertTrue(parse("-is:open").mentionsClosed(config))
        assertTrue(parse("state:Done").mentionsClosed(config))
        assertTrue("un prefijo que alcanza a Done también", parse("state:do").mentionsClosed(config))
        assertFalse(parse("state:doing").mentionsClosed(config))
        assertFalse(parse("due:week #api").mentionsClosed(config))
    }
}
