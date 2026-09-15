package com.tasklane.data.sqlite

import com.intellij.openapi.diagnostic.thisLogger
import com.tasklane.data.store.LoadAlert
import com.tasklane.domain.text.ImageRefParser
import org.jetbrains.sqlite.SqliteException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **§6.2 — qué hacer con una `tasklane.db` dañada.** Sin IDE, para poder romper bases de
 * verdad en un test y ver qué sale.
 *
 * ## El comportamiento que se conserva
 *
 * Con `tasks.xml`, un fichero ilegible se movía a cuarentena, lo que se podía leer entraba
 * igualmente, lo que faltaba se buscaba en el `.bak` y se avisaba de cuántas tareas se
 * recuperaron (`ReadResult.Corrupt`, y desde la Fase 3 el importador en *streaming*). Aquí
 * se hace lo mismo, con una diferencia que obligó a hacer más de lo que el plan decía: el
 * `.bak` se escribía **en cada guardado** y perdía, como mucho, el último; la copia de la
 * base es **de un día**. «Cuarentena + copia» a secas tiraría un día de trabajo por una
 * página rota que casi siempre es de un índice. Por eso la copia sólo pone lo que el
 * fichero dañado **no deja leer**.
 *
 * ## Los pasos
 *
 * 1. **Cuarentena.** `tasklane.db` y su diario se mueven a `tasklane.db.corrupt-<fecha>`
 *    (con su `-wal` al lado, para que se pueda abrir igual). No se borra nunca.
 * 2. **Salvar lo legible**, tabla a tabla y **por rangos de rowid**, sin tocar un solo
 *    índice del fichero dañado: un rango que falla se parte en dos hasta aislar las filas
 *    que de verdad no se leen. Lo derivado —índice de texto, contadores, tupla de orden—
 *    **no se copia**: se rehace con [StoreAudit.rebuild].
 * 3. **Completar desde la copia** sólo las tareas cuyo `seq` cayó en un rango ilegible. El
 *    `seq` de una tarea no cambia en toda su vida —ver la nota 3 de [TaskSchema]— y
 *    `VACUUM INTO` lo conserva, así que es la misma tarea en los dos ficheros. Una tarea
 *    borrada después de la copia **cuyo hueco sí se lee** no resucita; una cuyo hueco no se
 *    lee sí, y se acepta: el fichero de más es un problema mucho menor que el de menos.
 * 4. **Comprobar** con [StoreAudit] y sólo entonces poner la base nueva en su sitio.
 *
 * ## Reanudable
 *
 * La marca [DAMAGED] vive **fuera** de la base —la base es justo lo que no es de fiar— y
 * dice en qué va: si ya se movió a cuarentena y a dónde. La base nueva lleva dentro la fecha
 * de la recuperación que la produjo, así que un cierre entre ponerla en su sitio y borrar la
 * marca se reconoce como recuperación terminada y no se repite. Se cierre el IDE donde se
 * cierre, la apertura siguiente continúa.
 */
internal object StoreRecovery {

    /** La marca: hay que recuperar la base antes de abrirla. Ver el KDoc de la clase. */
    const val DAMAGED = "tasklane.db.damaged"

    /** La base a medio reconstruir. Si existe al empezar, es de un intento que no terminó y se rehace. */
    const val RECOVERING = "tasklane.db.recovering"

    const val QUARANTINE_PREFIX = "tasklane.db.corrupt-"

    /** `chore`: cuándo se recuperó la base, y si fue limpia (`n` = 0) o con huecos (`n` = 1). */
    const val RECOVERED = "db.recovered"

    /**
     * `chore`: la recuperación tuvo huecos. Mientras exista **y** siga en disco el fichero en
     * cuarentena de esa fecha, el recolector de imágenes no borra nada: las tareas que no se
     * pudieron leer pueden nombrar imágenes, y borrarlas quitaría la última forma de rescatar
     * esas tareas a mano.
     */
    const val INCOMPLETE = "db.recovery.incomplete"

