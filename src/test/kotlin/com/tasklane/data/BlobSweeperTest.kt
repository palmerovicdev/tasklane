package com.tasklane.data

import com.tasklane.data.attachment.AttachmentGc
import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.attachment.BlobSweeper
import com.tasklane.data.attachment.ImageNormalizer
import com.tasklane.data.sqlite.StoreFixture
import com.tasklane.data.sqlite.TaskStore
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Quitar imágenes sobre una base y un directorio **de verdad**: el recolector del
 * mantenimiento y los dos botones de los ajustes de la 2.3.
 *
 * Lo que se juega aquí es lo único que no se puede deshacer: que una imagen que alguna
 * tarea nombra no se borra nunca por «no usada», que borrar es fichero **y** fila, y que
 * todo es de un repositorio y no del proyecto.
 */
class BlobSweeperTest {

    private val repo = StoreFixture.REPO
    private val other = RepoKey("api-1234abcd")
    private val now = Instant.parse("2026-09-15T12:00:00Z")

    private class Fixture(val files: AttachmentStore, val tasks: TaskStore, val sweeper: BlobSweeper, val root: java.nio.file.Path)

    private fun <T> withSweeper(block: Fixture.() -> T): T = StoreFixture.withStore { tasks, _ ->
        val root = Files.createTempDirectory("tasklane-sweep")
        try {
            val files = AttachmentStore(StorageLayout(root))
            val ledger = object : BlobSweeper.Ledger {
                override fun collectible(repo: RepoKey, before: Instant, limit: Int) =
                    tasks.collectibleBlobs(repo, before.toEpochMilli(), limit)

                override fun referencedAmong(repo: RepoKey, ids: List<AttachmentId>) =
                    tasks.referencedAmong(repo, ids).mapTo(HashSet(), ::AttachmentId)

                override fun forget(repo: RepoKey, ids: List<AttachmentId>) = tasks.write { tasks.forgetBlobs(repo, ids) }

                override fun forgetBatch(repo: RepoKey): Int = tasks.forgetBlobRows(repo, limit = 2)
            }
            Fixture(files, tasks, BlobSweeper(files, ledger), root).block()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Una imagen guardada como lo hace el servicio: fichero, miniatura si toca y fila. */
    private fun Fixture.image(repo: RepoKey, seed: Int, at: Instant, size: Int = 600): AttachmentId {
        val pixels = BufferedImage(size, size / 2, BufferedImage.TYPE_INT_ARGB).also {
            it.createGraphics().run {
                color = Color(seed * 37 % 255, seed * 91 % 255, seed * 13 % 255)
                fillRect(0, 0, size, size / 2)
                dispose()
            }
        }
        val bytes = ImageNormalizer.encode(pixels)
        val id = files.put(repo, bytes)
        ImageNormalizer.thumbnail(pixels)?.let { files.putThumbnail(repo, id, it) }
        tasks.write { tasks.recordBlob(repo, BlobRecord(id, bytes.size.toLong(), size, size / 2, at), at.toEpochMilli()) }
        return id
    }

    private fun Fixture.useIn(taskId: String, ids: List<AttachmentId>, repo: RepoKey = this@BlobSweeperTest.repo) {
        val body = "Captura " + ids.joinToString(" ") { "![](tasklane:${it.value})" }
        tasks.apply(StoreFixture.CONFIG, listOf(Mutation.Upsert(listOf(StoreFixture.task(taskId, body = body, repo = repo)))))
    }

    @Test
    fun `el recolector respeta la gracia y lo referenciado`() = withSweeper {
        val old = now.minus(Duration.ofDays(3))
        val used = image(repo, 1, old)
        val orphan = image(repo, 2, old)
        val fresh = image(repo, 3, now.minusSeconds(60))
        useIn("t1", listOf(used))

        val swept = sweeper.collect(repo, now)

        assertEquals(1, swept.files)
        assertTrue(swept.complete)
        assertTrue(files.exists(repo, used))
        assertFalse(files.exists(repo, orphan))
        assertTrue("lo recién pegado sobrevive a la gracia", files.exists(repo, fresh))
        assertEquals(2, tasks.blobStatsOf(repo).count)
    }

    /**
     * «Borrar las no usadas» es el recolector **sin gracia**: se lleva también lo recién
     * pegado que nadie nombra, con su miniatura y su fila. Lo nombrado se queda, y el otro
     * repositorio ni se mira.
     */
    @Test
    fun `borrar las no usadas se lleva lo recien pegado pero nunca lo que se usa`() = withSweeper {
        val used = image(repo, 1, now.minusSeconds(5))
        val orphan = image(repo, 2, now.minusSeconds(5))
        val elsewhere = image(other, 3, now.minus(Duration.ofDays(9)))

        useIn("t1", listOf(used))
        val swept = sweeper.collect(repo, now, grace = Duration.ZERO)

        assertEquals(1, swept.files)
        assertTrue(swept.bytes > 0)
        assertTrue(files.exists(repo, used))
        assertTrue("la miniatura de lo usado se queda", files.locate(repo, used, thumbnail = true) != null)
        assertFalse(files.exists(repo, orphan))
        assertEquals("la miniatura se va con su original", null, files.locate(repo, orphan, thumbnail = true))
        assertEquals(1, tasks.blobStatsOf(repo).count)
        assertTrue("otro repositorio no se toca", files.exists(other, elsewhere))
        assertEquals(1, tasks.blobStatsOf(other).count)
    }

    /**
     * Una fila cuyo fichero ya no está —la marca la reconciliación— también se va: miente
     * sobre lo que hay, y borrar un fichero que no existe no le hace nada a nadie.
     */
    @Test
    fun `lo ausente se limpia de la tabla`() = withSweeper {
        val gone = image(repo, 1, now.minusSeconds(5))
        files.delete(repo, gone)
        tasks.write { tasks.markMissingBlobs(repo, now.plusSeconds(1).toEpochMilli()) }

        sweeper.collect(repo, now.plusSeconds(2), grace = Duration.ZERO)

        assertEquals(0, tasks.blobStatsOf(repo).count)
    }

    /**
     * «Borrar todas» se lleva también lo que se usa —es lo que se confirma en el diálogo—,
     * pero **no toca las tareas**: su cuerpo y sus referencias siguen diciendo que ahí había
     * una imagen, y la tarjeta pinta el hueco.
     */
    @Test
    fun `borrar todas vacia ficheros y filas del repositorio y deja las tareas`() = withSweeper {
        val used = image(repo, 1, now)
        repeat(4) { image(repo, 10 + it, now) }
        val elsewhere = image(other, 3, now)
        useIn("t1", listOf(used))

        val swept = sweeper.deleteAll(repo)

        assertTrue(swept.complete)
        assertFalse(files.exists(repo, used))
        assertEquals(0, tasks.blobStatsOf(repo).count)
        assertEquals("las referencias de la tarea siguen ahí", setOf(used.value), tasks.referencedAmong(repo, listOf(used)))
        assertTrue(files.exists(other, elsewhere))
        assertEquals(1, tasks.blobStatsOf(other).count)
    }

    /** Parado a medias, las filas no se tocan y se pide la reconciliación. */
    @Test
    fun `borrar todas cancelado no deja peso invisible`() = withSweeper {
        repeat(4) { image(repo, 20 + it, now) }
        var incomplete = false

        val swept = sweeper.deleteAll(repo, cancelled = { true }, onIncomplete = { incomplete = true })

        assertFalse(swept.complete)
        assertTrue(incomplete)
        assertEquals("las filas siguen contando lo que quedó en disco", 4, tasks.blobStatsOf(repo).count)
    }

    @Test
    fun `sin nada que borrar no pasa nada`() = withSweeper {
        assertEquals(0, sweeper.collect(repo, now, grace = Duration.ZERO).files)
        assertTrue(sweeper.deleteAll(repo).complete)
        assertEquals(AttachmentGc.DEFAULT_GRACE, Duration.ofHours(24))
    }
}
