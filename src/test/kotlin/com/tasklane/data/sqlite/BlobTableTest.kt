package com.tasklane.data.sqlite

import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.sqlite.StoreFixture.task
import com.tasklane.data.sqlite.StoreFixture.withStore
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * La contabilidad de blobs de la Fase 4 (§4.2): la tabla que sustituye al recorrido del
 * directorio.
 *
 * Lo que hay que fijar aquí es exactamente lo que el recolector se juega: que un blob
 * **referenciado nunca sale** como candidato, que la gracia se respeta, que la
 * reconciliación no pisa la fecha de creación —o la gracia dejaría de existir— y que lo
 * que el recorrido no vio queda marcado como ausente sin necesidad de recordar en
 * memoria los millones que sí vio.
 */
class BlobTableTest {

    private val now = Instant.parse("2026-09-15T12:00:00Z")
    private val old = now.minus(Duration.ofDays(7))
    private val other = RepoKey("api-1234abcd")

    private fun sha(seed: Char) = AttachmentId(seed.toString().repeat(64))

    private fun blob(seed: Char, at: Instant = old, bytes: Long = 1_000, size: Int = 400) =
        BlobRecord(sha(seed), bytes, size, size, at)

    private fun TaskStore.put(vararg tasks: Task) = apply(CONFIG, listOf(Mutation.Upsert(tasks.toList())))