    /** Lo que salió. */
    data class Outcome(
        /** Dónde quedó el fichero dañado, o `null` si no había fichero. */
        val quarantined: Path?,
        /** Tareas rescatadas del fichero dañado. */
        val salvaged: Int,
        /** Tareas puestas desde la copia, porque del dañado no se podían leer. */
        val restored: Int,
        /** Rangos de filas del fichero dañado que no se pudieron leer, en cualquier tabla. */
        val unreadable: Int,
        /**
         * Tareas que no quedaron enteras: anclas que no se pudieron rescatar de ninguna parte,
         * o etiquetas rehechas desde el texto normalizado del índice —en minúsculas y sin
         * acentos—.
         */
        val partial: Int,
        /** La fecha de la copia que se usó, o `null` si no se usó ninguna. */
        val backupAt: Long?,
        /** Cuándo empezó: el nombre de la cuarentena y la marca que queda en la base nueva. */
        val stamp: Long,
        /** Si el fichero dañado se pudo abrir. Si no, todo lo que hay salió de la copia. */
        val opened: Boolean = true,
    ) {
        val tasks: Int get() = salvaged + restored

        /** Sin pérdida posible: todo se leyó del fichero dañado. */
        val clean: Boolean get() = opened && unreadable == 0 && partial == 0 && restored == 0

        /**
         * El aviso, con el mismo criterio que tenía un `tasks.xml` ilegible
         * (`ReadResult.Corrupt`): recuperado si algo se salvó, perdido si nada. Y uno que el
         * XML no tenía, porque no podía darse: reparado sin perder nada.
         */
        fun alert(): LoadAlert = when {
            clean -> LoadAlert.Repaired(tasks, quarantined)
            tasks > 0 -> LoadAlert.Recovered(tasks, quarantined)
            else -> LoadAlert.Lost(quarantined)
        }
    }

    // -------------------------------------------------------------- diagnóstico

