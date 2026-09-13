package com.tasklane.domain

import com.tasklane.domain.text.UrlShortener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlShortenerTest {

    @Test
    fun `el esquema y el www sobran`() {
        assertEquals("ejemplo.com/a", UrlShortener.shorten("https://ejemplo.com/a"))
        assertEquals("ejemplo.com/a", UrlShortener.shorten("http://ejemplo.com/a"))
        assertEquals("ejemplo.com/a", UrlShortener.shorten("https://www.ejemplo.com/a"))
    }

    @Test
    fun `lo que cabe no se toca`() {
        assertEquals("ejemplo.com/uno/dos/tres", UrlShortener.shorten("https://ejemplo.com/uno/dos/tres"))
    }

    @Test
    fun `el caso de la documentacion`() {
        // El ejemplo de architecture.html §9. Con el presupuesto por defecto cabe
        // entera, asi que el que fuerza el colapso es el de una fila estrecha.
        val url = "https://youtrack.jetbrains.com/issue/ABC-123"
        assertEquals("youtrack.jetbrains.com/issue/ABC-123", UrlShortener.shorten(url))
        assertEquals("youtrack.jetbrains.com/…/ABC-123", UrlShortener.shorten(url, max = 32))
        assertEquals("youtrack.jetbrains.com/…", UrlShortener.shorten(url, max = 31))
    }

    @Test
    fun `el dominio va delante y entero`() {
        val long = "https://ejemplo.com/" + "segmento/".repeat(20) + "final"
        val short = UrlShortener.shorten(long, max = 30)
        assertEquals("ejemplo.com/…/final", short)
        assertTrue("el dominio tiene que ir primero", short.startsWith("ejemplo.com"))
    }

    @Test
    fun `si ni el ultimo segmento cabe queda el dominio`() {
        val url = "https://ejemplo.com/x/un-identificador-larguisimo-que-no-cabe-de-ninguna-manera"
        assertEquals("ejemplo.com/…", UrlShortener.shorten(url, max = 20))
    }

    @Test
    fun `la query y el fragmento no cuentan como ultimo segmento`() {
        assertEquals(
            "ejemplo.com/…/ABC-123",
            UrlShortener.shorten("https://ejemplo.com/issues/ABC-123?filtro=abierto#comentario", max = 25),
        )
    }

    @Test
    fun `un dominio solo que no cabe se recorta por el final`() {
        // Aqui ya no hay nada que preservar: la URL entera es el dominio.
        val short = UrlShortener.shorten("https://subdominio.muy.largo.ejemplo.com", max = 12)
        assertTrue(short.length <= 12)
        assertTrue(short.endsWith("…"))
    }

    @Test
    fun `la barra final no cuenta`() {
        assertEquals("ejemplo.com", UrlShortener.shorten("https://ejemplo.com/"))
    }
}
