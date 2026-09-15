package com.tasklane.bench

import org.jetbrains.sqlite.EmptyBinder
import org.jetbrains.sqlite.ObjectBinder
import org.jetbrains.sqlite.SqliteConnection
import org.jetbrains.sqlite.SqlitePreparedStatement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * El spike de la Fase 0 (`docs/plan-escala.md` §0.5, riesgo nº1): ¿se puede usar
 * SQLite desde un plugin de JetBrains?
 *
 * **La respuesta cambió la decisión del §2.3.** El plan daba por hecho que había que
 * empaquetar `org.xerial:sqlite-jdbc` —~12 MB de nativas, extraídas a un temporal en
 * el primer uso, dentro del classloader aislado del plugin— y ponía justo ahí el
 * riesgo alto. No hace falta: la plataforma **ya trae** SQLite.
 *
 *     lib/intellij.platform.sqlite.jar        org.jetbrains.sqlite, visibility="public"
 *     lib/native/<os>-<arch>/libsqliteij.*    las seis combinaciones que soporta el IDE
 *
 * Con `bundledModule("intellij.platform.sqlite")` entra en el classpath como ya
 * entraban `intellij.platform.vcs.dvcs` y compañía, y en runtime lo carga el
 * classloader de la plataforma, no el nuestro. Eso borra los tres costes que el plan
 * aceptaba a regañadientes:
 *
 * - **Cero bytes empaquetados.** El `.zip` del Marketplace no crece.
 * - **Cero riesgo de classloader.** Es el problema que ya nos hizo descartar
 *   `kotlinx-serialization` (ver `gradle.properties`), y aquí no se plantea: la
 *   nativa la carga la plataforma una vez para todo el IDE.
 * - **Cero problema de firma o de sandbox.** La nativa va dentro del IDE, firmada
 *   por JetBrains, y ya está cargada antes de que el plugin exista.
 *
 * Esta clase fija lo que el almacén de la Fase 3 necesita y que **no** es evidente
 * desde fuera: que la nativa de JetBrains lleva FTS5 compilado, que admite WAL, y
 * que `bm25()` y la paginación por keyset se comportan. Si JetBrains recompilara su
 * nativa sin `SQLITE_ENABLE_FTS5`, este test es lo que lo diría.
 *
 * **Dos trampas del módulo, anotadas aquí porque la Fase 3 las va a pisar.**
 *
 * 1. **Nada de `ObjectBinderFactory.createN()`.** Son `inline` con parámetros
 *    reificados, así que su cuerpo se copia en quien las llama; compiladas contra el
 *    IDE local (2026.2, JVM 25) no caben en nuestro bytecode 21 y el compilador lo
 *    rechaza con «Cannot inline bytecode built with JVM target 25». El constructor de
 *    [ObjectBinder] no es inline y hace lo mismo, así que se usa ése. Por lo mismo
 *    conviene no llamar a ninguna otra `inline` de este módulo.
 * 2. **Nada de `AutoCloseable.use`**, por la misma razón: es `inline` y en el stdlib
 *    del IDE viene a JVM 25. `Closeable.use` —la vieja— sí vale y es la que usa el
 *    código de producción, pero `SqliteStatement` sólo es `AutoCloseable`. Aquí se
 *    cierra con `try/finally`.
 */
class SqliteSpikeTest {

