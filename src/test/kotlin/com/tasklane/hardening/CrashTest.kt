package com.tasklane.hardening

import com.tasklane.bench.SyntheticCorpus
import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.attachment.BlobSweeper
import com.tasklane.data.sqlite.Sql
import com.tasklane.data.sqlite.StoreAudit
import com.tasklane.data.sqlite.TaskDb
import com.tasklane.data.sqlite.TaskImport
import com.tasklane.data.sqlite.TaskStore
import com.tasklane.data.sqlite.TasksXmlReader
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * **§6.1 — pruebas de caída.** Se mata el proceso a mitad de una transacción, de una
 * migración, de una recolección y de una copia de la base, y se comprueba que la apertura
 * siguiente está sana: la base, **y la contabilidad que el plugin lleva a mano** —ver
 * [StoreAudit]—, y que lo que no terminó se puede terminar.
 *
 * Cada escenario se repite [rounds] veces con un momento de muerte distinto, sorteado con
 * una semilla que se imprime: un fallo se reproduce con esa semilla. Por defecto son dos
 * vueltas, para que el build normal no tarde; `-PcrashRounds=50` es la versión de verdad.
 *
 * **Lo que se afirma y lo que no.** Con `synchronous = NORMAL` y WAL, una transacción
 * confirmada sobrevive a la muerte **del proceso** —el diario está en la caché del sistema
 * operativo—, así que eso se exige. Lo que `NORMAL` no promete es sobrevivir a un corte de
 * luz, y eso no se puede provocar desde un test: lo cubre la recuperación del §6.2.
 */
class CrashTest {

    private val rounds = System.getProperty("tasklane.crash.rounds")?.toIntOrNull() ?: 2
    private val seed = System.getProperty("tasklane.crash.seed")?.toLongOrNull() ?: System.nanoTime()

    private fun <T> withDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("tasklane-crash")
        return try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun round(scenario: String, n: Int): Random {
        val roundSeed = seed + n * 7919L
        println("[$scenario] vuelta ${n + 1}/$rounds · semilla -Dtasklane.crash.seed=$roundSeed")
        return Random(roundSeed)
    }

    private fun auditClean(db: TaskDb) {
        val problems = StoreAudit.check(db.writer)
        assertEquals("la base quedó con problemas tras la caída: $problems", emptyList<String>(), problems)
    }

    // ------------------------------------------------------------------ comandos

    /**
     * **A mitad de una transacción.** El hijo aplica comandos en bucle con el camino de
     * escritura de producción; se le mata justo después de anunciar que empieza la
     * transacción `k`, con un retraso sorteado dentro de lo que dura una.
     *
     * Después: la última transacción confirmada está entera, la que estaba en marcha no
     * dejó ni una fila, y los contadores, el índice de texto y las etiquetas cuadran.
     */
    @Test
    fun `morir a mitad de una transaccion no deja nada a medias`() {
        var midway = 0
        repeat(rounds) { n -> commandsRound(n) { midway += it } }
        println("[comandos] $midway de $rounds muertes cayeron dentro de una transacción")
    }

    private fun commandsRound(n: Int, onMidway: (Int) -> Unit) {
        val random = round("comandos", n)
        withDir { dir ->
            var lastCommit = 0
            ChildJvm.start(CrashWorker.MAIN, "commands", dir.toString()).use { child ->
                val target = random.nextInt(3, 7)
                // Lo que dura una transacción, medido por el propio hijo en la anterior: el
                // retraso se sortea dentro, que es donde la muerte tiene algo que romper.
                val lasts = child.await { it.startsWith("COMMIT ${target - 1} ") }.substringAfterLast(' ').toLong()
                child.await { it == "BEGIN $target" }
                Thread.sleep(random.nextLong(0, lasts.coerceAtLeast(1)))
                child.kill()
                lastCommit = child.output.filter { it.startsWith("COMMIT ") }.maxOfOrNull { it.split(' ')[1].toInt() } ?: 0
                onMidway(if (lastCommit < target) 1 else 0)
            }

            val db = TaskDb.open(dir)!!
            try {
                assertTrue("el diario delata la caída", db.dirty)
                val store = TaskStore(db)
                val k = (store.chore(Commands.CHORE)?.n ?: 0L).toInt()
                assertTrue("lo confirmado sobrevive: el hijo dijo COMMIT $lastCommit y la base llega a $k", k >= lastCommit)

                for (batch in 1..k + 1) {
                    val (total, done) = db.writer.first(
                        "SELECT count(*), coalesce(sum(state = ?), 0) FROM task WHERE id GLOB ?",
                        TasklaneConfig.DONE.value,
                        Commands.prefix(batch) + "*",
                    ) { it.getInt(0) to it.getInt(1) }!!
                    val expected = when (batch) {
                        k -> Commands.PER_BATCH to 0
                        k - 1 -> Commands.PER_BATCH to Commands.PER_BATCH
                        else -> 0 to 0
                    }
                    assertEquals("la tanda $batch con la transacción $k confirmada", expected, total to done)
                }
                auditClean(db)

                // Y la base se sigue usando: la transacción siguiente entra y cuadra.
                Commands.apply(store, k + 1)
                auditClean(db)
            } finally {
                db.close()
            }
            println("[comandos] muerto con $lastCommit confirmadas; la base, sana")
        }
    }

