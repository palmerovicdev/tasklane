package com.tasklane.diagnostics

import com.tasklane.bench.SyntheticBlobs
import com.tasklane.bench.SyntheticCorpus
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * El informe de *Tasklane: Diagnostics*, fijado sin arrancar un IDE.
 *
 * Importa más de lo que parece: este texto es lo que un usuario pega en un issue
 * cuando dice que el plugin va lento, y un informe que se equivoca al contar dirige
 * la investigación al sitio equivocado.
 */
class TasklaneDiagnosticsTest {

    private val repo = RepoKey.ROOT

    /**
     * Las cuentas de un repositorio, como las daría el almacén.
     *
     * Desde la Fase 3 el informe recibe agregados y no tareas —siete `count(*)` en vez
     * de un recorrido del corpus—, así que aquí se calculan a mano sobre el corpus
     * sintético. Que la consulta de SQLite dé lo mismo lo fija `TaskStoreTest`.
     */
    private fun statsOf(tasks: List<Task>) = TaskStats(
        tasks = tasks.size,
        bodyChars = tasks.sumOf { it.body.length.toLong() },
        anchors = tasks.sumOf { it.anchors.size },
        tags = tasks.sumOf { it.tags.size },
        imageRefs = tasks.sumOf { it.attachments.size },
        distinctImages = tasks.flatMap { task -> task.attachments.map { it.id.value } }.distinct().size,
        orphans = TaskReducer.orphans(tasks).size,
    )

    @Test
    fun `cuenta tareas, anclas, etiquetas y referencias`() {
        val tasks = SyntheticCorpus.tasks(20, blobPool = 20)
        val report = TasklaneDiagnostics.collect(null, mapOf(repo to statsOf(tasks)))

        val only = report.repos.single()
        assertEquals(20, only.tasks)
        assertEquals(20 * SyntheticCorpus.ANCHORS, only.anchors)
        assertEquals(20 * SyntheticCorpus.TAGS, only.tags)
        assertEquals(20 * SyntheticCorpus.IMAGES, only.imageRefs)
        assertEquals(20, report.tasks)
        assertTrue("el cuerpo son caracteres de verdad", only.bodyChars > 20 * 1_500)
    }

    /**
     * La deduplicación es la cifra con la que el §1.6 baja los 4 TB a 400 GB, y hasta
     * ahora no se podía mirar en un proyecto real. Con `blobPool` igual al número de
     * tareas, doscientas referencias apuntan a veinte blobs.
     */
    @Test
    fun `mide la deduplicacion real de las imagenes`() {
        val tasks = SyntheticCorpus.tasks(20, blobPool = 20)
        val only = TasklaneDiagnostics.collect(null, mapOf(repo to statsOf(tasks))).repos.single()

        assertEquals(200, only.imageRefs)
        assertEquals(20, only.distinctImages)
        assertTrue(DiagnosticsReport.render(report(tasks)).contains("10.0:1 dedup"))
    }

    /**
     * Un repositorio sin leer **no aparece**. Es la misma regla que protege al
     * recolector de adjuntos: una lista vacía porque no se ha abierto el fichero no
     * significa que no haya nada, significa que no se sabe — y un informe que dice
     * «0 tareas» de un repositorio lleno manda a mirar al sitio equivocado.
     */
    @Test
    fun `solo informa de lo que esta cargado`() {
        val report = TasklaneDiagnostics.collect(null, emptyMap())
        assertTrue(report.repos.isEmpty())
        assertEquals(0, report.tasks)
        assertTrue(DiagnosticsReport.render(report).contains("(none loaded yet)"))
    }

