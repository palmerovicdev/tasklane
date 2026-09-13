package com.tasklane.data

import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.data.attachment.ImageNormalizer
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files

class AttachmentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val repo = RepoKey.ROOT
    private val other = RepoKey("api-1234abcd")

    private fun layout() = StorageLayout(tmp.root.toPath().resolve("tasklane"))
    private fun store() = AttachmentStore(layout())

    private fun png(color: Color = Color.RED): ByteArray {
        val image = BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().run {
            setColor(color)
            fillRect(0, 0, 20, 10)
            dispose()
        }
        return ImageNormalizer.normalize(image, 1600)
    }

    @Test
    fun `guardar devuelve el sha del contenido y deja el fichero donde toca`() {
        val store = store()
        val bytes = png()
        val id = store.put(repo, bytes)

        assertEquals(ImageNormalizer.sha256(bytes), id.value)
        assertTrue(store.exists(repo, id))
        assertEquals(bytes.toList(), Files.readAllBytes(store.path(repo, id)).toList())
    }

    @Test
    fun `la misma imagen dos veces es un solo fichero`() {
        val store = store()
        val first = store.put(repo, png())
        val second = store.put(repo, png())

        assertEquals(first, second)
        assertEquals(1, store.list(repo).size)
    }

    /** Las tareas viven por repositorio; sus imágenes también. */
    @Test
    fun `cada repositorio tiene su propio directorio`() {
        val store = store()
        val id = store.put(repo, png())
        store.put(other, png())

        assertEquals(1, store.list(repo).size)
        assertEquals(1, store.list(other).size)
        assertTrue(store.exists(other, id))
    }

    @Test
    fun `los datos quedan fuera de VCS`() {
        store().put(repo, png())
        val ignore = layout().root.resolve(".gitignore")
        assertTrue(Files.exists(ignore))
        assertTrue(Files.readString(ignore).contains("*"))
    }

    @Test
    fun `lo guardado se puede volver a decodificar`() {
        val store = store()
        val id = store.put(repo, png())
        val image = store.load(repo, id)

        assertNotNull(image)
        assertEquals(20, image!!.width)
        assertEquals(10, image.height)
    }

    @Test
    fun `un blob que no esta no revienta la lectura`() {
        assertNull(store().load(repo, AttachmentId("f".repeat(64))))
    }

    @Test
    fun `un fichero corrupto se trata como ausente`() {
        val store = store()
        val id = store.put(repo, png())
        Files.write(store.path(repo, id), "no soy un png".toByteArray())

        assertNull(store.load(repo, id))
    }

    @Test
    fun `el listado ignora lo que no es un blob`() {
        val store = store()
        store.put(repo, png())
        val dir = layout().attachmentsDir(repo)
        Files.writeString(dir.resolve("notas.txt"), "hola")
        Files.writeString(dir.resolve("a1b2.png"), "sha demasiado corto")

        assertEquals(1, store.list(repo).size)
    }

    @Test
    fun `un temporal de una escritura interrumpida se detecta aparte`() {
        val store = store()
        store.put(repo, png())
        val dir = layout().attachmentsDir(repo)
        Files.writeString(dir.resolve("${"c".repeat(64)}.tmp"), "a medias")

        assertEquals(1, store.list(repo).size)
        assertEquals(1, store.orphanTemporaries(repo).size)
    }

    @Test
    fun `borrar quita el fichero`() {
        val store = store()
        val id = store.put(repo, png())

        assertTrue(store.delete(store.path(repo, id)))
        assertFalse(store.exists(repo, id))
    }

    @Test
    fun `listar un repositorio sin adjuntos no falla`() {
        assertTrue(store().list(repo).isEmpty())
    }
}
