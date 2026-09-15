package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.store.LoadAlert
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.TaskId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * **§6.2 — corrupción deliberada.** Se rompe `tasklane.db` de las formas en que se rompe una
 * base de verdad —la cabecera, una página de índice, una hoja de la tabla, el índice de
 * texto, el diario— y se comprueba que la ruta de recuperación da **el comportamiento que
 * daba un `tasks.xml` ilegible**: el fichero dañado en cuarentena y sin tocar, lo legible
 * dentro, lo que falta desde la copia, y un aviso que dice cuál de los tres casos fue.
 *
 * El escenario es siempre el mismo: una base con tareas, **la copia diaria**, y después de
 * la copia más trabajo —tareas nuevas, editadas y borradas—. Lo que se juega es qué de ese
 * trabajo sobrevive a cada daño.
 */
class StoreRecoveryTest {

    private val base = Instant.parse("2026-09-01T09:00:00Z")
    private val later = Instant.parse("2026-09-14T18:00:00Z")

    private class Scenario(val dir: Path, val backup: Path) {
        val file: Path get() = dir.resolve(TaskDb.FILE_NAME)
    }

    /**
     * 600 tareas en la copia; después, 50 nuevas, 40 editadas y 30 borradas. Los cuerpos
     * llevan una marca en mayúsculas que sólo está en la tabla —el índice de texto guarda
     * los términos en minúsculas—, para poder encontrar en el fichero la hoja de una tarea.
     */
    private fun <T> withScenario(block: (Scenario) -> T): T {
        val root = Files.createTempDirectory("tasklane-recovery")
        return try {
            val dir = root.resolve("tasklane")
            val backup = dir.resolve("tasklane.db.backup")
            val db = TaskDb.open(dir)!!
            val store = TaskStore(db)
            store.importBatch((0 until 600).map(::original), CONFIG)
            db.backupTo(backup)
            store.apply(CONFIG, listOf(Mutation.Upsert((0 until 50).map { i -> task("new-$i", "NUEVA-$i ${filler(i)}", later) })))
            store.apply(CONFIG, listOf(Mutation.Upsert((100 until 140).map { i -> original(i).copy(body = "EDITADA-$i ${filler(i)}", updatedAt = later) })))
            store.apply(CONFIG, listOf(Mutation.Delete((200 until 230).map { TaskId("t-$it") })))
            // Sin páginas libres con copias viejas de las filas: así la marca de un cuerpo
            // está en un solo sitio del fichero, que es la hoja viva de su tarea.
            db.writer.execute("VACUUM")
            db.close()
            block(Scenario(dir, backup))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun filler(i: Int) = "texto de relleno para que la fila pese lo que pesa una tarea de verdad ".repeat(8 + i % 5)

    private fun task(id: String, body: String, at: Instant) = StoreFixture.task(
        id,
        body = body,
        createdAt = base,
        updatedAt = at,
        tags = listOf("Tag${id.hashCode() % 7}", "común"),
        anchors = listOf(CodeAnchor.of("src/Main.kt", 10, 0, "fun main")),
    )

    private fun original(i: Int) = task("t-$i", "ORIGINAL-$i ${filler(i)}", base)

    private fun recover(s: Scenario): StoreRecovery.Outcome {
        val opened = TaskDb.openChecked(s.dir)
        if (opened is TaskDb.Opening.Ready) {
            // Abrió: el daño lo encuentra la comprobación, que es quien lo apunta.
            val problems = opened.db.checkIntegrity()
            opened.db.close()
            assertTrue("la comprobación tenía que ver el daño", problems.isNotEmpty())
            StoreRecovery.markDamaged(s.dir, problems.first())
        }
        return StoreRecovery.recover(s.dir, s.backup) ?: error("no había nada que recuperar")
    }

    private fun <T> reopened(s: Scenario, block: (TaskDb, TaskStore) -> T): T {
        val opened = TaskDb.openChecked(s.dir)
        assertTrue("la base recuperada tiene que abrir: $opened", opened is TaskDb.Opening.Ready)
        val db = (opened as TaskDb.Opening.Ready).db
        return try {
            assertEquals("la base recuperada cuadra", emptyList<String>(), StoreAudit.check(db.writer))
            block(db, TaskStore(db))
        } finally {
            db.close()
        }
    }

    private fun bodyOf(store: TaskStore, id: String): String? = store.task(TaskId(id))?.body

    /** El número de página (desde 1) de la hoja que contiene [needle]. */
    private fun pageOf(file: Path, needle: String): Int {
        val bytes = Files.readAllBytes(file)
        val pageSize = Sql(file, readOnly = true).let { sql -> try { sql.count("PRAGMA page_size") } finally { sql.close() } }
        val at = String(bytes, Charsets.ISO_8859_1).indexOf(needle)
        assertTrue("no está $needle en el fichero", at >= 0)
        return at / pageSize + 1
    }

    private fun trash(file: Path, page: Int, from: Int = 0, length: Int? = null) {
        val pageSize = Sql(file, readOnly = true).let { sql -> try { sql.count("PRAGMA page_size") } finally { sql.close() } }
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.seek((page - 1).toLong() * pageSize + from)
            raf.write(ByteArray(length ?: (pageSize - from)) { 0x5A })
        }
    }

    // ----------------------------------------------------------------- cabecera

    /**
     * **La cabecera, rota: el fichero ni abre.** Es el `ReadResult.Corrupt` sin nada
     * legible: cuarentena, todo desde la copia, y se dice que se recuperó —con lo que se
     * perdió desde la copia—.
     */
    @Test
    fun `una cabecera rota abre en recuperacion y tira de la copia`() = withScenario { s ->
        val damaged = Files.readAllBytes(s.file).also { it.fill(0x5A, 0, 100) }
        Files.write(s.file, damaged)

        val opened = TaskDb.openChecked(s.dir)
        assertTrue("no abre y queda apuntada: $opened", opened is TaskDb.Opening.Damaged)
        assertTrue(StoreRecovery.pending(s.dir))

        val outcome = StoreRecovery.recover(s.dir, s.backup)!!
        assertFalse(outcome.clean)
        assertEquals(0, outcome.salvaged)
        assertEquals(600, outcome.restored)
        assertTrue(outcome.alert() is LoadAlert.Recovered)
        assertNotNull(outcome.backupAt)
        assertFalse(StoreRecovery.pending(s.dir))
        assertArrayEquals("la cuarentena es el fichero tal cual", damaged, Files.readAllBytes(outcome.quarantined!!))

        reopened(s) { _, store ->
            assertEquals(600, store.countOf(REPO))
            assertTrue("lo de la copia está", bodyOf(store, "t-205")!!.startsWith("ORIGINAL-205"))
            assertNull("lo de después de la copia no había de dónde sacarlo", store.task(TaskId("new-1")))
        }
    }

    @Test
    fun `sin copia y sin nada legible se dice que se perdio`() = withScenario { s ->
        Files.delete(s.backup)
        Files.write(s.file, Files.readAllBytes(s.file).also { it.fill(0x5A, 0, 100) })
        assertTrue(TaskDb.openChecked(s.dir) is TaskDb.Opening.Damaged)

        val outcome = StoreRecovery.recover(s.dir, s.backup)!!
        assertTrue(outcome.alert() is LoadAlert.Lost)
        assertNotNull((outcome.alert() as LoadAlert.Lost).quarantinedAt)
        reopened(s) { _, store -> assertEquals(0, store.countOf(REPO)) }
    }

    // ------------------------------------------------------------------- índices

    /**
     * **Una página de índice, rota.** Es el caso común —la mayor parte de lo que se escribe
     * son páginas de índice— y aquí es donde «cuarentena + copia» a secas habría tirado un día
     * de trabajo: la tabla se lee entera, los índices se rehacen, y **no se pierde nada**.
     */
    @Test
    fun `un indice roto se repara sin perder nada de despues de la copia`() = withScenario { s ->
        val root = Sql(s.file, readOnly = true).let { sql ->
            try { sql.count("SELECT rootpage FROM sqlite_master WHERE name = 'task_board'") } finally { sql.close() }
        }
        trash(s.file, root, from = 16, length = 2_000)

        val outcome = recover(s)
        assertTrue("$outcome", outcome.clean)
        assertEquals(620, outcome.salvaged)
        assertEquals(0, outcome.restored)
        assertTrue(outcome.alert() is LoadAlert.Repaired)

        reopened(s) { db, store ->
            assertEquals(620, store.countOf(REPO))
            assertTrue(bodyOf(store, "new-3")!!.startsWith("NUEVA-3"))
            assertTrue(bodyOf(store, "t-120")!!.startsWith("EDITADA-120"))
            assertNull("lo borrado después de la copia no resucita", store.task(TaskId("t-210")))
            assertEquals(listOf("Tag${"t-7".hashCode() % 7}", "común"), store.task(TaskId("t-7"))!!.tags)
            assertEquals(1, store.task(TaskId("t-7"))!!.anchors.size)
            assertTrue("y se busca", db.reader.count("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'nueva'") >= 50)
        }
    }

    /** El índice de texto, roto: no se copia nunca, se rehace. Tampoco se pierde nada. */
    @Test
    fun `el indice de texto roto se rehace`() = withScenario { s ->
        val root = Sql(s.file, readOnly = true).let { sql ->
            try { sql.count("SELECT rootpage FROM sqlite_master WHERE name = 'task_fts_data'") } finally { sql.close() }
        }
        trash(s.file, root, from = 8, length = 1_500)

        val outcome = recover(s)
        assertTrue("$outcome", outcome.clean)
        reopened(s) { db, store ->
            assertEquals(620, store.countOf(REPO))
            assertEquals(40, db.reader.count("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'editada'"))
        }
    }

    // --------------------------------------------------------------------- tablas

    /**
     * **Una hoja de la tabla de tareas, rota.** Ahí sí hay algo que no se puede leer. Lo que
     * cae en la hoja sale de la copia —en su versión de la copia—; todo lo demás, incluidas
     * las ediciones y los borrados de después, se queda como estaba.
     */
    @Test
    fun `una hoja de la tabla rota completa sus huecos desde la copia y nada mas`() = withScenario { s ->
        // Una tarea editada después de la copia, para ver qué versión vuelve.
        trash(s.file, pageOf(s.file, "EDITADA-125 "), from = 8)

        val outcome = recover(s)
        assertFalse(outcome.clean)
        assertTrue("$outcome", outcome.unreadable > 0)
        assertTrue("$outcome", outcome.restored > 0)
        assertTrue(outcome.alert() is LoadAlert.Recovered)

        reopened(s) { _, store ->
            assertTrue("la de la hoja rota vuelve en su versión de la copia", bodyOf(store, "t-125")!!.startsWith("ORIGINAL-125"))
            assertTrue("las nuevas fuera de la hoja siguen", bodyOf(store, "new-40")!!.startsWith("NUEVA-40"))
            assertTrue("las editadas fuera de la hoja siguen editadas", bodyOf(store, "t-139")!!.startsWith("EDITADA-139"))
            assertNull("lo borrado cuyo hueco se lee no resucita", store.task(TaskId("t-229")))
            val total = store.countOf(REPO)
            assertTrue("ni se inventan ni desaparecen más de las de la hoja: $total", total in 615..625)
        }
    }

    // ---------------------------------------------------------------------- diario

    /**
     * **Un diario con basura.** SQLite descarta los marcos cuya suma no cuadra, así que un
     * `-wal` pisado no es una base dañada: es una base con menos transacciones. Lo que se
     * comprueba es que lo que queda **cuadra consigo mismo** —los contadores, el índice de
     * texto— y que no se dispara ninguna recuperación.
     */
    @Test
    fun `un diario con basura no dana la base`() = withScenario { s ->
        val live = TaskDb.open(s.dir)!!
        val crashed = s.dir.resolveSibling("caida").also(Files::createDirectories)
        try {
            val store = TaskStore(live)
            store.apply(CONFIG, listOf(Mutation.Upsert((0 until 30).map { i -> task("wal-$i", "WAL-$i ${filler(i)}", later) })))
            Files.copy(live.file, crashed.resolve(TaskDb.FILE_NAME))
            Files.copy(TaskDb.walOf(live.file), crashed.resolve("${TaskDb.FILE_NAME}-wal"))
        } finally {
            live.close()
        }
        val wal = crashed.resolve("${TaskDb.FILE_NAME}-wal")
        RandomAccessFile(wal.toFile(), "rw").use { raf ->
            raf.seek(raf.length() / 2)
            raf.write(ByteArray((raf.length() / 2).toInt()) { 0x5A })
        }

        val opened = TaskDb.openChecked(crashed)
        assertTrue("$opened", opened is TaskDb.Opening.Ready)
        val db = (opened as TaskDb.Opening.Ready).db
        try {
            assertEquals(emptyList<String>(), StoreAudit.check(db.writer))
        } finally {
            db.close()
        }
        assertFalse(StoreRecovery.pending(crashed))
    }

    // ------------------------------------------------------------------ reanudar

    /** Si el IDE se cierra a mitad de recuperar, la apertura siguiente termina el trabajo. */
    @Test
    fun `una recuperacion interrumpida se termina en la apertura siguiente`() = withScenario { s ->
        trash(s.file, pageOf(s.file, "EDITADA-130 "), from = 8)
        TaskDb.openChecked(s.dir).let { if (it is TaskDb.Opening.Ready) it.db.close() }
        StoreRecovery.markDamaged(s.dir, "prueba")

        val boom = runCatching {
            StoreRecovery.recover(s.dir, s.backup) { phase -> if (phase == "rebuild") error("se cerró el IDE") }
        }
        assertTrue(boom.isFailure)
        assertTrue("sigue pendiente", StoreRecovery.pending(s.dir))
        assertFalse("la base ya está en cuarentena", Files.exists(s.file))

        val outcome = StoreRecovery.recover(s.dir, s.backup)!!
        assertTrue(outcome.tasks > 600)
        assertEquals("una sola cuarentena", 1, Files.list(s.dir).use { files -> files.filter { it.fileName.toString().matches(Regex("${Regex.escape(StoreRecovery.QUARANTINE_PREFIX)}\\d+")) }.count() })
        reopened(s) { _, store -> assertTrue(store.countOf(REPO) > 600) }
    }

    /** Terminada pero sin borrar la marca: se reconoce y no se repite. */
    @Test
    fun `una recuperacion terminada no se repite aunque quede la marca`() = withScenario { s ->
        trash(s.file, pageOf(s.file, "EDITADA-130 "), from = 8)
        val outcome = recover(s)

        Files.writeString(
            StoreRecovery.markerOf(s.dir),
            "reason=prueba\nquarantine=${outcome.quarantined!!.fileName}\nstamp=${outcome.stamp}\n",
        )
        assertNull(StoreRecovery.recover(s.dir, s.backup))
        assertFalse(StoreRecovery.pending(s.dir))
        reopened(s) { _, store -> assertEquals(outcome.tasks, store.countOf(REPO)) }
    }

    @Test
    fun `reconoce una base danada por el error`() {
        assertTrue(StoreRecovery.isCorruption(RuntimeException("database disk image is malformed")))
        assertTrue(StoreRecovery.isCorruption(IllegalStateException("x", RuntimeException("file is not a database"))))
        assertFalse(StoreRecovery.isCorruption(RuntimeException("database or disk is full")))
    }
}
