package com.tasklane.data.sqlite

import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * El fichero abierto: las pragmas que la fase necesita, la promesa de la versión futura
 * y que abrir dos veces no rehaga nada.
 */
class TaskDbTest {

    private fun <T> withDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("tasklane-db")
        return try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `abre con WAL, claves ajenas y la version del esquema`() = withDir { dir ->
        val db = TaskDb.open(dir)!!
        try {
            assertEquals("wal", db.writer.first("PRAGMA journal_mode") { it.getString(0).orEmpty() }?.lowercase())
            assertEquals(1, db.writer.count("PRAGMA foreign_keys"))
            assertEquals(TaskSchema.VERSION, db.writer.count("PRAGMA user_version"))
            assertFalse(db.readOnly)
        } finally {
            db.close()
        }
    }

    /** Abrir un proyecto ya abierto antes no puede rehacer el esquema ni tocar los datos. */
    @Test
    fun `abrir dos veces es abrir`() = withDir { dir ->
        TaskDb.open(dir)!!.let { first ->
            TaskStore(first).importBatch(listOf(StoreFixture.task("a")), StoreFixture.CONFIG)
            first.close()
        }

        val second = TaskDb.open(dir)!!
        try {
            assertEquals(1, second.writer.count("SELECT count(*) FROM task"))
            assertEquals(TaskSchema.VERSION, second.writer.count("PRAGMA user_version"))
        } finally {
            second.close()
        }
    }

    /**
     * Una base de antes de los rangos (2.15.0) gana la columna `anchor.span` al abrirse, sin
     * subir `user_version`: sus anclas son de una línea, y lo que inserta una versión
     * anterior sin nombrar la columna sigue entrando.
     */
    @Test
    fun `una base de antes de los rangos gana la columna al abrir`() = withDir { dir ->
        TaskDb.open(dir)!!.let { first ->
            TaskStore(first).importBatch(
                listOf(StoreFixture.task("a", anchors = listOf(com.tasklane.domain.model.CodeAnchor.of("src/A.kt", 4)))),
                StoreFixture.CONFIG,
            )
            first.writer.execute("ALTER TABLE anchor DROP COLUMN span")
            first.close()
        }

        val second = TaskDb.open(dir)!!
        try {
            assertTrue("span" in second.writer.rows("PRAGMA table_info(anchor)") { it.getString(1) })
            assertEquals(TaskSchema.VERSION, second.writer.count("PRAGMA user_version"))
            assertEquals(0, second.writer.count("SELECT span FROM anchor WHERE task_id = 'a'"))
            // Lo que haría una versión anterior al guardar una tarea.
            second.writer.execute("INSERT INTO anchor (task_id, pos, path, line, col, text) VALUES ('a', 1, 'src/B.kt', 0, 0, '')")
            assertEquals(2, TaskStore(second).task(com.tasklane.domain.model.TaskId("a"))!!.anchors.size)
        } finally {
            second.close()
        }
    }

    /**
     * La promesa que `TasksCodec` hacía con el atributo `version`, trasladada a
     * `PRAGMA user_version`: una base escrita por una versión **posterior** del plugin
     * se abre en solo lectura y se avisa, en vez de degradarla escribiéndola con un
     * esquema viejo.
     */
    @Test
    fun `una base de una version futura se abre sin escritura`() = withDir { dir ->
        TaskDb.open(dir)!!.let { first ->
            first.writer.execute("PRAGMA user_version = ${TaskSchema.VERSION + 1}")
            first.close()
        }

        val future = TaskDb.open(dir)!!
        try {
            assertTrue(future.readOnly)
            assertEquals(TaskSchema.VERSION + 1, future.version)
            // Y nadie escribe: el almacén se cruza de brazos.
            val store = TaskStore(future)
            assertEquals(0, store.apply(StoreFixture.CONFIG, listOf(
                com.tasklane.domain.command.Mutation.Upsert(listOf(StoreFixture.task("a"))),
            )).rows)
        } finally {
            future.close()
        }
    }

    /** Un directorio imposible no puede tumbar el plugin: se abre sin almacén y ya. */
    @Test
    fun `un sitio donde no se puede escribir devuelve null`() = withDir { dir ->
        val file = dir.resolve("soy-un-fichero")
        Files.writeString(file, "x")
        assertEquals(null, TaskDb.open(file))
    }

