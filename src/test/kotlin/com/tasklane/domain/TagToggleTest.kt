package com.tasklane.domain

import com.tasklane.domain.query.QueryParser
import com.tasklane.domain.query.TagToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pulsar `#api` en una tarjeta (P30): añadirla al buscador, o quitarla si ya estaba. */
class TagToggleTest {

    @Test
    fun `sobre un buscador vacio la etiqueta es toda la busqueda`() {
        assertEquals("#api", TagToggle.toggle("", "api"))
        assertEquals("#api", TagToggle.toggle("   ", "api"))
    }

    @Test
    fun `se anade a lo que ya habia escrito, que asi se estrecha`() {
        assertEquals("login #api", TagToggle.toggle("login", "api"))
        assertEquals("state:\"In Review\" #api", TagToggle.toggle("state:\"In Review\" ", "api"))
        assertEquals("#urgente #api", TagToggle.toggle("#urgente", "api"))
    }

    @Test
    fun `pulsarla otra vez la quita y deja el resto como estaba`() {
        assertEquals("", TagToggle.toggle("#api", "api"))
        assertEquals("login", TagToggle.toggle("login #api", "api"))
        assertEquals("login fallo", TagToggle.toggle("login #api fallo", "api"))
        assertEquals("state:\"In Review\"", TagToggle.toggle("#api state:\"In Review\"", "api"))
    }

    @Test
    fun `sin mirar mayusculas, pero sin comerse otra etiqueta que empieza igual`() {
        assertTrue(TagToggle.isActive("#API", "api"))
        assertEquals("", TagToggle.toggle("#API", "api"))
        assertFalse(TagToggle.isActive("#apis", "api"))
        assertEquals("#apis #api", TagToggle.toggle("#apis", "api"))
        assertFalse(TagToggle.isActive("api", "api"))
    }

    @Test
    fun `una etiqueta con espacios va entre comillas y el buscador la lee entera`() {
        val raw = TagToggle.toggle("", "en revisión")
        assertEquals("#\"en revisión\"", raw)
        assertEquals(setOf("en revision"), QueryParser.parse(raw).tags)
        assertEquals("", TagToggle.toggle(raw, "en revisión"))
    }

    @Test
    fun `los caracteres especiales de una etiqueta no son una expresion regular`() {
        assertEquals("#c++", TagToggle.toggle("", "c++"))
        assertEquals("", TagToggle.toggle("#c++", "c++"))
        assertFalse(TagToggle.isActive("#cxx", "c.."))
    }
}
