package com.tasklane.data

import com.tasklane.data.attachment.BlobLayout
import com.tasklane.domain.model.AttachmentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La política de nombres del árbol de adjuntos (§4.1), fijada como lo que es: texto.
 *
 * Es la clase que decide **dónde se escribe un fichero**, así que lo que hay que
 * comprobar es que nada que no sea un hash pueda decidirlo.
 */
class BlobLayoutTest {

    private val sha = "ab" + "cd" + "0".repeat(60)
    private val id = AttachmentId(sha)

    @Test
    fun `los cuatro primeros digitos reparten en dos niveles`() {
        assertEquals("ab" to "cd", BlobLayout.shardsOf(id))
        assertEquals("ab/cd/$sha.png", BlobLayout.relativePath(id))
        assertEquals("ab/cd/$sha.thumb.png", BlobLayout.relativePath(id, thumbnail = true))
    }

    @Test
    fun `lo que no es un sha no decide donde se escribe`() {
        assertNull(BlobLayout.shardsOf(AttachmentId("")))
        assertNull(BlobLayout.shardsOf(AttachmentId("../../etc/passwd")))
        assertNull(BlobLayout.shardsOf(AttachmentId("AB" + sha.substring(2))))
        assertNull(BlobLayout.shardsOf(AttachmentId(sha.dropLast(1))))
    }

    @Test
    fun `una miniatura no es un blob`() {
        assertNull(BlobLayout.idOf("$sha.thumb.png"))
        assertEquals(id, BlobLayout.idOf("$sha.png"))
        assertTrue(BlobLayout.isThumbnail("$sha.thumb.png"))
        assertFalse(BlobLayout.isThumbnail("$sha.png"))
    }

    @Test
    fun `cada fichero sabe de que blob cuelga`() {
        assertEquals(id to false, BlobLayout.ownerOf("$sha.png"))
        assertEquals(id to true, BlobLayout.ownerOf("$sha.thumb.png"))
        assertNull(BlobLayout.ownerOf("notas.txt"))
        assertNull(BlobLayout.ownerOf("a1b2.png"))
        assertNull(BlobLayout.ownerOf("$sha.png.tmp"))
    }

    @Test
    fun `un temporal se reconoce con las dos formas`() {
        assertTrue(BlobLayout.isTemporary("$sha.png.tmp"))
        assertTrue(BlobLayout.isTemporary("$sha.tmp"))
        assertFalse(BlobLayout.isTemporary("$sha.png"))
    }
}
