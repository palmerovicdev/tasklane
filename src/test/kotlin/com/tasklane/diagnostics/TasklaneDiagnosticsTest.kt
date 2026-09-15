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

    @Test
    fun `pesa el fichero de tareas y los blobs del disco`() {
        val dir = Files.createTempDirectory("tasklane-diag")
        try {
            val layout = StorageLayout(dir)
            val tasks = SyntheticCorpus.tasks(30, blobPool = 30)
            SyntheticCorpus.writeTasksXml(layout.tasksFile(repo), count = 30, blobPool = 30)
            // Sólo diez de los treinta blobs: los otros veinte están referenciados y
            // no existen, que es exactamente el caso que la tarjeta pinta como hueco.
            SyntheticBlobs.writeAll(layout.attachmentsDir(repo), 10)

            val only = TasklaneDiagnostics.collect(layout, mapOf(repo to statsOf(tasks))).repos.single()

            // El `tasks.xml` ya no es lo que pesa: desde la Fase 3 es la base, y el
            // fichero viejo cuenta como copia conservada.
            assertTrue("el fichero conservado pesa", only.backupBytes > 30 * 2_000)
            assertEquals(10, only.blobCount)
            assertTrue("los blobs pesan", only.blobBytes > 10 * 10_000)
            assertFalse(only.blobsTruncated)
            assertEquals(only.tasksFileBytes + only.backupBytes + only.blobBytes, only.totalBytes)
        } finally {
            dir.toFile().deleteRecursively()
        }
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