    /** ¿Es este fallo una base dañada, y no un disco lleno o un permiso? */
    fun isCorruption(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any { e ->
        val code = (e as? SqliteException)?.resultCode?.name.orEmpty()
        code.startsWith("SQLITE_CORRUPT") || code == "SQLITE_NOTADB" || code == "SQLITE_IOERR_CORRUPTFS" ||
            e.message.orEmpty().let { it.contains("malformed", ignoreCase = true) || it.contains("not a database", ignoreCase = true) }
    }

    fun markerOf(dir: Path): Path = dir.resolve(DAMAGED)

    fun pending(dir: Path): Boolean = Files.exists(markerOf(dir))

    /**
     * Apunta que la base está dañada y que la próxima apertura la tiene que recuperar.
     * Idempotente: si ya estaba apuntado, no se toca —podría llevar ya el sitio de la
     * cuarentena—.
     */
    fun markDamaged(dir: Path, reason: String) {
        val marker = markerOf(dir)
        if (Files.exists(marker)) return
        writeMarker(marker, Marker(reason = reason.lineSequence().firstOrNull().orEmpty().take(500), quarantine = null))
    }

    fun quarantineOf(dir: Path, stamp: Long): Path = dir.resolve("$QUARANTINE_PREFIX$stamp")

    // --------------------------------------------------------------- recuperar

    /**
     * Recupera la base de [dir] si hay algo que recuperar. **Bloqueante y O(tamaño de la
     * base)**: en segundo plano, con barra.
     *
     * @param backup la copia diaria, si hay; se comprueba antes de fiarse de ella.
     * @param progress la fase en la que va, para la barra.
     * @return lo que salió, o `null` si no había nada pendiente.
     */
    fun recover(
        dir: Path,
        backup: Path?,
        now: Long = System.currentTimeMillis(),
        progress: (String) -> Unit = {},
    ): Outcome? {
        val marker = markerOf(dir)
        val db = dir.resolve(TaskDb.FILE_NAME)
        var state = readMarker(marker)

        if (state == null) {
            // Sin marca, sólo se recupera si alguien la pidió: quien llama la pone al ver
            // que la base no abre. Nada que hacer.
            return null
        }

        // ¿Terminó ya una recuperación y sólo faltaba borrar la marca?
        if (state.quarantine != null && Files.exists(db)) {
            if (finishedRecovery(db) == state.stamp) {
                Files.deleteIfExists(marker)
                return null
            }
            // Una base que no es la nuestra: una versión anterior del plugin abrió el proyecto
            // entre medias y creó una vacía, y quizá escribió en ella. No se mezcla, pero
            // tampoco se pisa: se aparta junto a la cuarentena.
            move(db, dir.resolve("${state.quarantine}-later-$now"))
            moveIfExists(TaskDb.walOf(db), dir.resolve("${state.quarantine}-later-$now-wal"))
        }

        // 1. Cuarentena, una sola vez.
        if (state.quarantine == null) {
            val stamp = now
            val target = quarantineOf(dir, stamp)
            if (Files.exists(db)) {
                move(db, target)
                moveIfExists(TaskDb.walOf(db), TaskDb.walOf(target))
            }
            Files.deleteIfExists(db.resolveSibling("${db.fileName}-shm"))
            state = state.copy(quarantine = target.fileName.toString(), stamp = stamp)
            writeMarker(marker, state)
        }
        val stamp = state.stamp
        val quarantine = dir.resolve(state.quarantine!!).takeIf(Files::exists)

        // 2 y 3. Reconstruir a un fichero aparte; un intento anterior a medias se tira.
        val building = dir.resolve(RECOVERING)
        Files.deleteIfExists(building)
        Files.deleteIfExists(TaskDb.walOf(building))
        Files.deleteIfExists(building.resolveSibling("${building.fileName}-shm"))

        val target = TaskDb.createAt(building)
        val outcome = try {
            rebuild(target, quarantine, backup, stamp, progress)
        } catch (e: Throwable) {
            runCatching { target.close() }
            throw e
        }
        target.close()

        // 4. En su sitio, y la marca fuera. En este orden: ver el KDoc de la clase.
        move(building, db)
        Files.deleteIfExists(marker)
        return outcome
    }

    private fun rebuild(target: TaskDb, quarantine: Path?, backup: Path?, stamp: Long, progress: (String) -> Unit): Outcome {
        val sql = target.writer
        sql.execute("PRAGMA foreign_keys = OFF")
        sql.execute("PRAGMA cache_size = -${TaskStore.BULK_CACHE_KB}")

        progress("salvage")
        val source = quarantine?.let(::openSource)
        val copy = backup?.takeIf(Files::exists)?.let(::openBackup)
        var salvaged = 0
        var restored = 0
        var unreadable = 0
        var partial = 0
        val lostTasks = ArrayList<LongRange>()

        try {
            if (source != null) {
                val salvage = sql.transaction {
                    val tasks = copyTable(source, sql, "task", rowid = "seq", keepRowid = true)
                    lostTasks += tasks.lost
                    var lost = tasks.lost.size
                    for (table in listOf("tag", "anchor", "blob_ref", "imported", "blob", "chore")) {
                        lost += copyTable(source, sql, table).lost.size
                    }
                    tasks.rows to lost
                }
                salvaged = salvage.first
                unreadable = salvage.second
            }

            progress("backup")
            if (copy != null) {
                // Todo lo de la copia si el dañado no se pudo ni abrir; si se abrió, sólo los
                // huecos de `task`.
                val holes = if (source == null) listOf(0L..Long.MAX_VALUE) else lostTasks
                restored = sql.transaction { restoreFromBackup(copy, sql, holes) }
                sql.transaction {
                    for (table in listOf("imported", "blob")) copyTable(copy, sql, table, orIgnore = true)
                }
            }

            progress("rebuild")
            partial = sql.transaction { fillMissingSides(sql, copy) }
            sql.transaction {
                // La contabilidad de mantenimiento no se hereda de un fichero dañado: la
                // comprobación ya se hizo —es esto— y la reconciliación de imágenes tiene que
                // volver a pasar, porque la tabla `blob` puede haber quedado corta.
                sql.execute("DELETE FROM chore WHERE name LIKE 'db.%' OR name LIKE 'blob.fsck:%'")
                StoreAudit.rebuild(sql)
            }
        } finally {
            runCatching { source?.close() }
            runCatching { copy?.close() }
        }

        val outcome = Outcome(
            quarantined = quarantine,
            salvaged = salvaged,
            restored = restored,
            unreadable = unreadable,
            partial = partial,
            backupAt = if (copy != null && (restored > 0 || source == null)) runCatching { Files.getLastModifiedTime(backup!!).toMillis() }.getOrNull() else null,
            stamp = stamp,
            opened = source != null || quarantine == null,
        )
        sql.transaction {
            saveChore(sql, RECOVERED, stamp, if (outcome.clean) 0 else 1)
            if (!outcome.clean && quarantine != null) saveChore(sql, INCOMPLETE, stamp, 1)
        }
        sql.execute("PRAGMA foreign_keys = ON")

        progress("check")
        val problems = StoreAudit.check(sql)
        check(problems.isEmpty()) { "la base reconstruida no cuadra: $problems" }
        return outcome
    }

    // ------------------------------------------------------------ salvar filas

    private class Copied(val rows: Int, val lost: List<LongRange>)

    /**
     * Copia una tabla de [from] a [to] **por rangos de rowid**, partiendo en dos cada rango
     * que no se deja leer. No usa ningún índice del origen —un salto de rowid va por el
     * árbol de la tabla—, que es lo que permite salvar una tabla entera de un fichero cuyo
     * índice está roto.
     *
     * @param keepRowid si el rowid es parte de la fila (`task.seq`) y hay que conservarlo.
     * @param orIgnore si una fila que ya está gana. Rescatando del dañado gana el dañado
     *   —es lo más reciente—, y completando desde la copia gana lo que ya había.
     */
    private fun copyTable(
        from: Sql,
        to: Sql,
        table: String,
        rowid: String = "rowid",
        keepRowid: Boolean = false,
        orIgnore: Boolean = true,
        within: LongRange? = null,
    ): Copied {
        val columns = columnsOf(to, table).filter { keepRowid || it.first != rowid }
        val names = columns.joinToString(", ") { it.first }
        val select = "SELECT $names FROM $table WHERE $rowid BETWEEN ? AND ?"
        val insert = "INSERT ${if (orIgnore) "OR IGNORE " else ""}INTO $table ($names) VALUES (${Sql.placeholders(columns.size)})"

        val bounds = within ?: (rowidBound(from, table, rowid) ?: return Copied(0, emptyList()))
        var rows = 0
        val lost = ArrayList<LongRange>()
        var lastGood = bounds.first - 1

        fun query(range: LongRange): List<Array<Any>> = from.rows(select, range.first, range.last) { row ->
            Array<Any>(columns.size) { i -> if (columns[i].second) row.getLong(i) else row.getString(i).orEmpty() }
        }

        fun keep(batch: List<Array<Any>>, last: Long) {
            if (batch.isEmpty()) return
            to.batch(insert, columns.size, batch.asSequence())
            rows += batch.size
            lastGood = last
        }

        /**
         * Un rango que no se dejó leer entero, fila a fila.
         *
         * **Un vacío aquí no siempre es un vacío.** Medido sobre una hoja rota: la fila de la
         * primera posición da error, pero las dos siguientes de la misma hoja **no dan error
         * ni aparecen** —la búsqueda binaria sobre punteros basura sale por un lado—, y un
         * rango que las abarca devuelve las de alrededor y se las salta en silencio. Por eso
         * no se parte en mitades, que se fiarían de ese resultado, y por eso un vacío cerca
         * de una fila que falla se cuenta como hueco: la copia lo rellena si lo tiene. Lejos
         * de cualquier fallo, un vacío es una tarea borrada, y no se resucita.
         */
        fun probe(range: LongRange) {
            val failed = ArrayList<Long>()
            val empty = ArrayList<Long>()
            for (rowid in range) {
                val batch = try {
                    query(rowid..rowid)
                } catch (_: Exception) {
                    failed += rowid
                    continue
                }
                if (batch.isEmpty()) empty += rowid else keep(batch, rowid)
            }
            for (rowid in failed) lost += rowid..rowid
            // Sin un solo fallo fila a fila pero con el rango fallando entero, no hay de dónde
            // acotar: todo vacío es sospechoso.
            for (rowid in empty) {
                if (failed.isEmpty() || failed.any { kotlin.math.abs(it - rowid) <= NEAR_FAILURE }) lost += rowid..rowid
            }
        }

        var start = bounds.first
        while (start <= bounds.last) {
            val end = if (bounds.last - start < RANGE) bounds.last else start + RANGE - 1
            val window = start..end
            try {
                keep(query(window), end)
            } catch (_: Exception) {
                // Muy por encima de lo último que se leyó y con el borde del árbol roto, cada
                // rango vacío fallaría: cientos de sondas por rango para no encontrar nada.
                // Ahí se da el rango por perdido sin sondear.
                if (start > lastGood + GIVE_UP_SPAN) lost += window else probe(window)
            }
            if (end == Long.MAX_VALUE) break
            start = end + 1
        }
        return Copied(rows, mergeAdjacent(lost))
    }

    /**
     * Hasta dónde llegan los rowids de una tabla. `max()` lee el borde derecho del árbol, que
     * puede ser justo lo roto; entonces se sube desde lo que ocupa el fichero, que acota por
     * arriba cualquier rowid que se haya podido asignar.
     */
    private fun rowidBound(sql: Sql, table: String, rowid: String): LongRange? {
        val max = runCatching { sql.first("SELECT max($rowid) FROM $table") { it.getLong(0) } }
        max.getOrNull()?.let { return 1L..it }
        if (max.isSuccess) return null
        val pages = runCatching { sql.count("PRAGMA page_count").toLong() }.getOrDefault(0L)
        return 1L..(pages * ROWS_PER_PAGE_BOUND).coerceAtLeast(RANGE.toLong())
    }

    /** Las tareas de la copia que caen en los huecos del dañado, con sus filas hijas. */
    private fun restoreFromBackup(copy: Sql, to: Sql, holes: List<LongRange>): Int {
        var restored = 0
        for (hole in holes) {
            val bound = rowidBound(copy, "task", "seq") ?: break
            val first = maxOf(hole.first, bound.first)
            val last = minOf(hole.last, bound.last)
            if (first > last) continue
            val before = to.count("SELECT count(*) FROM task")
            copyTable(copy, to, "task", rowid = "seq", keepRowid = true, within = first..last)
            restored += to.count("SELECT count(*) FROM task") - before
        }
        if (restored == 0) return 0
        // Las filas hijas de lo que acaba de entrar, de la misma copia: son de esa versión de
        // la tarea, no de la del dañado.
        for (table in StoreAudit.SIDE_TABLES) {
            val columns = columnsOf(to, table)
            val names = columns.joinToString(", ") { it.first }
            val insert = "INSERT OR IGNORE INTO $table ($names) VALUES (${Sql.placeholders(columns.size)})"
            for (hole in holes) {
                val ids = to.rows("SELECT id FROM task WHERE seq BETWEEN ? AND ?", hole.first, hole.last) { it.getString(0).orEmpty() }
                for (chunk in ids.chunked(Sql.MAX_VARIABLES)) {
                    val rows = copy.rows(
                        "SELECT $names FROM $table WHERE task_id IN (${Sql.placeholders(chunk.size)})",
                        *chunk.map { it as Any }.toTypedArray(),
                    ) { row -> Array<Any>(columns.size) { i -> if (columns[i].second) row.getLong(i) else row.getString(i).orEmpty() } }
                    to.batch(insert, columns.size, rows.asSequence())
                }
            }
        }
        return restored
    }

    /**
     * Las tareas rescatadas cuyas filas hijas cayeron en un hueco **de otra tabla**: la
     * tarea se leyó, sus etiquetas o sus anclas no. Los indicadores `has_*` de la propia fila
     * dicen que faltan.
     *
     * - **Referencias a imágenes**: se rehacen del cuerpo, que es de donde salen. Exacto.
     * - **Etiquetas y anclas**: de la copia, si la tarea no cambió desde entonces. Si cambió,
     *   las etiquetas salen de `tags_text` —normalizadas: en minúsculas y sin acentos— y
     *   las anclas se dan por perdidas.
     *
     * @return cuántas tareas quedaron sin todo lo que tenían.
     */
    private fun fillMissingSides(sql: Sql, copy: Sql?): Int {
        val refs = sql.rows(
            "SELECT id, repo, body FROM task t WHERE has_image = 1 AND NOT EXISTS (SELECT 1 FROM blob_ref r WHERE r.task_id = t.id)",
        ) { Triple(it.getString(0).orEmpty(), it.getString(1).orEmpty(), it.getString(2).orEmpty()) }
        sql.batch(
            "INSERT OR IGNORE INTO blob_ref (task_id, blob_id, repo) VALUES (?, ?, ?)",
            3,
            refs.asSequence().flatMap { (id, repo, body) ->
                ImageRefParser.parse(body).map { it.id.value }.distinct().asSequence().map { arrayOf<Any>(id, it, repo) }
            },
        )

        val missing = sql.rows(
            """
            SELECT id, updated_at, tags_text,
                   has_tag = 1 AND NOT EXISTS (SELECT 1 FROM tag g WHERE g.task_id = t.id),
                   has_anchor = 1 AND NOT EXISTS (SELECT 1 FROM anchor a WHERE a.task_id = t.id)
              FROM task t
             WHERE (has_tag = 1 AND NOT EXISTS (SELECT 1 FROM tag g WHERE g.task_id = t.id))
                OR (has_anchor = 1 AND NOT EXISTS (SELECT 1 FROM anchor a WHERE a.task_id = t.id))
            """.trimIndent(),
        ) { Missing(it.getString(0).orEmpty(), it.getLong(1), it.getString(2).orEmpty(), it.getInt(3) != 0, it.getInt(4) != 0) }

        var partial = 0
        for (task in missing) {
            val sameInCopy = copy != null && runCatching {
                copy.count("SELECT count(*) FROM task WHERE id = ? AND updated_at = ?", task.id, task.updatedAt) > 0
            }.getOrDefault(false)
            var lost = false
            if (task.tags) {
                if (sameInCopy) {
                    copySides(copy!!, sql, "tag", task.id)
                } else {
                    lost = true
                    // Sin la tupla de orden: la pone `StoreAudit.rebuild` desde la tarea.
                    sql.batch(
                        "INSERT OR IGNORE INTO tag (task_id, tag, repo, state, bookmarked, priority_rank, sort_date, undated, completed_at, due_date) " +
                            "VALUES (?, ?, '', '', 0, 0, 0, 0, 0, 0)",
                        2,
                        task.tagsText.split(' ').filter(String::isNotBlank).distinct().asSequence().map { arrayOf<Any>(task.id, it) },
                    )
                }
            }
            if (task.anchors) {
                if (sameInCopy) copySides(copy!!, sql, "anchor", task.id) else lost = true
            }
            if (lost) partial++
        }
        return partial
    }

    private class Missing(val id: String, val updatedAt: Long, val tagsText: String, val tags: Boolean, val anchors: Boolean)

    private fun copySides(from: Sql, to: Sql, table: String, taskId: String) {
        val columns = columnsOf(to, table)
        val names = columns.joinToString(", ") { it.first }
        val rows = runCatching {
            from.rows("SELECT $names FROM $table WHERE task_id = ?", taskId) { row ->
                Array<Any>(columns.size) { i -> if (columns[i].second) row.getLong(i) else row.getString(i).orEmpty() }
            }
        }.getOrDefault(emptyList())
        to.batch("INSERT OR IGNORE INTO $table ($names) VALUES (${Sql.placeholders(columns.size)})", columns.size, rows.asSequence())
    }

    // ------------------------------------------------------------------ andamiaje

    /** Las columnas de una tabla en la base nueva, con si son enteras. El esquema manda, no el fichero dañado. */
    private fun columnsOf(sql: Sql, table: String): List<Pair<String, Boolean>> =
        sql.rows("PRAGMA table_info($table)") { it.getString(1).orEmpty() to it.getString(2).orEmpty().equals("INTEGER", ignoreCase = true) }

    /**
     * El fichero dañado, en solo lectura si se deja —así ni siquiera el cierre le vuelca el
     * diario encima—; si no, en lectura y escritura sin escribir nada. `null` si no abre o no
     * se le puede leer ni el esquema.
     */
    private fun openSource(file: Path): Sql? {
        for (readOnly in listOf(true, false)) {
            val sql = runCatching { Sql(file, readOnly) }.getOrNull() ?: continue
            val usable = runCatching {
                sql.count("SELECT count(*) FROM sqlite_master") >= 0 && sql.count("PRAGMA user_version") <= TaskSchema.VERSION
            }.getOrDefault(false)
            if (usable) return sql
            runCatching { sql.close() }
        }
        return null
    }

    /**
     * La copia, **si pasa una comprobación rápida**. Una copia rota no puede tapar huecos: se
     * ignora y se dice que no había.
     */
    private fun openBackup(file: Path): Sql? {
        val sql = runCatching { Sql(file) }.getOrNull() ?: return null
        val ok = runCatching { sql.rows("PRAGMA quick_check(1)") { it.getString(0).orEmpty() } == listOf("ok") }.getOrDefault(false)
        if (ok) return sql
        thisLogger().warn("Tasklane: la copia $file no pasa la comprobación; no se usa para recuperar")
        runCatching { sql.close() }
        return null
    }

    private fun finishedRecovery(db: Path): Long? = runCatching {
        val sql = Sql(db, readOnly = true)
        try {
            sql.first("SELECT at FROM chore WHERE name = ?", RECOVERED) { it.getLong(0) }
        } finally {
            sql.close()
        }
    }.getOrNull()

    private fun saveChore(sql: Sql, name: String, at: Long, n: Long) = sql.update(
        "INSERT INTO chore (name, at, n) VALUES (?, ?, ?) ON CONFLICT(name) DO UPDATE SET at = excluded.at, n = excluded.n",
        name,
        at,
        n,
    )

    private fun mergeAdjacent(unsorted: List<LongRange>): List<LongRange> {
        if (unsorted.isEmpty()) return unsorted
        val ranges = unsorted.sortedBy { it.first }
        val out = ArrayList<LongRange>()
        var current = ranges.first()
        for (next in ranges.drop(1)) {
            current = if (next.first == current.last + 1) current.first..next.last else {
                out += current
                next
            }
        }
        out += current
        return out
    }

    private data class Marker(val reason: String, val quarantine: String?, val stamp: Long = 0L)

    private fun readMarker(file: Path): Marker? {
        if (!Files.exists(file)) return null
        val values = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            .toMap()
        return Marker(
            reason = values["reason"].orEmpty(),
            quarantine = values["quarantine"]?.takeIf(String::isNotBlank),
            stamp = values["stamp"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun writeMarker(file: Path, marker: Marker) {
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, "reason=${marker.reason}\nquarantine=${marker.quarantine.orEmpty()}\nstamp=${marker.stamp}\n")
        move(tmp, file)
    }

    private fun moveIfExists(from: Path, to: Path) {
        if (Files.exists(from)) move(from, to)
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Filas por rango de rowid al salvar. Un rango sano es una consulta; uno que falla se
     * parte, y lo que dentro de él salga vacío se trata como hueco —ver [copyTable]—. Por eso
     * no es más grande: acota cuánto de alrededor de una hoja rota se va a buscar a la copia.
     */
    private const val RANGE = 256

    /**
     * A qué distancia de una fila que falla un vacío se trata como hueco. Una hoja de 4 KB no
     * guarda más de unas decenas de filas de tarea, así que esto cubre la hoja rota y deja
     * fuera lo borrado de verdad un poco más allá. Ver [copyTable].
     */
    private const val NEAR_FAILURE = 64L

    /** Cuánto por encima de la última fila leída se sigue sondeando lo que falla. Ver [copyTable]. */
    private const val GIVE_UP_SPAN = RANGE * 16L

    /**
     * Cota de rowids por página cuando no se puede leer `max()`. Una fila de tarea no baja de
     * unas decenas de bytes y una página son 4 KB: cien es holgado, y un rango vacío cuesta un
     * salto de árbol.
     */
    private const val ROWS_PER_PAGE_BOUND = 100L
}