    private fun <T> withDb(block: (SqliteConnection) -> T): T {
        val dir = Files.createTempDirectory("tasklane-spike")
        val db = SqliteConnection(dir.resolve("tasklane.db"), false)
        return try {
            block(db)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun <T> SqlitePreparedStatement<*>.closing(block: (SqlitePreparedStatement<*>) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    /** Las columnas de texto de una consulta, fila a fila. */
    private fun SqliteConnection.textRows(sql: String, columns: Int = 1): List<List<String?>> =
        prepareStatement(sql, EmptyBinder).closing { statement ->
            buildList {
                val rows = statement.executeQuery()
                while (rows.next()) add((0 until columns).map(rows::getString))
            }
        }

    /**
     * Que la nativa cargue y responda. Es el test que falla primero —y con un
     * `UnsatisfiedLinkError` bien visible— si el módulo deja de estar en el
     * classpath o si la plataforma cambia de nombre.
     */
    @Test
    fun `la nativa de la plataforma carga y ejecuta SQL`() = withDb { db ->
        db.execute("CREATE TABLE t (a TEXT)")
        db.execute("INSERT INTO t VALUES ('hola')")
        assertEquals("hola", db.selectString("SELECT a FROM t"))
    }

    /**
     * WAL es el §2.4: lectores y escritor sin bloquearse, y atomicidad sin el `.bak`
     * que hoy se copia en cada volcado. `journal_mode` devuelve el modo que quedó
     * puesto, así que el `PRAGMA` se comprueba solo.
     */
    @Test
    fun `admite WAL y las pragmas del esquema`() = withDb { db ->
        assertEquals("wal", db.textRows("PRAGMA journal_mode = WAL").single().single()?.lowercase())
        db.execute("PRAGMA synchronous = NORMAL")
        db.execute("PRAGMA foreign_keys = ON")
        db.execute("PRAGMA user_version = 1")
        assertEquals(1, db.selectInt("PRAGMA user_version"))
        assertEquals(1, db.selectInt("PRAGMA foreign_keys"))
    }

    /**
     * **El test que decide el plan.** FTS5 es una opción de compilación
     * (`SQLITE_ENABLE_FTS5`), no algo que SQLite traiga siempre; sin él, §3.5 hay que
     * escribirlo a mano y la Fase 3 crece las tres semanas que el plan reservaba
     * para el plan B.
     *
     * Se prueba además `remove_diacritics 2`, que es lo que sustituye a
     * `TextNormalizer`: buscar `revision` tiene que encontrar `revisión`.
     */
    @Test
    fun `FTS5 esta compilado y quita diacriticos`() = withDb { db ->
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

        assertEquals(1, db.selectInt("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'revision'"))
        assertEquals(1, db.selectInt("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'revisión'"))
        assertEquals(1, db.selectInt("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'TOKEN'"))
        assertEquals(0, db.selectInt("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'inexistente'"))
    }

    /**
     * `bm25()` con peso en la columna del título es lo que sustituye al
     * `TITLE_WEIGHT = 1_000_000` de `LinearScanIndex.score()` (§2.4). Lo que hay que
     * fijar es el **sentido**: bm25 devuelve valores negativos y más negativo es
     * mejor, así que el orden ascendente ya pone primero lo que mejor casa —y un peso
     * de 10 en `title` basta para que un acierto en el título gane a uno en el
     * cuerpo, que es la jerarquía que hoy se consigue apilando escalones—.
     */
    @Test
    fun `bm25 pondera el titulo por encima del cuerpo`() = withDb { db ->
        db.execute("CREATE VIRTUAL TABLE task_fts USING fts5(title, body)")
        db.execute("INSERT INTO task_fts VALUES ('Nota suelta', 'algo sobre el login y su token')")
        db.execute("INSERT INTO task_fts VALUES ('Arreglar el login', 'nada que anadir')")

        val ranked = db
            .textRows("SELECT title FROM task_fts WHERE task_fts MATCH 'login' ORDER BY bm25(task_fts, 10.0, 1.0)")
            .map { it.single() }

        assertEquals(listOf("Arreglar el login", "Nota suelta"), ranked)
    }

    /**
     * La paginación por keyset del §2.4 —«jamás `OFFSET`»— sobre el índice compuesto
     * que sostiene la lista. Lo que se comprueba es que la tupla del cursor
     * `(bookmarked, priority_rank, updated_at, id)` parte la lista sin repetir ni
     * saltarse nada, que es justo lo que `OFFSET` sí garantiza y por lo que resulta
     * tentador.
     *
     * Las fechas van repetidas a propósito —cinco filas por valor—: sin el `id` como
     * último componente de la tupla, dos filas con la misma marca de tiempo quedarían
     * una a cada lado del corte y una de ellas no saldría en ninguna página.
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
        db.beginTransaction()
        val insert = db.prepareStatement(
            "INSERT INTO task VALUES (?, ?, ?, ?)",
            ObjectBinder(paramCount = 4),
        )
        try {
            for (i in 0 until total) {
                insert.binder.bind("t-%04d".format(i), if (i % 7 == 0) 1 else 0, i % 3, (i / 5).toLong())
                insert.binder.addBatch()
            }
            insert.executeBatch()
        } finally {
            insert.close()
        }
        db.commit()

        data class Cursor(val id: String, val bookmarked: Int, val rank: Int, val updatedAt: Long)

        val order = "ORDER BY bookmarked DESC, priority_rank DESC, updated_at DESC, id DESC"
        val page = 40
        val seen = ArrayList<String>(total)
        var cursor: Cursor? = null

        while (true) {
            val here = cursor
            val statement = if (here == null) {
                db.prepareStatement(
                    "SELECT id, bookmarked, priority_rank, updated_at FROM task $order LIMIT $page",
                    EmptyBinder,
                )
            } else {
                db.prepareStatement(
                    "SELECT id, bookmarked, priority_rank, updated_at FROM task " +
                        "WHERE (bookmarked, priority_rank, updated_at, id) < (?, ?, ?, ?) $order LIMIT $page",
                    ObjectBinder(paramCount = 4),
                ).also { it.binder.bind(here.bookmarked, here.rank, here.updatedAt, here.id) }
            }

            val before = seen.size
            statement.closing { prepared ->
                val rows = prepared.executeQuery()
                while (rows.next()) {
                    val row = Cursor(rows.getString(0)!!, rows.getInt(1), rows.getInt(2), rows.getLong(3))
                    cursor = row
                    seen += row.id
                }
            }
            if (seen.size - before < page) break
        }

        assertEquals("ninguna fila se repite", seen.size, seen.toSet().size)
        assertEquals("no falta ninguna", total, seen.size)
    }

    /**
     * Lo que la Fase 3 promete: el coste por operación no depende de N. Se mide con
     * un corpus pequeño para que el test siga siendo un test —el banco de verdad es
     * el de `ScaleBenchmark`—, pero la forma de la consulta es la del §2.4 y lo que
     * se comprueba es que el plan la resuelve **por índice** y no con un `SCAN` ni un
     * `USE TEMP B-TREE FOR ORDER BY`, que es lo que delataría un orden de un millón
     * de filas escondido detrás de un `LIMIT 100`.
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
        db.execute(
            "CREATE INDEX task_board ON task(repo, state, bookmarked DESC, priority_rank DESC, updated_at DESC)",
        )

        val plan = db.textRows(
            "EXPLAIN QUERY PLAN SELECT id FROM task WHERE repo = 'r' AND state = 's' " +
                "ORDER BY bookmarked DESC, priority_rank DESC, updated_at DESC LIMIT 100",
            columns = 4,
        ).joinToString(" | ") { it[3].orEmpty() }

        assertTrue("debe usar task_board, y el plan fue: $plan", plan.contains("task_board"))
        assertTrue("no debe ordenar en memoria, y el plan fue: $plan", !plan.contains("TEMP B-TREE"))
    }
}
