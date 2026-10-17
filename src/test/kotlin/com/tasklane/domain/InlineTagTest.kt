package com.tasklane.domain

import com.tasklane.domain.text.InlineTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineTagTest {

    /** La palabra que cierra el separador tecleado en la última posición de [typed]. */
    private fun closedBy(typed: String): InlineTag.Found? = InlineTag.endingAt(typed, typed.length - 1)

    @Test
    fun `una palabra con almohadilla y un separador es una etiqueta`() {
        assertEquals(InlineTag.Found(8, 12, "api"), closedBy("Revisar #api "))
        assertEquals("api", closedBy("Revisar #api,")?.tag)
        assertEquals("api", closedBy("Revisar #api\n")?.tag)
        assertEquals("api", closedBy("#api\t")?.tag)
    }

    @Test
    fun `admite guiones, barras y cifras si hay alguna letra`() {
        assertEquals("front-end/v2_beta", closedBy("#front-end/v2_beta ")?.tag)
    }

    @Test
    fun `una incidencia de GitHub no es una etiqueta`() {
        assertNull(closedBy("Arregla #123 "))
    }

    @Test
    fun `la almohadilla tiene que abrir la palabra`() {
        assertNull(closedBy("Programa en C# "))
        assertNull(closedBy("https://example.com/pagina#seccion "))
        assertNull(closedBy("a,#api "))
    }

    @Test
    fun `lo que no cumple la regla a la primera se queda como esta`() {
        assertNull(closedBy("# "))
        assertNull(closedBy("## "))
        assertNull(closedBy("##api "))
        assertNull(closedBy("Fin de #api. "))
        assertNull(closedBy("Pregunta #api? "))
    }

    @Test
    fun `dentro de codigo no hay etiquetas`() {
        assertNull(closedBy("Mira `#include "))
        assertNull(closedBy("```c\n#define "))
        assertEquals("api", closedBy("```c\n#define\n```\n#api ")?.tag)
        assertEquals("api", closedBy("`codigo` y #api ")?.tag)
    }

    @Test
    fun `la que se queda al final sin cerrar tambien cuenta`() {
        assertEquals("api", InlineTag.trailing("Revisar el login #api")?.tag)
        assertNull(InlineTag.trailing("Revisar el login #api "))
        assertNull(InlineTag.trailing(""))
    }

    @Test
    fun `quitarla se lleva el separador o el hueco de delante`() {
        val typed = "Revisar #api "
        assertEquals("Revisar ", InlineTag.remove(typed, closedBy(typed)!!))

        val middle = "Revisar #api login"
        assertEquals("Revisar login", InlineTag.remove(middle, InlineTag.endingAt(middle, 12)!!))

        val line = "Revisar #api\nSegunda"
        assertEquals("Revisar\nSegunda", InlineTag.remove(line, InlineTag.endingAt(line, 12)!!))

        val last = "Revisar #api"
        assertEquals("Revisar", InlineTag.remove(last, InlineTag.trailing(last)!!))
    }
}