    // ------------------------------------------------------------------ migración

    /**
     * **A mitad de migrar un `tasks.xml`.** Lo que queda en la base es un prefijo exacto del
     * fichero —la marca de por dónde iba se escribe en la misma transacción que la tanda—, y
     * volver a lanzar la migración termina con **todas** las tareas, sin repetir ninguna.
     */
    @Test
    fun `morir a mitad de la migracion y continuar deja todas las tareas`() = repeat(rounds) { n ->
        val random = round("migración", n)
        withDir { dir ->
            val xml = dir.resolve("tasks.xml")
            val total = TasksXmlReader.CHUNK * 4 + 500
            SyntheticCorpus.writeTasksXml(xml, total, Commands.REPO)
            val db = dir.resolve("db")
            var finished = false

            ChildJvm.start(CrashWorker.MAIN, "migration", db.toString(), xml.toString()).use { child ->
                val after = TasksXmlReader.CHUNK * random.nextInt(1, 3)
                child.await { it == "CHUNK $after" }
                Thread.sleep(random.nextLong(0, 250))
                child.kill()
                // Con la máquina cargada el hijo puede terminar antes de que llegue la muerte.
                // No es un fallo —la base también tiene que quedar sana así—, pero se dice.
                finished = child.output.any { it.startsWith("DONE") }
            }

            val reopened = TaskDb.open(db)!!
            try {
                val store = TaskStore(reopened)
                val marked = store.importedCount(Commands.REPO)
                val rows = reopened.writer.count("SELECT count(*) FROM task")
                assertEquals("la marca de por dónde iba y lo escrito son lo mismo", marked, rows)
                assertEquals("lo escrito son tandas enteras", 0, if (rows == total) 0 else rows % TasksXmlReader.CHUNK)
                if (!finished) assertTrue(rows < total)
                auditClean(reopened)

                val outcome = TaskImport.run(store, xml, Commands.REPO, Commands.CONFIG)
                assertTrue("${outcome.result}", outcome.result is TasksXmlReader.Result.Done)
                assertEquals(total, reopened.writer.count("SELECT count(*) FROM task"))
                assertEquals(total, store.importedCount(Commands.REPO))
                val ids = HashSet<String>()
                TasksXmlReader.read(xml, Commands.REPO) { chunk -> chunk.forEach { ids += it.id.value } }
                assertEquals(0, reopened.writer.count("SELECT count(*) FROM task") - ids.size)
                assertTrue("todas las del fichero, y ninguna de más", ids.all { store.task(TaskId(it)) != null })
                auditClean(reopened)
            } finally {
                reopened.close()
            }
            println("[migración] muerta ${if (finished) "después de terminar" else "a mitad"}; continuar la completó")
        }
    }

    // ----------------------------------------------------------------- recolector

