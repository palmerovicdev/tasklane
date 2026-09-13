package com.tasklane.domain

import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.text.ImageRefParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El parser de referencias a imágenes.
 *
 * No es una curiosidad de expresiones regulares: esta clase es la **única**
 * definición de «qué imagen está referenciada», y el recolector de basura borra
 * ficheros a partir de lo que aquí salga. Un fallo en un sentido pinta un inlay de
 * más; en el otro, borra una captura del usuario.
 */
class ImageRefParserTest {

    private val sha = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test
    fun `una referencia se reconoce y se sabe donde esta`() {
        val body = "Mira esto:\n![](tasklane:$sha)"
        val ref = ImageRefParser.parse(body).single()

        assertEquals(AttachmentId(sha), ref.id)
        assertEquals("![](tasklane:$sha)", body.substring(ref.range.first, ref.range.last + 1))
    }

    @Test
    fun `el texto alternativo no estorba`() {
        val refs = ImageRefParser.parse("![captura del error](tasklane:$sha)")
        assertEquals(AttachmentId(sha), refs.single().id)
    }

    @Test
    fun `la misma imagen dos veces son dos referencias y un solo id`() {
        val body = "![](tasklane:$sha)\ntexto\n![](tasklane:$sha)"
        assertEquals(2, ImageRefParser.parse(body).size)
        assertEquals(setOf(AttachmentId(sha)), ImageRefParser.ids(body))
    }

    @Test
    fun `el orden es el del cuerpo`() {
        val ids = ImageRefParser.ids("![](tasklane:$other)\n![](tasklane:$sha)").toList()
        assertEquals(listOf(AttachmentId(other), AttachmentId(sha)), ids)
    }

    @Test
    fun `un sha a medias no es una referencia`() {
        assertTrue(ImageRefParser.parse("![](tasklane:abc123)").isEmpty())
    }

    @Test
    fun `un enlace normal a una imagen no es una referencia nuestra`() {
        assertTrue(ImageRefParser.parse("![](https://ejemplo.com/a.png)").isEmpty())
    }

    @Test
    fun `el sha se normaliza a minusculas`() {
        val refs = ImageRefParser.parse("![](tasklane:${sha.uppercase()})")
        assertEquals(AttachmentId(sha), refs.single().id)
    }

    /**
     * Al revés que con los enlaces: una referencia dentro de un bloque de código
     * sigue apuntando a un blob de verdad. Ignorarla lo dejaría sin referencias y el
     * recolector lo borraría con el texto todavía nombrándolo.
     */
    @Test
    fun `una referencia dentro de un bloque de codigo cuenta igual`() {
        val body = "```\n![](tasklane:$sha)\n```"
        assertEquals(setOf(AttachmentId(sha)), ImageRefParser.ids(body))
    }

    @Test
    fun `la referencia que se inserta es la que se reconoce`() {
        val text = ImageRefParser.reference(AttachmentId(sha))
        assertEquals(AttachmentId(sha), ImageRefParser.parse(text).single().id)
    }

    @Test
    fun `quitar referencias deja el resto del texto intacto`() {
        assertEquals("antes  despues", ImageRefParser.strip("antes ![](tasklane:$sha) despues"))
    }

    @Test
    fun `un cuerpo sin referencias se devuelve tal cual`() {
        val body = "nada que quitar"
        assertSame(body, ImageRefParser.strip(body))
    }
}
