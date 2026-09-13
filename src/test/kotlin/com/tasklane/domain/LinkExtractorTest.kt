package com.tasklane.domain

import com.tasklane.domain.text.LinkExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkExtractorTest {

    private fun urls(body: String) = LinkExtractor.extract(body).map { it.url }

    @Test
    fun `una URL suelta`() {
        val link = LinkExtractor.extract("Revisar https://ejemplo.com/a").single()
        assertEquals("https://ejemplo.com/a", link.url)
        assertEquals("ejemplo.com/a", link.display)
        assertEquals("https://ejemplo.com/a", "Revisar https://ejemplo.com/a".substring(link.range))
    }

    @Test
    fun `www se completa a https`() {
        assertEquals(listOf("https://www.ejemplo.com/a"), urls("mirar www.ejemplo.com/a"))
    }

    @Test
    fun `el enlace Markdown se muestra con su texto`() {
        val body = "Ver [el ticket](https://ejemplo.com/issue/ABC-123) antes del viernes"
        val link = LinkExtractor.extract(body).single()
        assertEquals("https://ejemplo.com/issue/ABC-123", link.url)
        assertEquals("el ticket", link.display)
        // El rango cubre la sintaxis entera: la fila pinta «el ticket» en su lugar.
        assertEquals("[el ticket](https://ejemplo.com/issue/ABC-123)", body.substring(link.range))
    }

    @Test
    fun `solo http y https`() {
        // El caso que justifica la regla: lo que se pinta como enlace es lo que un
        // clic va a ejecutar.
        assertTrue(urls("[abrir](file:///etc/passwd)").isEmpty())
        assertTrue(urls("[abrir](javascript:alert(1))").isEmpty())
        assertTrue(urls("mailto:alguien@ejemplo.com").isEmpty())
    }

    @Test
    fun `una URL dentro de un bloque de codigo no se extrae`() {
        val body = """
            Documentar el endpoint
            ```
            curl https://api.ejemplo.com/v1/tareas
            ```
            y avisar
        """.trimIndent()
        assertTrue(urls(body).isEmpty())
    }

    @Test
    fun `una URL entre comillas invertidas tampoco`() {
        assertTrue(urls("usar `https://api.ejemplo.com/v1` como base").isEmpty())
    }

    @Test
    fun `una comilla invertida suelta no esconde el resto de la linea`() {
        assertEquals(listOf("https://ejemplo.com/a"), urls("el `flag y https://ejemplo.com/a"))
    }

    @Test
    fun `un bloque sin cerrar llega hasta el final`() {
        val body = "Notas\n```\nhttps://ejemplo.com/a\ntodavia escribiendo"
        assertTrue(urls(body).isEmpty())
    }

    @Test
    fun `la puntuacion de la frase no es parte de la URL`() {
        assertEquals(listOf("https://ejemplo.com/a"), urls("Mira https://ejemplo.com/a."))
        assertEquals(listOf("https://ejemplo.com/a"), urls("Mira https://ejemplo.com/a, y luego otra cosa"))
        assertEquals(listOf("https://ejemplo.com/a"), urls("(ver https://ejemplo.com/a)"))
    }

    @Test
    fun `un parentesis equilibrado si es parte de la URL`() {
        assertEquals(
            listOf("https://es.wikipedia.org/wiki/Ada_(lenguaje)"),
            urls("leer https://es.wikipedia.org/wiki/Ada_(lenguaje)"),
        )
    }

    @Test
    fun `varios enlaces conservan el orden del cuerpo`() {
        val body = "primero https://a.ejemplo.com/1 y luego [segundo](https://b.ejemplo.com/2)"
        assertEquals(listOf("https://a.ejemplo.com/1", "https://b.ejemplo.com/2"), urls(body))
    }

    @Test
    fun `un cuerpo sin enlaces no devuelve nada`() {
        assertTrue(urls("Comprar pan y leche").isEmpty())
        assertTrue(urls("").isEmpty())
    }

    private fun String.substring(range: IntRange) = substring(range.first, range.last + 1)
}
