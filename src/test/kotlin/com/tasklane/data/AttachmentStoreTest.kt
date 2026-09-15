package com.tasklane.data

import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.data.attachment.BlobLayout
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
import java.nio.file.Path

/**
 * El almacén de blobs, ya fragmentado (§4.1).
 *
 * Lo que hay que fijar aquí son dos cosas que se pisan: que lo nuevo se escribe en su
 * hoja `ab/cd`, y que **lo viejo se sigue encontrando en plano mientras no se traslade**.
 * Sin lo segundo, actualizar el plugin dejaría a todo el mundo con las capturas
 * «desaparecidas» hasta que terminara un trabajo de fondo.
 */
class AttachmentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val repo = RepoKey.ROOT
    private val other = RepoKey("api-1234abcd")

    private fun layout() = StorageLayout(tmp.root.toPath().resolve("tasklane"))
    private fun store() = AttachmentStore(layout())

    private fun png(size: Int = 20, color: Color = Color.RED): ByteArray {
        val image = BufferedImage(size, size / 2, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().run {
            setColor(color)
            fillRect(0, 0, size, size / 2)
            dispose()
        }
        return ImageNormalizer.encode(image)
    }

    /** Todos los blobs del árbol, recorriéndolo como lo hace la reconciliación. */
    private fun scanned(store: AttachmentStore, repo: RepoKey): List<AttachmentStore.Blob> {
        val out = ArrayList<AttachmentStore.Blob>()
        store.scan(repo) { out += it.blobs }
        return out
    }

    private fun temporaries(store: AttachmentStore, repo: RepoKey): List<AttachmentStore.Blob> {
        val out = ArrayList<AttachmentStore.Blob>()
        store.scan(repo) { out += it.temporaries }
        return out
    }

    @Test
    fun `guardar devuelve el sha del contenido y lo deja en su hoja`() {
        val store = store()
        val bytes = png()
        val id = store.put(repo, bytes)

        assertEquals(ImageNormalizer.sha256(bytes), id.value)
        assertTrue(store.exists(repo, id))
        assertEquals(bytes.toList(), Files.readAllBytes(store.path(repo, id)).toList())

        // `attachments/ab/cd/<sha>.png`, y ni un fichero suelto en la raíz.
        val root = layout().attachmentsDir(repo)
        assertEquals(root.resolve(id.value.substring(0, 2)).resolve(id.value.substring(2, 4)),
            store.path(repo, id).parent)
        assertTrue(Files.list(root).use { it.allMatch(Files::isDirectory) })
    }

    @Test
    fun `la misma imagen dos veces es un solo fichero`() {
        val store = store()
        val first = store.put(repo, png())
        val second = store.put(repo, png())

        assertEquals(first, second)
        assertEquals(1, scanned(store, repo).size)
    }

    /** Las tareas viven por repositorio; sus imágenes también. */
    @Test
    fun `cada repositorio tiene su propio directorio`() {
        val store = store()
        val id = store.put(repo, png())
        store.put(other, png())

        assertEquals(1, scanned(store, repo).size)
        assertEquals(1, scanned(store, other).size)
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

    // ------------------------------------------------------- lo que ya estaba

    /**
     * **La garantía de la actualización**: una captura escrita por la 2.0 en el
     * directorio plano se sigue encontrando, tal cual, sin haber trasladado nada.
     */
    @Test
    fun `un blob en plano se sigue encontrando`() {
        val store = store()
        val bytes = png()
        val id = AttachmentId(ImageNormalizer.sha256(bytes))
        writeFlat(id, bytes)

        assertTrue(store.exists(repo, id))
        assertEquals(store.legacyPath(repo, id), store.locate(repo, id))
        assertNotNull(store.load(repo, id))
        // Y no se reescribe en la hoja: ya está guardado, el nombre es su contenido.
        assertEquals(id, store.put(repo, bytes))
        assertFalse(Files.exists(store.path(repo, id)))
    }

    @Test
    fun `el traslado mueve lo plano a su hoja, con su miniatura`() {
        val store = store()
        val bytes = png()
        val id = AttachmentId(ImageNormalizer.sha256(bytes))
        writeFlat(id, bytes)
        writeFlat(id, bytes, thumbnail = true)

        val pass = store.relocate(repo)

        assertEquals(2, pass.moved)
        assertFalse(pass.remaining)
        assertTrue(Files.exists(store.path(repo, id)))
        assertTrue(Files.exists(store.thumbnailPath(repo, id)))
        assertFalse(Files.exists(store.legacyPath(repo, id)))
        assertEquals(store.path(repo, id), store.locate(repo, id))
    }

    /** Por tandas: con un millón de ficheros en plano, moverlos todos de una vez es la operación que no termina. */
    @Test
    fun `el traslado va por tandas y dice si queda mas`() {
        val store = store()
        repeat(5) { writeFlat(AttachmentId(ImageNormalizer.sha256(png(size = 20 + it * 2))), png(size = 20 + it * 2)) }

        val first = store.relocate(repo, limit = 2)
        assertEquals(2, first.moved)
        assertTrue(first.remaining)

        val rest = store.relocate(repo, limit = 10)
        assertEquals(3, rest.moved)
        assertFalse(rest.remaining)
        assertEquals(5, scanned(store, repo).size)
    }

    @Test
    fun `el traslado no toca lo que no es un blob`() {
        val store = store()
        val dir = layout().attachmentsDir(repo)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("notas.txt"), "hola")
        Files.writeString(dir.resolve("a1b2.png"), "sha demasiado corto")

        assertEquals(0, store.relocate(repo).moved)
        assertTrue(Files.exists(dir.resolve("notas.txt")))
    }

    // ------------------------------------------------------------ miniaturas

    @Test
    fun `la miniatura vive al lado y no se confunde con un blob`() {
        val store = store()
        val id = store.put(repo, png())
        store.putThumbnail(repo, id, png(size = 8))

        assertTrue(Files.exists(store.thumbnailPath(repo, id)))
        assertNotNull(store.load(repo, id, thumbnail = true))
        // El recorrido ve UN blob, no dos: una miniatura no se referencia ni se recoge
        // por su cuenta, se va con su original.
        assertEquals(1, scanned(store, repo).size)
    }

    @Test
    fun `borrar se lleva el original y su miniatura`() {
        val store = store()
        val id = store.put(repo, png())
        store.putThumbnail(repo, id, png(size = 8))

        assertTrue(store.delete(repo, id))
        assertFalse(store.exists(repo, id))
        assertNull(store.locate(repo, id, thumbnail = true))
        assertTrue(scanned(store, repo).isEmpty())
    }

    /** También si la pareja se quedó en plano: un traslado a medias no puede dejar basura sin dueño. */
    @Test
    fun `borrar tambien limpia lo que quedo en plano`() {
        val store = store()
        val bytes = png()
        val id = AttachmentId(ImageNormalizer.sha256(bytes))
        writeFlat(id, bytes)
        writeFlat(id, bytes, thumbnail = true)

        assertTrue(store.delete(repo, id))
        assertNull(store.locate(repo, id))
        assertNull(store.locate(repo, id, thumbnail = true))
    }

    // -------------------------------------------------------------- recorrido

    @Test
    fun `el recorrido ignora lo que no es un blob`() {
        val store = store()
        store.put(repo, png())
        val dir = layout().attachmentsDir(repo)
        Files.writeString(dir.resolve("notas.txt"), "hola")
        Files.writeString(dir.resolve("a1b2.png"), "sha demasiado corto")

        assertEquals(1, scanned(store, repo).size)
    }

    @Test
    fun `un temporal de una escritura interrumpida se detecta aparte`() {
        val store = store()
        val id = store.put(repo, png())
        val leftover = store.path(repo, id).resolveSibling("${"c".repeat(64)}.png.tmp")
        Files.writeString(leftover, "a medias")

        assertEquals(1, scanned(store, repo).size)
        assertEquals(listOf(leftover), temporaries(store, repo).map { it.path })
    }

    /**
     * El recorrido cuenta lo que encuentra **fuera del árbol**, porque de eso depende que
     * el traslado se pueda dar por terminado: si una versión anterior del plugin vuelve a
     * escribir en plano, la marca deja de ser cierta.
     */
    @Test
    fun `el recorrido distingue lo que quedo en plano`() {
        val store = store()
        store.put(repo, png())
        val bytes = png(size = 30)
        writeFlat(AttachmentId(ImageNormalizer.sha256(bytes)), bytes)

        var blobs = 0
        var flat = 0
        store.scan(repo) {
            blobs += it.blobs.size
            flat += it.flat
        }
        assertEquals(2, blobs)
        assertEquals(1, flat)
    }

    @Test
    fun `el recorrido se puede parar`() {
        val store = store()
        repeat(6) { store.put(repo, png(size = 20 + it * 2)) }

        var seen = 0
        val complete = store.scan(repo, batch = 1, cancelled = { seen >= 2 }) { seen += it.blobs.size }

        assertFalse("un recorrido cancelado no concluye nada", complete)
        assertTrue(seen < 6)
    }

    @Test
    fun `recorrer un repositorio sin adjuntos no falla`() {
        assertTrue(scanned(store(), repo).isEmpty())
    }

    @Test
    fun `las dimensiones salen de la cabecera del png`() {
        val store = store()
        val id = store.put(repo, png(size = 40))
        val size = store.dimensionsOf(store.path(repo, id))

        assertEquals(40, size?.width)
        assertEquals(20, size?.height)
    }

    @Test
    fun `borrar quita el fichero`() {
        val store = store()
        val id = store.put(repo, png())

        assertTrue(store.delete(store.path(repo, id)))
        assertFalse(store.exists(repo, id))
    }

    /**
     * «Borrar todas» vacía el directorio **entero** del repositorio —originales,
     * miniaturas, lo que quedara en plano y los temporales— y dice cuánto se fue. El de
     * otro repositorio ni se toca.
     */
    @Test
    fun `borrar todo vacia el arbol del repositorio y solo ese`() {
        val store = store()
        val kept = store.put(other, png(size = 22))
        val id = store.put(repo, png())
        store.putThumbnail(repo, id, png(size = 10))
        val flat = png(size = 30)
        writeFlat(AttachmentId(ImageNormalizer.sha256(flat)), flat)
        Files.writeString(store.path(repo, id).resolveSibling("${"d".repeat(64)}.png.tmp"), "a medias")

        val wipe = store.deleteAll(repo)

        assertTrue(wipe.complete)
        assertEquals(4L, wipe.files)
        assertTrue(wipe.bytes > 0)
        assertFalse(Files.exists(layout().attachmentsDir(repo)))
        assertTrue("el otro repositorio no se toca", store.exists(other, kept))
        // Y se puede volver a guardar: el directorio se rehace al escribir.
        assertTrue(store.exists(repo, store.put(repo, png())))
    }

    @Test
    fun `borrar todo se puede parar a medias`() {
        val store = store()
        repeat(5) { store.put(repo, png(size = 20 + it * 2)) }

        var seen = 0L
        val wipe = store.deleteAll(repo, cancelled = { seen >= 2 }) { seen = it }

        assertFalse(wipe.complete)
        assertEquals(2L, wipe.files)
        assertEquals(3, scanned(store, repo).size)
    }

    private fun writeFlat(id: AttachmentId, bytes: ByteArray, thumbnail: Boolean = false): Path {
        val dir = layout().attachmentsDir(repo)
        Files.createDirectories(dir)
        val file = dir.resolve(BlobLayout.fileName(id, thumbnail))
        Files.write(file, bytes)
        return file
    }
}
