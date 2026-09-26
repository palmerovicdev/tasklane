package com.tasklane.domain

import com.tasklane.domain.query.QueryParser
import com.tasklane.domain.query.TaskQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
