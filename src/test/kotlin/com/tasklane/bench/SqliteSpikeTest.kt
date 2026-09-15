package com.tasklane.bench

import com.tasklane.data.sqlite.Sql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * El spike de la Fase 0 (`docs/plan-escala.md` §0.5, riesgo nº1): ¿se puede usar SQLite
 * desde un plugin de JetBrains, **con lo que el almacén necesita**?
 *
 * **Dos respuestas en la vida del plugin.** La Fase 0 encontró que la plataforma ya traía
 * SQLite (`org.jetbrains.sqlite`) y lo usó para no empaquetar nada. La Fase 6 lo tuvo que
 * deshacer: el Marketplace rechaza el plugin porque ese paquete es API interna
 * (`@ApiStatus.Internal` en su `package-info`), así que desde entonces se empaqueta
 * `org.xerial:sqlite-jdbc` —que es lo que el §2.3 del plan proponía desde el principio—.
 *
 * Esta clase fija, contra el driver que de verdad se empaqueta, lo que el almacén de la
 * Fase 3 necesita y que **no** es evidente desde fuera: que la nativa lleva FTS5
 * compilado, que admite WAL, que `unicode61 remove_diacritics 2` y `bm25()` se comportan
 * y que la paginación por *keyset* va por índice. Si una versión nueva del driver se
 * compilara sin `SQLITE_ENABLE_FTS5`, este test es lo que lo diría.
 *
 * Va sobre [Sql] y no sobre JDBC a pelo: lo que se prueba es lo que usa producción.
 */
class SqliteSpikeTest {