    @Test
    fun `el lector ve lo que el escritor confirma`() = withDir { dir ->
        val db = TaskDb.open(dir)!!
        try {
            TaskStore(db).importBatch(listOf(StoreFixture.task("a")), StoreFixture.CONFIG)
            assertNotNull(db.reader.first("SELECT id FROM task WHERE id = 'a'") { it.getString(0).orEmpty() })
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------- Fase 5

    /**
     * **Un cierre limpio no deja diario; una caída, sí.** Es la señal con la que se decide
     * comprobar la integridad, y no hace falta un fichero marcador porque SQLite ya lo
     * deja: cerrar la última conexión vuelca el WAL y lo borra.
     *
     * La caída se simula copiando la base **y su diario** con las conexiones abiertas,
     * que es exactamente lo que queda en disco si el proceso muere en ese instante.
     */
    @Test
    fun `una caida deja el diario y la apertura siguiente lo sabe`() = withDir { dir ->
        val crashed = dir.resolve("caida")
        val clean = dir.resolve("limpia")

        TaskDb.open(clean)!!.let { db ->
            TaskStore(db).importBatch(listOf(StoreFixture.task("a")), StoreFixture.CONFIG)
            db.close()
        }
        TaskDb.open(clean)!!.let { db ->
            assertFalse("un cierre limpio no es un cierre sucio", db.dirty)
            db.close()
        }

        val live = TaskDb.open(dir.resolve("viva"))!!
        try {
            TaskStore(live).importBatch(listOf(StoreFixture.task("a"), StoreFixture.task("b")), StoreFixture.CONFIG)
            Files.createDirectories(crashed)
            Files.copy(live.file, crashed.resolve(TaskDb.FILE_NAME))
            Files.copy(TaskDb.walOf(live.file), crashed.resolve("${TaskDb.FILE_NAME}-wal"))
        } finally {
            live.close()
        }

        val reopened = TaskDb.open(crashed)!!
        try {
            assertTrue("el diario que quedó delata la caída", reopened.dirty)
            assertEquals("y lo que había en él no se pierde", 2, reopened.reader.count("SELECT count(*) FROM task"))
            assertTrue("WAL es lo que hace que una caída no corrompa", reopened.checkIntegrity().isEmpty())
        } finally {
            reopened.close()
        }
    }

    /**
     * La copia diaria: una base entera, compacta y que se abre, sin temporales al lado y
     * sustituyendo a la anterior.
     */
    @Test
    fun `la copia de seguridad es una base que se abre y dice lo mismo`() = withDir { dir ->
        val db = TaskDb.open(dir.resolve("base"))!!
        try {
            val store = TaskStore(db)
            store.importBatch((0 until 50).map { StoreFixture.task("t$it", tags = listOf("x")) }, StoreFixture.CONFIG)
            val target = dir.resolve("tasklane.db.backup")
            Files.writeString(target, "una copia vieja que tiene que desaparecer")

            val bytes = db.backupTo(target)
            store.importBatch(listOf(StoreFixture.task("despues")), StoreFixture.CONFIG)

            assertEquals(Files.size(target), bytes)
            assertFalse("sin temporal al lado", Files.exists(dir.resolve("tasklane.db.backup.tmp")))
            // De lectura y escritura a propósito: la copia sale en modo diario clásico y no
            // en WAL, y la plataforma no la abre en solo lectura. Es además como se abriría
            // para restaurarla.
            val copy = Sql(target)
            try {
                assertEquals("lo de después de copiar no está", 50, copy.count("SELECT count(*) FROM task"))
                assertEquals(50, copy.count("SELECT count(*) FROM tag"))
                assertEquals(50, copy.count("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'tarea'"))
                assertEquals(listOf("ok"), copy.rows("PRAGMA integrity_check") { it.getString(0).orEmpty() })
            } finally {
                copy.close()
            }
        } finally {
            db.close()
        }
    }

    /**
     * Una base con un índice pisado. La comprobación lo tiene que ver —y si la sentencia
     * revienta en vez de listar problemas, eso también es un problema que devolver—.
     */
    @Test
    fun `la comprobacion ve una base danada`() = withDir { dir ->
        val base = dir.resolve("base")
        val file = TaskDb.open(base)!!.let { db ->
            TaskStore(db).importBatch((0 until 2_000).map { StoreFixture.task("t$it") }, StoreFixture.CONFIG)
            val file = db.file
            db.close()
            file
        }
        // La raíz del índice que sostiene la lista, llena de basura.
        val (pageSize, root) = Sql(file, readOnly = true).let { sql ->
            try {
                sql.count("PRAGMA page_size") to sql.count("SELECT rootpage FROM sqlite_master WHERE name = 'task_board'")
            } finally {
                sql.close()
            }
        }
        java.io.RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.seek((root - 1).toLong() * pageSize + 16)
            raf.write(ByteArray(pageSize - 32) { 0x5A })
        }

        val db = TaskDb.open(base)!!
        try {
            assertTrue("tiene que encontrar algo", db.checkIntegrity().isNotEmpty())
        } finally {
            db.close()
        }
    }

    /**
     * **Las estadísticas no le cambian el plan a la lista.** `PRAGMA optimize` al cerrar
     * puede acabar lanzando `ANALYZE` —hoy no lo hace, con el SQLite de la plataforma y
     * sin estadísticas previas—, y la Fase 3 entera descansa en que cada consulta de la
     * lista vaya por su índice cubriente y sin ordenar en memoria. Esto es el centinela:
     * con un `ANALYZE` completo, los planes siguen siendo los mismos.
     */
    @Test
    fun `tras analizar, las consultas de la lista siguen yendo por indice`() = withDir { dir ->
        TaskDb.open(dir)!!.let { db ->
            val states = listOf(TasklaneConfig.TODO, TasklaneConfig.DOING, TasklaneConfig.DONE)
            TaskStore(db).importBatch(
                (0 until 3_000).map {
                    StoreFixture.task(
                        "t$it",
                        state = states[it % 3],
                        priority = StoreFixture.CONFIG.priorities[it % 3].id,
                        bookmarked = it % 17 == 0,
                        tags = listOf("a${it % 7}"),
                        createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(it * 3_600L),
                    )
                },
                StoreFixture.CONFIG,
            )
            db.writer.execute("ANALYZE")
            db.close()
        }

        val db = TaskDb.open(dir)!!
        try {
            assertTrue("hay estadísticas", db.reader.count("SELECT count(*) FROM sqlite_stat1") > 0)
            val state = TasklaneConfig.TODO.value
            val plans = mapOf(
                "task_board" to "SELECT t.seq FROM task t WHERE t.repo = 'root' AND t.state = '$state' AND " +
                    "t.bookmarked = 0 AND t.priority_rank = 1 AND (t.sort_date, t.id) < (5, 'x') " +
                    "ORDER BY t.sort_date DESC, t.id DESC LIMIT 50",
                "task_date" to "SELECT sort_date FROM task WHERE repo = 'root' AND state = '$state' AND undated = 0 " +
                    "AND sort_date < 9999999999999 ORDER BY sort_date DESC LIMIT 1",
                "tag_board" to "SELECT t.seq FROM tag g JOIN task t ON t.id = g.task_id WHERE g.repo = 'root' AND " +
                    "g.state = '$state' AND g.bookmarked = 0 AND g.priority_rank = 1 AND g.tag = 'a1' " +
                    "ORDER BY g.sort_date DESC, g.task_id DESC LIMIT 50",
                "task_ord" to "SELECT seq FROM task WHERE repo = 'root' AND (ord, seq) > (0, 0) ORDER BY ord, seq LIMIT 2000",
            )
            for ((index, query) in plans) {
                val plan = db.reader.rows("EXPLAIN QUERY PLAN $query") { it.getString(3).orEmpty() }.joinToString(" | ")
                assertTrue("$index: tiene que ir por su índice, y el plan fue: $plan", plan.contains(index))
                assertFalse("$index: no puede ordenar en memoria, y el plan fue: $plan", plan.contains("TEMP B-TREE"))
            }
        } finally {
            db.close()
        }
    }

    /**
     * Lo que pregunta la barra de estado (2.14.0) y el filtro de vencidas tienen que ir por
     * el índice parcial `task_due`, y el segundo sin ordenar en memoria. Con estadísticas,
     * que es cuando el planificador podría preferir cualquiera de los de la lista: todos
     * empiezan por `repo` y llevan `completed_at` y `due_date` en la cola.
     */
    @Test
    fun `lo vencido va por su indice parcial`() = withDir { dir ->
        TaskDb.open(dir)!!.let { db ->
            val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
            TaskStore(db).importBatch(
                (0 until 3_000).map {
                    StoreFixture.task(
                        "t$it",
                        dueDate = if (it % 10 == 0) start.plusSeconds(it * 3_600L) else null,
                        state = if (it % 3 == 0) TasklaneConfig.DONE else TasklaneConfig.TODO,
                        completedAt = if (it % 3 == 0) start else null,
                    )
                },
                StoreFixture.CONFIG,
            )
            db.writer.execute("ANALYZE")
            db.close()
        }

        val db = TaskDb.open(dir)!!
        try {
            val open = "repo = 'root' AND completed_at = ${TaskSchema.NO_DATE} AND due_date <> ${TaskSchema.NO_DATE}"
            val queries = listOf(
                "SELECT coalesce(sum(due_date < 5), 0), min(CASE WHEN due_date >= 5 THEN due_date END) FROM task WHERE $open",
                "SELECT seq FROM task WHERE $open AND due_date < 5 ORDER BY due_date LIMIT 50",
            )
            for (query in queries) {
                val plan = db.reader.rows("EXPLAIN QUERY PLAN $query") { it.getString(3).orEmpty() }.joinToString(" | ")
                assertTrue("tiene que ir por task_due, y el plan fue: $plan", plan.contains("task_due"))
                assertFalse("no puede ordenar en memoria, y el plan fue: $plan", plan.contains("TEMP B-TREE"))
            }
        } finally {
            db.close()
        }
    }
}