    /**
     * **A mitad de recolectar.** Lo que se juega es lo único irreversible del plugin: una
     * imagen que alguna tarea nombra **no se borra nunca**, se muera el proceso cuando se
     * muera. Y lo que quedó a medias —filas de ficheros ya borrados— lo termina la pasada
     * siguiente sin dejar nada suelto.
     */
    @Test
    fun `morir a mitad de recolectar no borra nada que se use`() = repeat(rounds) { n ->
        val random = round("recolector", n)
        withDir { dir ->
            val db = dir.resolve("db")
            val attachments = dir.resolve("data")
            val files = AttachmentStore(StorageLayout(attachments))
            val now = Instant.parse("2026-09-15T12:00:00Z")
            val old = now.minus(Duration.ofDays(7))
            val ids = ArrayList<AttachmentId>(BLOBS)
            var finished = false
            TaskDb.open(db)!!.let { setup ->
                val store = TaskStore(setup)
                for (i in 0 until BLOBS) {
                    val id = files.put(Commands.REPO, "blob-$i-".repeat(40).toByteArray())
                    if (i % 3 == 0) files.putThumbnail(Commands.REPO, id, "thumb-$i".toByteArray())
                    ids += id
                }
                store.write { store.adoptBlobs(Commands.REPO, ids.map { BlobRecord(it, 400, 1, 1, old) }, old.toEpochMilli()) }
                store.importBatch(
                    (0 until REFERENCED).map { i -> task("used-$i", "Captura ${ImageRefParser.reference(ids[i])}") },
                    Commands.CONFIG,
                )
                setup.close()
            }

            ChildJvm.start(CrashWorker.MAIN, "gc", db.toString(), attachments.toString(), now.toString()).use { child ->
                // Dentro de la segunda tanda: con la primera ya confirmada y la segunda con
                // ficheros borrados y filas todavía sin quitar.
                val at = 50 * random.nextInt(21, 30)
                child.await { it == "DELETED $at" }
                Thread.sleep(random.nextLong(0, 20))
                child.kill()
                finished = child.output.any { it.startsWith("DONE") }
            }

            val reopened = TaskDb.open(db)!!
            try {
                val store = TaskStore(reopened)
                for (i in 0 until REFERENCED) {
                    assertTrue("la imagen $i la usa una tarea y no puede faltar", files.exists(Commands.REPO, ids[i]))
                }
                auditClean(reopened)

                val swept = BlobSweeper(files, Ledger(store)).collect(Commands.REPO, now)
                assertTrue(swept.complete)
                assertEquals("sólo quedan filas de lo que se usa", REFERENCED, store.blobStatsOf(Commands.REPO).count)
                var onDisk = 0
                files.scan(Commands.REPO) { onDisk += it.blobs.size }
                assertEquals("y en disco, lo mismo", REFERENCED, onDisk)
                for (i in REFERENCED until BLOBS step 3) {
                    assertEquals("la miniatura se fue con su original", null, files.locate(Commands.REPO, ids[i], thumbnail = true))
                }
                for (i in 0 until REFERENCED) assertTrue(files.exists(Commands.REPO, ids[i]))
            } finally {
                reopened.close()
            }
            println("[recolector] muerto ${if (finished) "después de terminar" else "a mitad"}; nada de lo usado se borró")
        }
    }

    // ---------------------------------------------------------------------- copia

    /**
     * **A mitad de la copia diaria.** La copia de seguridad es de lo que tira la
     * recuperación del §6.2, así que morir mientras se renueva no puede dejar ni una copia a
     * medias con su nombre ni perder la anterior: o está la vieja entera o la nueva entera.
     */
    @Test
    fun `morir a mitad de la copia deja la copia anterior o la nueva, enteras`() = repeat(rounds) { n ->
        val random = round("copia", n)
        withDir { dir ->
            val target = dir.resolve("tasklane.db.backup")
            val first = BACKUP_TASKS
            var copyMillis = 0L
            TaskDb.open(dir.resolve("db"))!!.let { setup ->
                val store = TaskStore(setup)
                store.importBatch(SyntheticCorpus.tasks(first, Commands.REPO, config = Commands.CONFIG), Commands.CONFIG)
                val start = System.nanoTime()
                setup.backupTo(target)
                copyMillis = (System.nanoTime() - start) / 1_000_000
                store.importBatch(
                    SyntheticCorpus.tasks(BACKUP_EXTRA, Commands.REPO, seed = 7, config = Commands.CONFIG)
                        .map { it.copy(id = TaskId("extra-" + it.id.value)) },
                    Commands.CONFIG,
                )
                setup.close()
            }

            var finished = false
            ChildJvm.start(CrashWorker.MAIN, "backup", dir.resolve("db").toString(), target.toString()).use { child ->
                child.await { it == "START" }
                // Dentro de lo que tardó la primera copia, que es algo menor que ésta.
                Thread.sleep(random.nextLong(0, (copyMillis * 3 / 2).coerceAtLeast(1)))
                child.kill()
                finished = child.output.any { it.startsWith("DONE") }
            }

            val copy = Sql(target)
            try {
                assertEquals(listOf("ok"), copy.rows("PRAGMA integrity_check") { it.getString(0).orEmpty() })
                val count = copy.count("SELECT count(*) FROM task")
                assertTrue("la copia es la vieja o la nueva, entera: $count", count == first || count == first + BACKUP_EXTRA)
                if (finished) assertEquals(first + BACKUP_EXTRA, count)
            } finally {
                copy.close()
            }

            val db = TaskDb.open(dir.resolve("db"))!!
            try {
                auditClean(db)
                db.backupTo(target)
                assertFalse("el temporal de la copia muerta se limpia", Files.exists(dir.resolve("tasklane.db.backup.tmp")))
            } finally {
                db.close()
            }
            val copy2 = Sql(target)
            try {
                assertEquals(first + BACKUP_EXTRA, copy2.count("SELECT count(*) FROM task"))
            } finally {
                copy2.close()
            }
            println("[copia] muerta ${if (finished) "después" else "durante"} la copia")
        }
    }

    private fun task(id: String, body: String) = com.tasklane.data.sqlite.StoreFixture.task(id, body = body, repo = Commands.REPO)

    private companion object {
        const val BLOBS = 6_000
        const val REFERENCED = 1_000
        const val BACKUP_TASKS = 3_000
        const val BACKUP_EXTRA = 500
    }
}
