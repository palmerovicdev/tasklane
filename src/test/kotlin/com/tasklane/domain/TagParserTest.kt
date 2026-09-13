package com.tasklane.domain

import com.tasklane.domain.text.TagParser
import org.junit.Assert.assertEquals
import org.junit.Test

class TagParserTest {

    @Test
    fun `separa por comas, espacios y saltos de linea`() {
        assertEquals(listOf("api", "urgente", "web"), TagParser.parse("api, urgente\nweb"))
    }

    @Test
    fun `la almohadilla de delante sobra`() {
        assertEquals(listOf("api"), TagParser.parse("#api"))
    }

    @Test
    fun `la misma etiqueta dos veces es una, y se queda la primera forma`() {
        assertEquals(listOf("API"), TagParser.parse("API, api, #Api"))
    }

    @Test
    fun `lo vacio no produce etiquetas`() {
        assertEquals(emptyList<String>(), TagParser.parse("  ,  , #"))
    }

    @Test
    fun `anadir respeta lo que ya estaba`() {
        assertEquals(listOf("api", "web"), TagParser.add(listOf("api"), "web"))
        assertEquals(listOf("api"), TagParser.add(listOf("api"), "API"))
    }
}