    private fun <T> withDb(block: (Sql) -> T): T {
        val dir = Files.createTempDirectory("tasklane-spike")
        val db = Sql(dir.resolve("tasklane.db"))
        return try {
            block(db)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun Sql.text(sql: String): String? = first(sql) { it.getString(0).orEmpty() }

    /**
     * Que la nativa cargue y responda. Es el test que falla primero —y con un
     * `UnsatisfiedLinkError` bien visible— si el driver deja de encontrar su nativa en
     * algún sistema.
     */
    @Test
    fun `la nativa carga y ejecuta SQL`() = withDb { db ->
        db.execute("CREATE TABLE t (a TEXT)")
        db.execute("INSERT INTO t VALUES ('hola')")
        assertEquals("hola", db.text("SELECT a FROM t"))
    }

    /**
     * WAL es el §2.4: lectores y escritor sin bloquearse. `journal_mode` devuelve el modo
     * que quedó puesto, así que el `PRAGMA` se comprueba solo.
     */
    @Test
    fun `admite WAL y las pragmas del esquema`() = withDb { db ->
        assertEquals("wal", db.text("PRAGMA journal_mode = WAL")?.lowercase())
        db.execute("PRAGMA synchronous = NORMAL")
        db.execute("PRAGMA foreign_keys = ON")
        db.execute("PRAGMA user_version = 1")
        assertEquals(1, db.count("PRAGMA user_version"))
        assertEquals(1, db.count("PRAGMA foreign_keys"))
    }

    /**
     * **El test que decide el plan.** FTS5 es una opción de compilación, no algo que SQLite
     * traiga siempre. Se prueba además `remove_diacritics 2`, que es lo que sustituye a
     * `TextNormalizer`: buscar `revision` tiene que encontrar `revisión`.
     */
    @Test
    fun `FTS5 esta compilado y quita diacriticos`() = withDb { db ->
        assertEquals(1, db.count("SELECT sqlite_compileoption_used('ENABLE_FTS5')"))
        db.execute(
            """
            CREATE VIRTUAL TABLE task_fts USING fts5(
              title, body,
              tokenize = "unicode61 remove_diacritics 2"
            )
            """.trimIndent(),
        )
        db.execute("INSERT INTO task_fts VALUES ('Revisión del login', 'Mirar el token caducado')")
        db.execute("INSERT INTO task_fts VALUES ('Pulir la tarjeta', 'Sin nada que ver')")

        assertEquals(1, db.count("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'revision'"))
        assertEquals(1, db.count("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'revisión'"))
        assertEquals(1, db.count("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'TOKEN'"))
        assertEquals(0, db.count("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'inexistente'"))
    }

    /**
     * `bm25()` con peso en la columna del título es lo que sustituye al
     * `TITLE_WEIGHT = 1_000_000` de `LinearScanIndex.score()` (§2.4). bm25 devuelve valores
     * negativos y más negativo es mejor, así que el orden ascendente ya pone primero lo que
     * mejor casa.
     */
    @Test
    fun `bm25 pondera el titulo por encima del cuerpo`() = withDb { db ->
        db.execute("CREATE VIRTUAL TABLE task_fts USING fts5(title, body)")
        db.execute("INSERT INTO task_fts VALUES ('Nota suelta', 'algo sobre el login y su token')")
        db.execute("INSERT INTO task_fts VALUES ('Arreglar el login', 'nada que anadir')")

        val ranked = db.rows("SELECT title FROM task_fts WHERE task_fts MATCH 'login' ORDER BY bm25(task_fts, 10.0, 1.0)") {
            it.getString(0).orEmpty()
        }
        assertEquals(listOf("Arreglar el login", "Nota suelta"), ranked)
    }

    /**
     * La paginación por *keyset* del §2.4 —«jamás `OFFSET`»— sobre el índice compuesto que
     * sostiene la lista: la tupla del cursor parte la lista sin repetir ni saltarse nada,
     * con fechas repetidas a propósito.
     */
    @Test
    fun `la paginacion por keyset no repite ni pierde filas`() = withDb { db ->
        db.execute(
            """
            CREATE TABLE task (
              id TEXT PRIMARY KEY, bookmarked INTEGER NOT NULL,
              priority_rank INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execute("CREATE INDEX task_board ON task(bookmarked DESC, priority_rank DESC, updated_at DESC, id DESC)")

        val total = 250
        db.transaction {
            db.batch(
                "INSERT INTO task VALUES (?, ?, ?, ?)",
                4,
                (0 until total).asSequence().map { i -> arrayOf<Any>("t-%04d".format(i), if (i % 7 == 0) 1 else 0, i % 3, (i / 5).toLong()) },
            )
        }

        data class Cursor(val id: String, val bookmarked: Int, val rank: Int, val updatedAt: Long)

        val order = "ORDER BY bookmarked DESC, priority_rank DESC, updated_at DESC, id DESC"
        val page = 40
        val seen = ArrayList<String>(total)
        var cursor: Cursor? = null

        while (true) {
            val here = cursor
            val rows = if (here == null) {
                db.rows("SELECT id, bookmarked, priority_rank, updated_at FROM task $order LIMIT $page") {
                    Cursor(it.getString(0)!!, it.getInt(1), it.getInt(2), it.getLong(3))
                }
            } else {
                db.rows(
                    "SELECT id, bookmarked, priority_rank, updated_at FROM task " +
                        "WHERE (bookmarked, priority_rank, updated_at, id) < (?, ?, ?, ?) $order LIMIT $page",
                    here.bookmarked,
                    here.rank,
                    here.updatedAt,
                    here.id,
                ) { Cursor(it.getString(0)!!, it.getInt(1), it.getInt(2), it.getLong(3)) }
            }
            rows.forEach { seen += it.id }
            cursor = rows.lastOrNull() ?: cursor
            if (rows.size < page) break
        }

        assertEquals("ninguna fila se repite", seen.size, seen.toSet().size)
        assertEquals("no falta ninguna", total, seen.size)
    }

    /**
     * La forma de la consulta del §2.4 se resuelve **por índice** y no con un `SCAN` ni un
     * `USE TEMP B-TREE FOR ORDER BY`, que es lo que delataría un orden de un millón de filas
     * escondido detrás de un `LIMIT 100`.
     */
    @Test
    fun `la consulta de la lista se resuelve por indice`() = withDb { db ->
        db.execute(
            """
            CREATE TABLE task (
              id TEXT PRIMARY KEY, repo TEXT NOT NULL, state TEXT NOT NULL,
              bookmarked INTEGER NOT NULL, priority_rank INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execute("CREATE INDEX task_board ON task(repo, state, bookmarked DESC, priority_rank DESC, updated_at DESC)")

        val plan = db.rows(
            "EXPLAIN QUERY PLAN SELECT id FROM task WHERE repo = 'r' AND state = 's' " +
                "ORDER BY bookmarked DESC, priority_rank DESC, updated_at DESC LIMIT 100",
        ) { it.getString(3).orEmpty() }.joinToString(" | ")

        assertTrue("debe usar task_board, y el plan fue: $plan", plan.contains("task_board"))
        assertTrue("no debe ordenar en memoria, y el plan fue: $plan", !plan.contains("TEMP B-TREE"))
    }

    /**
     * Lo que el módulo de la plataforma no tenía y el driver sí: cortar una sentencia en
     * marcha desde otro hilo (`sqlite3_interrupt`). Es lo que deja cancelar a mitad una copia
     * o una comprobación de integridad.
     */
    @Test
    fun `una sentencia larga se puede cortar desde otro hilo`() = withDb { db ->
        val started = System.nanoTime()
        val cutter = Thread {
            Thread.sleep(100)
            db.interrupt()
        }
        cutter.start()
        val outcome = runCatching {
            db.count("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 2000000000) SELECT count(*) FROM n")
        }
        cutter.join()
        assertTrue("tenía que cortarse: $outcome", outcome.isFailure)
        assertTrue("y en seguida", (System.nanoTime() - started) / 1_000_000 < 10_000)
    }
}