    /**
     * **Los blobs ya no se pesan recorriendo el directorio** (§4.2): la cifra viene de la
     * tabla, y lo único que sigue yendo al disco son los ficheros del formato viejo y la
     * base. Es el cambio de la Fase 4 que se puede comprobar desde fuera: aquí se
     * escriben diez blobs de verdad y el informe **no** los ve, porque nadie los ha
     * apuntado.
     */
    @Test
    fun `pesa los ficheros del disco y las imagenes de la tabla`() {
        val dir = Files.createTempDirectory("tasklane-diag")
        try {
            val layout = StorageLayout(dir)
            val tasks = SyntheticCorpus.tasks(30, blobPool = 30)
            SyntheticCorpus.writeTasksXml(layout.tasksFile(repo), count = 30, blobPool = 30)
            SyntheticBlobs.writeAll(layout.attachmentsDir(repo), 10)

            val onDiskOnly = TasklaneDiagnostics.collect(layout, mapOf(repo to statsOf(tasks))).repos.single()
            assertEquals("lo que no está apuntado no se cuenta", 0, onDiskOnly.blobCount)

            val blobs = BlobStats(count = 10, bytes = 330_000, oversized = 2, oversizedBytes = 800_000)
            val only = TasklaneDiagnostics
                .collect(layout, mapOf(repo to statsOf(tasks)), blobs = mapOf(repo to blobs))
                .repos.single()

            // El `tasks.xml` ya no es lo que pesa: desde la Fase 3 es la base, y el
            // fichero viejo cuenta como copia conservada.
            assertTrue("el fichero conservado pesa", only.backupBytes > 30 * 2_000)
            assertEquals(10, only.blobCount)
            assertEquals(330_000L, only.blobBytes)
            assertEquals(only.tasksFileBytes + only.backupBytes + only.blobBytes, only.totalBytes)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Mientras la reconciliación no haya pasado, lo que la tabla sabe está **incompleto**
     * y el informe tiene que decirlo. Un «0 imágenes» sobre un directorio lleno mandaría
     * a mirar al sitio equivocado, que es justo lo que este informe existe para evitar.
     */
    @Test
    fun `avisa de que las cifras de imagenes aun no estan reconciliadas`() {
        val stats = mapOf(repo to statsOf(SyntheticCorpus.tasks(5)))
        val pending = TasklaneDiagnostics.collect(null, stats)
        assertTrue(pending.pending)
        assertTrue(DiagnosticsReport.render(pending).contains("still being reconciled"))

        val done = TasklaneDiagnostics.collect(null, stats, reconciled = setOf(repo))
        assertFalse(done.pending)
        assertFalse(DiagnosticsReport.render(done).contains("still being reconciled"))
    }

    /**
     * Las capturas que ya estaban guardadas cuando el tope bajó a 400 px **no se tocan**
     * —reescalarlas cambiaría su SHA, que es su nombre—, así que lo mínimo que se le debe
     * a quien tenga tres gigas de ellas es decirle cuántas son.
     */
    @Test
    fun `dice cuantas imagenes estan por encima del tope de hoy`() {
        val text = DiagnosticsReport.render(
            TasklaneDiagnostics.collect(
                null,
                mapOf(repo to statsOf(SyntheticCorpus.tasks(5))),
                blobs = mapOf(repo to BlobStats(count = 50, bytes = 20_000_000, oversized = 7, oversizedBytes = 2_800_000)),
                imageMaxSize = 400,
            ),
        )
        assertTrue(text, text.contains("7 image(s) above the 400 px cap"))
    }

    /**
     * La copia y la comprobación de la base (Fase 5): lo que alguien necesita saber antes
     * de tocar nada a mano en `.idea/tasklane`. Y la copia cuenta como sitio ocupado.
     */
    @Test
    fun `el informe dice si hay copia de la base y como salio la ultima comprobacion`() {
        val stats = mapOf(repo to statsOf(SyntheticCorpus.tasks(5)))
        val at = java.time.LocalDateTime.of(2026, 9, 15, 10, 2).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        val none = DiagnosticsReport.render(TasklaneDiagnostics.collect(null, stats))
        assertTrue(none, none.contains("Backup           none yet"))
        assertTrue(none, none.contains("never needed a check"))

        val report = TasklaneDiagnostics.collect(
            null,
            stats,
            store = StoreHealth(backupAt = at, backupBytes = 3L * 1024 * 1024, checkedAt = at, problems = 0),
        )
        val ok = DiagnosticsReport.render(report)
        assertTrue(ok, ok.contains("Backup           2026-09-15 10:02, 3.0 MB"))
        assertTrue(ok, ok.contains("ok, checked on 2026-09-15 10:02"))
        assertEquals(3L * 1024 * 1024, report.totalBytes)

        val damaged = DiagnosticsReport.render(
            TasklaneDiagnostics.collect(null, stats, store = StoreHealth(checkedAt = at, problems = 4)),
        )
        assertTrue(damaged, damaged.contains("DAMAGED: 4 problem(s)"))

        val pending = DiagnosticsReport.render(TasklaneDiagnostics.collect(null, stats, store = StoreHealth(pending = true)))
        assertTrue(pending, pending.contains("check pending"))
    }

    /** La cuota del §4.5 se enseña siempre que esté puesta, y se marca cuando se cruza. */
    @Test
    fun `el informe enseña la cuota y dice cuando se pasa`() {
        val stats = mapOf(repo to statsOf(SyntheticCorpus.tasks(5)))
        val quota = 1024L * 1024 * 1024

        val under = DiagnosticsReport.render(
            TasklaneDiagnostics.collect(null, stats, blobs = mapOf(repo to BlobStats(1, 1024)), quotaBytes = quota),
        )
        assertTrue(under, under.contains("of 1.0 GB quota"))
        assertFalse(under, under.contains("OVER"))

        val over = DiagnosticsReport.render(
            TasklaneDiagnostics.collect(
                null,
                stats,
                blobs = mapOf(repo to BlobStats(1, 2 * quota)),
                quotaBytes = quota,
            ),
        )
        assertTrue(over, over.contains("OVER"))
    }

    /** Sin `StorageLayout` —proyecto por defecto, tests ligeros— no se cae: informa lo que sabe. */
    @Test
    fun `sin layout informa solo de lo que hay en memoria`() {
        val only = TasklaneDiagnostics.collect(null, mapOf(repo to statsOf(SyntheticCorpus.tasks(5)))).repos.single()
        assertEquals(5, only.tasks)
        assertEquals(0L, only.tasksFileBytes)
        assertEquals(0, only.blobCount)
    }

    @Test
    fun `los pesos se escriben para leerlos`() {
        assertEquals("512 B", TasklaneDiagnostics.humanBytes(512))
        assertEquals("1.0 KB", TasklaneDiagnostics.humanBytes(1024))
        assertEquals("286 MB", TasklaneDiagnostics.humanBytes(300_000_000))
        assertEquals("2.7 GB", TasklaneDiagnostics.humanBytes(2_900_000_000))
    }

    private fun report(tasks: List<Task>) =
        TasklaneDiagnostics.collect(null, mapOf(repo to statsOf(tasks)))
}