    @Test
    fun `lo que se apunta vuelve a salir como candidato`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), now.toEpochMilli())

        val candidates = store.collectibleBlobs(REPO, now.toEpochMilli(), 10)
        assertEquals(listOf(sha('a')), candidates.map { it.id })
        assertEquals(400, candidates.single().width)
        assertEquals(1_000L, candidates.single().bytes)
    }

    /** La salvaguarda que sostiene el recolector: si una tarea lo nombra, no es candidato. */
    @Test
    fun `un blob referenciado por una tarea no es candidato`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), now.toEpochMilli())
        store.recordBlob(REPO, blob('b'), now.toEpochMilli())
        store.put(task("t1", body = "Con captura ![](tasklane:${sha('a').value})"))

        assertEquals(listOf(sha('b')), store.collectibleBlobs(REPO, now.toEpochMilli(), 10).map { it.id })
    }

    /** Borrar la tarea suelta su blob: el `ON DELETE CASCADE` de `blob_ref` es lo que lo permite. */
    @Test
    fun `al borrar la tarea su imagen vuelve a ser candidata`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), now.toEpochMilli())
        store.put(task("t1", body = "![](tasklane:${sha('a').value})"))
        assertTrue(store.collectibleBlobs(REPO, now.toEpochMilli(), 10).isEmpty())

        store.apply(CONFIG, listOf(Mutation.Delete(listOf(com.tasklane.domain.model.TaskId("t1")))))
        assertEquals(listOf(sha('a')), store.collectibleBlobs(REPO, now.toEpochMilli(), 10).map { it.id })
    }

    @Test
    fun `lo recien escrito no sale, y la tanda tiene tope`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a', at = now), now.toEpochMilli())
        store.recordBlob(REPO, blob('b'), now.toEpochMilli())
        store.recordBlob(REPO, blob('c'), now.toEpochMilli())

        val cutoff = now.minus(Duration.ofHours(24)).toEpochMilli()
        assertEquals(2, store.collectibleBlobs(REPO, cutoff, 10).size)
        assertEquals(1, store.collectibleBlobs(REPO, cutoff, 1).size)
    }

    @Test
    fun `cada repositorio cuenta los suyos`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), now.toEpochMilli())
        store.recordBlob(other, blob('a'), now.toEpochMilli())
        store.recordBlob(other, blob('b'), now.toEpochMilli())

        assertEquals(1, store.blobStatsOf(REPO).count)
        assertEquals(2, store.blobStatsOf(other).count)
        assertEquals(3_000L, store.blobBytes())
    }

    @Test
    fun `olvidar quita la fila`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), now.toEpochMilli())
        store.forgetBlobs(REPO, listOf(sha('a')))

        assertEquals(0, store.blobStatsOf(REPO).count)
    }

    // ------------------------------------------------------- reconciliación

    /**
     * **Re-apuntar no rejuvenece un blob.** Si la reconciliación pisara `created_at`, la
     * gracia de 24 horas pasaría a ser «24 horas desde el último escaneo», o sea nunca, y
     * nada se recogería jamás.
     */
    @Test
    fun `volver a verlo no reinicia el periodo de gracia`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), old.toEpochMilli())
        store.recordBlob(REPO, blob('a'), now.toEpochMilli())

        val cutoff = now.minus(Duration.ofHours(24)).toEpochMilli()
        assertEquals(1, store.collectibleBlobs(REPO, cutoff, 10).size)
    }

    @Test
    fun `adoptar respeta la fecha del fichero y no pisa lo que ya estaba`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a', bytes = 111), now.toEpochMilli())
        store.adoptBlobs(REPO, listOf(blob('a', bytes = 999), blob('b', bytes = 222)), now.toEpochMilli())

        val rows = store.collectibleBlobs(REPO, now.toEpochMilli(), 10).associateBy { it.id }
        assertEquals("no se pisa la fila que ya estaba", 111L, rows[sha('a')]?.bytes)
        assertEquals(222L, rows[sha('b')]?.bytes)
    }

    @Test
    fun `saber cuales ya se conocen es lo que decide a quien adoptar`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), now.toEpochMilli())

        assertEquals(setOf(sha('a').value), store.knownBlobs(REPO, listOf(sha('a'), sha('b'))))
        assertTrue(store.knownBlobs(other, listOf(sha('a'))).isEmpty())
    }

    /**
     * La deriva en la otra dirección: lo que el recorrido no volvió a ver ya no está.
     * Se resuelve con un sello y una comparación, sin recordar nada en memoria.
     */
    @Test
    fun `lo que el recorrido no vio queda marcado como ausente`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), old.toEpochMilli())
        store.recordBlob(REPO, blob('b'), old.toEpochMilli())

        store.seeBlobs(REPO, listOf(sha('a')), now.toEpochMilli())
        val missing = store.markMissingBlobs(REPO, now.toEpochMilli())

        assertEquals(1, missing)
        val stats = store.blobStatsOf(REPO)
        assertEquals(2, stats.count)
        assertEquals(1, stats.missing)
        assertEquals(1, stats.present)
        // Y no se marca dos veces: la pasada siguiente ya no tiene nada que decir.
        assertEquals(0, store.markMissingBlobs(REPO, now.toEpochMilli()))
    }

    @Test
    fun `un blob que vuelve deja de faltar`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), old.toEpochMilli())
        store.markMissingBlobs(REPO, now.toEpochMilli())
        assertEquals(1, store.blobStatsOf(REPO).missing)

        store.seeBlobs(REPO, listOf(sha('a')), now.toEpochMilli())
        assertEquals(0, store.blobStatsOf(REPO).missing)
    }

    // -------------------------------------------------------- cuentas y tareas

    /**
     * **Lo que pesa es lo que está en disco.** Una fila cuyo fichero ya no está cuenta como
     * ausente y no suma: es la cifra que los ajustes enseñan desde la 2.3, y «cuánto
     * ocupan mis imágenes» es una pregunta sobre el disco.
     */
    @Test
    fun `lo ausente se cuenta pero no pesa`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a', bytes = 33_000), old.toEpochMilli())
        store.recordBlob(REPO, blob('b', bytes = 407_000), now.toEpochMilli())
        store.markMissingBlobs(REPO, now.toEpochMilli())

        val stats = store.blobStatsOf(REPO)
        assertEquals(2, stats.count)
        assertEquals(1, stats.missing)
        assertEquals(407_000L, stats.bytes)
    }

    /** Vaciar la tabla de un repositorio va por tandas y no toca la de otro. */
    @Test
    fun `olvidar las filas de un repositorio no toca las de otro`() = withStore { store, _ ->
        store.recordBlob(REPO, blob('a'), now.toEpochMilli())
        store.recordBlob(REPO, blob('b'), now.toEpochMilli())
        store.recordBlob(other, blob('c'), now.toEpochMilli())

        assertEquals(1, store.forgetBlobRows(REPO, limit = 1))
        assertEquals(1, store.forgetBlobRows(REPO, limit = 1))
        assertEquals(0, store.forgetBlobRows(REPO, limit = 1))
        assertEquals(0, store.blobStatsOf(REPO).count)
        assertEquals(1, store.blobStatsOf(other).count)
    }

    @Test
    fun `una tarea de mantenimiento recuerda cuando se hizo`() = withStore { store, _ ->
        assertEquals(null, store.chore("blob.fsck:root"))

        store.saveChore("blob.fsck:root", now.toEpochMilli(), 42)
        assertEquals(now.toEpochMilli(), store.chore("blob.fsck:root")?.at)
        assertEquals(42L, store.chore("blob.fsck:root")?.n)

        store.saveChore("blob.fsck:root", now.toEpochMilli() + 1, 7)
        assertEquals(7L, store.chore("blob.fsck:root")?.n)

        // Olvidarla es lo que vuelve a dejar la tarea pendiente. Lo usa la
        // reconciliación cuando descubre que el traslado ya no está terminado.
        store.forgetChore("blob.fsck:root")
        assertEquals(null, store.chore("blob.fsck:root"))
    }

    /**
     * **Las tablas de la Fase 4 entran sin subir la versión del esquema**, y eso es una
     * decisión, no un descuido: subirla dejaría en **solo lectura** cualquier proyecto
     * abierto después con una versión anterior del plugin — se bloquearía editar tareas
     * para proteger dos tablas que la reconciliación sabe reconstruir. Ver el KDoc de
     * [TaskSchema.VERSION].
     */
    @Test
    fun `las tablas nuevas no suben la version del esquema`() = withStore { store, db ->
        assertEquals(1, TaskSchema.VERSION)
        assertEquals(TaskSchema.VERSION, db.version)
        assertFalse(db.readOnly)

        // Y están puestas: las pone el DDL al abrir, sin una línea de migración.
        store.recordBlob(REPO, blob('a'), now.toEpochMilli())
        store.saveChore("blob.fsck:root", now.toEpochMilli(), 0)
        assertEquals(1, store.blobStatsOf(REPO).count)
        assertEquals(now.toEpochMilli(), store.chore("blob.fsck:root")?.at)
    }
}
