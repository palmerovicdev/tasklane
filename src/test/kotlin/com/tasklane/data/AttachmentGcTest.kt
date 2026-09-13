package com.tasklane.data

import com.tasklane.data.attachment.AttachmentGc
import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.domain.model.AttachmentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

/**
 * La política de recolección, que es la única parte de borrar ficheros que se puede
 * fijar con un test. Y hay que poder: lo que decide esta función se ejecuta sobre
 * datos del usuario sin preguntar.
 */
class AttachmentGcTest {

    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val grace = Duration.ofHours(24)

    private fun blob(name: String, age: Duration) = AttachmentStore.Blob(
        id = AttachmentId(name),
        path = Paths.get("/tmp/$name.png"),
        modified = now.minus(age),
        size = 1024,
    )

    @Test
    fun `un blob viejo y sin referencias se recoge`() {
        val old = blob("a", Duration.ofDays(3))
        assertEquals(listOf(old), AttachmentGc.collectible(listOf(old), emptySet(), now, grace))
    }

    @Test
    fun `un blob referenciado no se toca por viejo que sea`() {
        val used = blob("a", Duration.ofDays(365))
        assertTrue(AttachmentGc.collectible(listOf(used), setOf(AttachmentId("a")), now, grace).isEmpty())
    }

    /**
     * El caso que justifica el periodo de gracia: se pega una captura, se deshace, y
     * el blob queda sin referencias mientras el diálogo sigue abierto. Recogerlo ahí
     * convertiría un `⌘Z` en una imagen perdida.
     */
    @Test
    fun `lo recien escrito sobrevive aunque no lo referencie nadie`() {
        val fresh = blob("a", Duration.ofMinutes(5))
        assertTrue(AttachmentGc.collectible(listOf(fresh), emptySet(), now, grace).isEmpty())
    }

    @Test
    fun `justo en el limite todavia no se recoge`() {
        val edge = blob("a", grace.minusSeconds(1))
        assertTrue(AttachmentGc.collectible(listOf(edge), emptySet(), now, grace).isEmpty())
    }

    @Test
    fun `una imagen usada por otra tarea salva el blob compartido`() {
        val shared = blob("a", Duration.ofDays(10))
        val orphan = blob("b", Duration.ofDays(10))
        val collected = AttachmentGc.collectible(listOf(shared, orphan), setOf(AttachmentId("a")), now, grace)
        assertEquals(listOf(orphan), collected)
    }

    @Test
    fun `sin blobs no hay nada que hacer`() {
        assertTrue(AttachmentGc.collectible(emptyList(), emptySet(), now, grace).isEmpty())
    }
}
