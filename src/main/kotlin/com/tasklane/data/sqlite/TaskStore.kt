package com.tasklane.data.sqlite

import com.tasklane.diagnostics.TaskStats
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.AnchoredTask
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * El almacén: la única pieza que escribe en `tasklane.db`.
 *
 * ## Qué hace distinto a `TaskFileStore`
 *
 * `TaskFileStore.write` recibía **la lista entera** de un repositorio y volcaba el
 * fichero completo; a un millón de tareas eso son 2,9 GB serializados por cada
 * pulsación del marcador. Aquí se reciben [Mutation]s: las filas que el comando tocó,
 * o —cuando el cambio no cabe fila a fila— la sentencia que lo describe.
 *
 * ## Una transacción por comando, y adiós al debounce de 500 ms
 *
 * Hasta la Fase 2 había un `debounce` que agrupaba escrituras porque **cada** volcado
 * costaba el fichero entero; con WAL, confirmar una transacción es escribir las páginas
 * que cambiaron al final de un fichero secuencial, y con `synchronous = NORMAL` ni
 * siquiera hay un `fsync` por confirmación. Agrupar dejó de pagar, y quitarlo arregla de
 * paso lo que el debounce costaba: hasta medio segundo de ediciones perdidas si el IDE
 * se caía.
 *
 * ## Los contadores se mantienen aquí, no se consultan
 *
 * `count(*)` sobre un estado con un millón de filas sigue siendo un recorrido de
 * índice aunque no toque la tabla, y las pestañas lo pedirían en cada repintado. Cada
 * escritura lleva su propia contabilidad —[Deltas]— y la deja en `counter` y
 * `priority_counter` **dentro de la misma transacción**: si la transacción se deshace,
 * los contadores se deshacen con ella.
 *
 * ## `seq` lo asigna esta clase
 *
 * El rowid no se deja a SQLite porque la migración necesita poder escribir la fila de
 * la tarea y la del índice de texto **en la misma tanda**, y una inserción por lotes no
 * devuelve rowids. Ver la nota 3 de [TaskSchema].
 *
 * **Bloqueante y fuera del EDT** salvo los comandos sueltos del usuario, que son una
 * transacción de un puñado de sentencias indexadas.
 */
internal class TaskStore(private val db: TaskDb) {

    private val sql = db.writer

    /** El siguiente `seq` libre. Ver el KDoc de la clase. */
    private val nextSeq = AtomicLong(sql.count("SELECT coalesce(max(seq), 0) FROM task").toLong() + 1)

    val readOnly: Boolean get() = db.readOnly

    // -------------------------------------------------------------- lecturas puntuales

    /**
     * La tarea que un comando nombra. Es lo que sustituye a «el reducer recibe el
     * modelo entero»: se cargan **las tareas del comando**, no el corpus.
     */
    fun task(id: TaskId): Task? = tasks(listOf(id)).firstOrNull()

    fun tasks(ids: Collection<TaskId>): List<Task> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Task>(ids.size)
        for (chunk in ids.chunked(Sql.MAX_VARIABLES)) {
            val params = chunk.map { it.value as Any }.toTypedArray()
            out += sql.rows(
                "SELECT ${TaskRows.COLUMNS} FROM task WHERE id IN (${Sql.placeholders(chunk.size)})",
                *params,
                read = TaskRows::read,
            )
        }
        return hydrate(out)
    }

    /**
     * Les pone las etiquetas y las anclas a un puñado de tareas con **dos** consultas,
     * no con dos por tarea. Es la diferencia entre una página de cincuenta filas que
     * cuesta tres saltos de índice y una que cuesta ciento uno.
     */
    fun hydrate(tasks: List<Task>): List<Task> = hydrate(sql, tasks)

    /** Siguiente hueco del orden manual. O(log n): un salto sobre `task_ord`. */
    fun nextOrder(repo: RepoKey): Long =
        (sql.first("SELECT max(ord) FROM task WHERE repo = ?", repo.value) { it.getLong(0) } ?: 0L) + ORDER_GAP

    /** Los pares `(repo, estado)` que tienen alguna tarea. Sale de `counter`: son cinco filas. */
    fun statesInUse(): List<Pair<RepoKey, StateId>> =
        sql.rows("SELECT repo, state FROM counter WHERE n > 0") {
            RepoKey(it.getString(0).orEmpty()) to StateId(it.getString(1).orEmpty())
        }

    fun prioritiesInUse(): List<Pair<RepoKey, PriorityId>> =
        sql.rows("SELECT repo, priority FROM priority_counter WHERE n > 0") {
            RepoKey(it.getString(0).orEmpty()) to PriorityId(it.getString(1).orEmpty())
        }

    fun repos(): List<RepoKey> =
        sql.rows("SELECT DISTINCT repo FROM counter WHERE n > 0") { RepoKey(it.getString(0).orEmpty()) }

    fun countOf(repo: RepoKey): Int =
        sql.count("SELECT coalesce(sum(n), 0) FROM counter WHERE repo = ?", repo.value)

    // ------------------------------------------------------------------- escritura

    /** Qué pasó al aplicar unas mutaciones. Es el recibo que el servicio convierte en avisos. */
    data class Applied(
        /** Filas tocadas. Cero significa que no hay nada que repintar. */
        val rows: Int,
        /** Tareas que **este** cambio acaba de aparcar por configuración ausente. */
        val parked: Parked = Parked.NONE,
    )

    /**
     * Las tareas aparcadas, contadas y con una muestra.
     *
     * Una muestra y no la lista entera porque borrar un estado con un millón de tareas
     * dentro las aparca todas, y el aviso sólo necesita el número; los ids son para el
     * «enséñamelas», y enseñar más de una pantalla no significa nada.
     */
    data class Parked(val count: Int, val ids: Set<TaskId>) {
        companion object {
            val NONE = Parked(0, emptySet())
            const val SAMPLE = 50
        }
    }

    /** Aplica las mutaciones en **una** transacción. */
    fun apply(config: TasklaneConfig, mutations: List<Mutation>): Applied {
        if (mutations.isEmpty() || db.readOnly) return Applied(0)
        return sql.transaction {
            val deltas = Deltas()
            var rows = 0
            var parked = Parked.NONE
            for (mutation in mutations) {
                when (mutation) {
                    is Mutation.Upsert -> {
                        for (task in mutation.tasks) upsert(task, config, deltas)
                        rows += mutation.tasks.size
                    }

                    is Mutation.Delete -> rows += delete(mutation.ids, deltas)
                    is Mutation.Reassign -> rows += reassign(config, mutation, deltas)
                    is Mutation.Reprioritize -> rows += reprioritize(config, mutation, deltas)
                    is Mutation.Backfill -> rows += backfill(config, mutation.states)
                    is Mutation.Forget -> rows += forget(mutation.repo, deltas)
                    is Mutation.Renormalize -> {
                        val result = renormalize(mutation.before, config, deltas)
                        rows += result.rows
                        parked = result.parked
                    }
                }
            }
            deltas.flush(sql)
            Applied(rows, parked)
        }
    }

    // ----------------------------------------------------------------- fila a fila

    private fun upsert(task: Task, config: TasklaneConfig, deltas: Deltas) {
        val old = previous(task.id)
        if (old != null) {
            deltas.remove(old)
            ftsDelete(old)
        }
        val seq = old?.seq ?: nextSeq.getAndIncrement()
        sql.update(TaskRows.UPSERT.trimIndent(), *TaskRows.values(seq, task, config))
        ftsInsert(seq, task)
        writeSides(task, config)
        deltas.add(task, config)
    }

    private fun delete(ids: List<TaskId>, deltas: Deltas): Int {
        var removed = 0
        for (id in ids) {
            val old = previous(id) ?: continue
            ftsDelete(old)
            deltas.remove(old)
            // `ON DELETE CASCADE` se lleva tag, anchor y blob_ref; el índice de texto
            // no cascadea porque es una tabla virtual, y por eso se borra a mano.
            sql.update("DELETE FROM task WHERE id = ?", id.value)
            removed++
        }
        return removed
    }

    /** Las filas de `tag`, `anchor` y `blob_ref` de una tarea: se rehacen enteras. */
    private fun writeSides(task: Task, config: TasklaneConfig) {
        val id = task.id.value
        sql.update("DELETE FROM tag WHERE task_id = ?", id)
        sql.update("DELETE FROM anchor WHERE task_id = ?", id)
        sql.update("DELETE FROM blob_ref WHERE task_id = ?", id)

        if (task.tags.isNotEmpty()) {
            val rank = TaskRows.rankOf(task, config)
            val sortDate = TaskRows.sortDate(task, config)
            for (tag in task.tags) {
                sql.update(
                    "INSERT INTO tag (task_id, tag, repo, state, bookmarked, priority_rank, sort_date, " +
                        "undated, completed_at, due_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id,
                    tag,
                    task.repo.value,
                    task.stateId.value,
                    if (task.bookmarked) 1 else 0,
                    rank,
                    sortDate,
                    if (TaskRows.undated(task, config)) 1 else 0,
                    TaskRows.millis(task.completedAt),
                    TaskRows.millis(task.dueDate),
                )
            }
        }
        task.anchors.forEachIndexed { index, anchor ->
            sql.update(
                "INSERT INTO anchor (task_id, pos, path, line, col, text) VALUES (?, ?, ?, ?, ?, ?)",
                id,
                index,
                anchor.path,
                anchor.line,
                anchor.column,
                anchor.text,
            )
        }
        for (blob in task.attachments.map { it.id.value }.distinct()) {
            sql.update(
                "INSERT INTO blob_ref (task_id, blob_id, repo) VALUES (?, ?, ?)",
                id,
                blob,
                task.repo.value,
            )
        }
    }

    // ------------------------------------------------------------------ operaciones masivas

    /**
     * `UPDATE task SET state = ? WHERE state = ?`: lo que en la Fase 2 era un `map`
     * sobre un millón de objetos.
     *
     * Se recorre repositorio a repositorio en vez de lanzar un único `WHERE state = ?`
     * porque el índice empieza por `repo`: sin ese prefijo, mover diez tareas obligaría
     * a recorrer el millón.
     */
    private fun reassign(config: TasklaneConfig, mutation: Mutation.Reassign, deltas: Deltas): Int {
        val to = config.stateOrDefault(mutation.to)
        val now = mutation.at.toEpochMilli()
        val terminal = if (to.terminal) 1 else 0
        var rows = 0

        for (repo in repos()) {
            val before = counterOf(repo, mutation.from) ?: continue
            // Las aparcadas primero: llevan el estado original en `extra` y hay que
            // quitárselo, igual que hace `applyState`, porque moverlas a mano es una
            // decisión del usuario y deja de ser un aparcamiento. Son las que el índice
            // parcial `task_parked` enumera, o sea un puñado.
            unparkExtra(repo, mutation.from, TaskReducer.ORIG_STATE)

            val completed = "CASE WHEN $terminal = 1 AND completed_at = ${TaskSchema.NO_DATE} " +
                "THEN $now ELSE completed_at END"
            sql.update(
                """
                UPDATE task
                   SET state = ?, state_terminal = $terminal, updated_at = $now,
                       completed_at = $completed,
                       ${anchorSet(to.anchor, "$now", completed)}
                 WHERE repo = ? AND state = ?
                """.trimIndent(),
                mutation.to.value,
                repo.value,
                mutation.from.value,
            )
            syncTags(repo, mutation.from, mutation.to)
            rows += before.n
            deltas.move(repo, mutation.from, mutation.to, before, toTerminal = to.terminal)
        }
        return rows
    }

    private fun reprioritize(config: TasklaneConfig, mutation: Mutation.Reprioritize, deltas: Deltas): Int {
        val to = config.priorityOrDefault(mutation.to)
        val now = mutation.at.toEpochMilli()
        var rows = 0

        for (repo in repos()) {
            val before = sql.count(
                "SELECT n FROM priority_counter WHERE repo = ? AND priority = ?",
                repo.value,
                mutation.from.value,
            )
            if (before == 0) continue
            unparkExtra(repo, null, TaskReducer.ORIG_PRIORITY, priority = mutation.from)

            sql.update(
                """
                UPDATE task
                   SET priority = ?, priority_rank = ${to.order}, updated_at = $now,
                       sort_date = ${updatedAnchorExpr(config, now)}
                 WHERE repo = ? AND priority = ?
                """.trimIndent(),
                mutation.to.value,
                repo.value,
                mutation.from.value,
            )
            sql.update(
                """
                UPDATE tag
                   SET priority_rank = ${to.order},
                       sort_date = (SELECT t.sort_date FROM task t WHERE t.id = tag.task_id)
                 WHERE repo = ? AND task_id IN (SELECT id FROM task WHERE repo = ? AND priority = ?)
                """.trimIndent(),
                repo.value,
                repo.value,
                mutation.to.value,
            )
            rows += before
            deltas.movePriority(repo, mutation.from, mutation.to, before)
        }
        return rows
    }

    private fun backfill(config: TasklaneConfig, states: Set<StateId>): Int {
        var rows = 0
        for (repo in repos()) {
            for (stateId in states) {
                val state = config.state(stateId) ?: continue
                val counter = counterOf(repo, stateId) ?: continue
                if (counter.open == 0) continue
                // `updated_at` no se toca: la fecha que se rellena se DERIVA de ella.
                // Al sellar la fecha, un estado anclado en ella deja de tener tareas
                // «sin fecha»: la que se acaba de rellenar ES la del anclaje.
                val sortDate =
                    if (state.anchor == DateAnchor.COMPLETED) ", sort_date = updated_at, undated = 0" else ""
                sql.update(
                    """
                    UPDATE task SET completed_at = updated_at$sortDate
                     WHERE repo = ? AND state = ? AND completed_at = ${TaskSchema.NO_DATE}
                    """.trimIndent(),
                    repo.value,
                    stateId.value,
                )
                syncTags(repo, stateId, stateId)
                rows += counter.open
                sql.update(
                    "UPDATE counter SET n_open = 0 WHERE repo = ? AND state = ?",
                    repo.value,
                    stateId.value,
                )
            }
        }
        return rows
    }

    private fun forget(repo: RepoKey, deltas: Deltas): Int {
        val total = countOf(repo)
        // El índice de texto no cascadea: sus filas se borran a mano, a tandas, con el
        // contenido que la tabla virtual necesita para localizarlas.
        while (true) {
            val batch = sql.rows(
                "SELECT seq, title, body, tags_text, files_text FROM task WHERE repo = ? LIMIT 2000",
                repo.value,
            ) {
                Fts(
                    it.getLong(0),
                    it.getString(1).orEmpty(),
                    it.getString(2).orEmpty(),
                    it.getString(3).orEmpty(),
                    it.getString(4).orEmpty(),
                )
            }
            if (batch.isEmpty()) break
            for (row in batch) ftsDelete(row)
            sql.update(
                "DELETE FROM task WHERE seq IN (${Sql.placeholders(batch.size)})",
                *batch.map { it.seq as Any }.toTypedArray(),
            )
        }
        sql.update("DELETE FROM counter WHERE repo = ?", repo.value)
        sql.update("DELETE FROM priority_counter WHERE repo = ?", repo.value)
        sql.update("DELETE FROM imported WHERE repo = ?", repo.value)
        deltas.forget(repo)
        return total
    }

    // ------------------------------------------------------- configuración desincronizada

    private class Renormalized(val rows: Int, val parked: Parked)

    /**
     * Lo que `TaskReducer.normalize` hacía tarea a tarea, hecho por claves.
     *
     * Recibe la configuración **anterior** y por eso puede tocar sólo lo que cambió: lo
     * normal —y `ConfigChanged` se emite en cada arranque— es que no haya cambiado
     * nada y esto no escriba una sola fila. El antiguo `mapAllTasks` conseguía lo mismo
     * conservando identidades, pero recorriendo el corpus entero para averiguarlo.
     */
    private fun renormalize(before: TasklaneConfig, config: TasklaneConfig, deltas: Deltas): Renormalized {
        var rows = 0

        // 1. Lo derivado de un estado que sigue existiendo pero cambió de forma.
        for (state in config.states) {
            // Un estado NUEVO no tiene tareas dentro: no hay nada derivado que rehacer.
            // Importa porque añadir un estado en los ajustes no puede costar una pasada
            // por la tabla, y porque `ConfigChanged` se emite también en cada arranque.
            val old = before.state(state.id) ?: continue
            if (old.terminal == state.terminal && old.anchor == state.anchor) continue
            val terminal = if (state.terminal) 1 else 0
            sql.update(
                "UPDATE task SET state_terminal = $terminal, ${anchorSet(state.anchor)} WHERE state = ?",
                state.id.value,
            )
            sql.update(
                """
                UPDATE tag
                   SET sort_date = (SELECT t.sort_date FROM task t WHERE t.id = tag.task_id),
                       undated = (SELECT t.undated FROM task t WHERE t.id = tag.task_id)
                 WHERE state = ?
                """.trimIndent(),
                state.id.value,
            )
            rows++
        }

        // 2. Lo derivado de una prioridad que cambió de sitio en la lista.
        for (priority in config.priorities) {
            val old = before.priority(priority.id) ?: continue
            if (old.order == priority.order) continue
            sql.update(
                "UPDATE task SET priority_rank = ? WHERE priority = ? AND priority_rank <> ?",
                priority.order,
                priority.id.value,
                priority.order,
            )
            sql.update(
                "UPDATE tag SET priority_rank = ? WHERE priority_rank <> ? AND task_id IN " +
                    "(SELECT id FROM task WHERE priority = ?)",
                priority.order,
                priority.order,
                priority.id.value,
            )
            rows++
        }

        // 3. Se fue: estados y prioridades que ya no están en la configuración.
        val parkedIds = LinkedHashSet<TaskId>()
        var parkedCount = 0
        for ((repo, stateId) in statesInUse()) {
            if (config.state(stateId) != null) continue
            val moved = park(repo, stateId, config, deltas, parkedIds)
            parkedCount += moved
            rows += moved
        }
        for ((repo, priorityId) in prioritiesInUse()) {
            if (config.priority(priorityId) != null) continue
            val moved = parkPriority(repo, priorityId, config, deltas, parkedIds)
            parkedCount += moved
            rows += moved
        }

        // 4. Volvió: lo aparcado cuyo original reapareció. El índice parcial
        //    `task_parked` es lo que hace que esto cueste lo que cuestan las aparcadas.
        rows += unpark(config, deltas)

        return Renormalized(rows, Parked(parkedCount, parkedIds))
    }

    /** Manda al estado por defecto lo que estaba en un estado que ya no existe. */
    private fun park(
        repo: RepoKey,
        gone: StateId,
        config: TasklaneConfig,
        deltas: Deltas,
        sample: MutableSet<TaskId>,
    ): Int {
        val counter = counterOf(repo, gone) ?: return 0
        val target = config.defaultState
        sample += sql.rows(
            "SELECT id FROM task WHERE repo = ? AND state = ? LIMIT ${Parked.SAMPLE}",
            repo.value,
            gone.value,
        ) { TaskId(it.getString(0).orEmpty()) }

        // Las que ya llevaban algo en `extra` van una a una: son las del índice parcial
        // y hay que respetar el `putIfAbsent` —el id que vale la pena recordar es el
        // primero, no el fallback por el que pasó después—.
        val awkward = sql.rows(
            "SELECT ${TaskRows.COLUMNS} FROM task WHERE repo = ? AND state = ? AND extra <> ''",
            repo.value,
            gone.value,
        ) { TaskRows.read(it) }
        for (task in hydrate(awkward)) {
            val extra = LinkedHashMap(task.extra)
            extra.putIfAbsent(TaskReducer.ORIG_STATE, gone.value)
            upsert(task.copy(stateId = target.id, extra = extra), config, deltas)
        }

        val terminal = if (target.terminal) 1 else 0
        sql.update(
            """
            UPDATE task
               SET state = ?, state_terminal = $terminal, ${anchorSet(target.anchor)}, extra = ?
             WHERE repo = ? AND state = ? AND extra = ''
            """.trimIndent(),
            target.id.value,
            TaskRows.encodeExtra(mapOf(TaskReducer.ORIG_STATE to gone.value)),
            repo.value,
            gone.value,
        )
        syncTags(repo, gone, target.id)
        deltas.move(repo, gone, target.id, counter, toTerminal = target.terminal)
        return counter.n
    }

    private fun parkPriority(
        repo: RepoKey,
        gone: PriorityId,
        config: TasklaneConfig,
        deltas: Deltas,
        sample: MutableSet<TaskId>,
    ): Int {
        val n = sql.count(
            "SELECT n FROM priority_counter WHERE repo = ? AND priority = ?",
            repo.value,
            gone.value,
        )
        if (n == 0) return 0
        val target = config.defaultPriority
        if (sample.size < Parked.SAMPLE) {
            sample += sql.rows(
                "SELECT id FROM task WHERE repo = ? AND priority = ? LIMIT ${Parked.SAMPLE}",
                repo.value,
                gone.value,
            ) { TaskId(it.getString(0).orEmpty()) }
        }

        val awkward = sql.rows(
            "SELECT ${TaskRows.COLUMNS} FROM task WHERE repo = ? AND priority = ? AND extra <> ''",
            repo.value,
            gone.value,
        ) { TaskRows.read(it) }
        for (task in hydrate(awkward)) {
            val extra = LinkedHashMap(task.extra)
            extra.putIfAbsent(TaskReducer.ORIG_PRIORITY, gone.value)
            upsert(task.copy(priorityId = target.id, extra = extra), config, deltas)
        }

        sql.update(
            "UPDATE task SET priority = ?, priority_rank = ${target.order}, extra = ? " +
                "WHERE repo = ? AND priority = ? AND extra = ''",
            target.id.value,
            TaskRows.encodeExtra(mapOf(TaskReducer.ORIG_PRIORITY to gone.value)),
            repo.value,
            gone.value,
        )
        sql.update(
            "UPDATE tag SET priority_rank = ${target.order} WHERE task_id IN " +
                "(SELECT id FROM task WHERE repo = ? AND priority = ?)",
            repo.value,
            target.id.value,
        )
        deltas.movePriority(repo, gone, target.id, n)
        return n
    }

    /** Devuelve a su sitio lo aparcado cuyo estado o prioridad original ha reaparecido. */
    private fun unpark(config: TasklaneConfig, deltas: Deltas): Int {
        val parked = sql.rows(
            "SELECT ${TaskRows.COLUMNS} FROM task WHERE extra <> ''",
        ) { TaskRows.read(it) }
        if (parked.isEmpty()) return 0
        var rows = 0
        for (task in hydrate(parked)) {
            val extra = LinkedHashMap(task.extra)
            var stateId = task.stateId
            var priorityId = task.priorityId
            extra[TaskReducer.ORIG_STATE]?.let { orig ->
                config.state(StateId(orig))?.let { stateId = it.id; extra -= TaskReducer.ORIG_STATE }
            }
            extra[TaskReducer.ORIG_PRIORITY]?.let { orig ->
                config.priority(PriorityId(orig))?.let { priorityId = it.id; extra -= TaskReducer.ORIG_PRIORITY }
            }
            if (stateId == task.stateId && priorityId == task.priorityId) continue
            upsert(task.copy(stateId = stateId, priorityId = priorityId, extra = extra), config, deltas)
            rows++
        }
        return rows
    }

    /**
     * Quita una marca de aparcamiento de las pocas filas que la tengan, sin tocar el
     * resto de `extra`. Se apoya en el índice parcial, así que cuesta lo que cuestan
     * las aparcadas y no lo que cuesta el proyecto.
     */
    private fun unparkExtra(repo: RepoKey, state: StateId?, key: String, priority: PriorityId? = null) {
        val where = StringBuilder("repo = ? AND extra <> ''")
        val params = ArrayList<Any>()
        params += repo.value
        state?.let { where.append(" AND state = ?"); params += it.value }
        priority?.let { where.append(" AND priority = ?"); params += it.value }

        val rows = sql.rows(
            "SELECT id, extra FROM task WHERE $where",
            *params.toTypedArray(),
        ) { it.getString(0).orEmpty() to it.getString(1).orEmpty() }

        for ((id, encoded) in rows) {
            val extra = TaskRows.decodeExtra(encoded)
            if (key !in extra) continue
            sql.update("UPDATE task SET extra = ? WHERE id = ?", TaskRows.encodeExtra(extra - key), id)
        }
    }

    // --------------------------------------------------------------------- migración

    /**
     * Escribe muchas tareas nuevas de golpe. Es el camino de la migración del §3.3 y el
     * único que **no** consulta si la fila ya existía: quien lo llama sabe que no.
     *
     * Con sentencias por lotes y `seq` asignado aquí, un millón de tareas son un puñado
     * de sentencias preparadas y no ocho millones.
     */
    /**
     * Le presta a SQLite una caché grande mientras dure [block], y se la quita después.
     *
     * Importar es el único trabajo de este plugin con un patrón de escritura **aleatorio
     * y masivo**: cada tarea toca seis índices de `task`, la tabla de etiquetas, la de
     * anclas, la de referencias y el índice de texto, y en cuanto el árbol de cada uno
     * deja de caber en la caché de páginas, insertar una fila pasa a ser un puñado de
     * lecturas de disco. Medido: con la caché de 32 MB del §3.1, la velocidad de
     * importación se desploma según crece la base.
     *
     * Son megabytes **nativos**, no del heap de la JVM, así que no tocan el presupuesto
     * del §2.6; y se devuelven al terminar, porque una caché así en reposo sería memoria
     * reservada para nada.
     */
    fun <T> bulk(block: () -> T): T {
        sql.execute("PRAGMA cache_size = -$BULK_CACHE_KB")
        return try {
            block()
        } finally {
            runCatching { sql.execute(TaskSchema.PRAGMAS.first { it.contains("cache_size") }) }
        }
    }

    fun importBatch(batch: List<Task>, config: TasklaneConfig) {
        // Un `tasks.xml` editado a mano puede traer el mismo id dos veces, y con
        // `INSERT` a secas eso tumba la tanda entera. Quitar los repetidos aquí cubre el
        // caso normal —un bloque duplicado al copiar y pegar—; si los dos están en tandas
        // distintas, la migración lo reporta como fichero roto y lo ya importado se
        // queda, que es exactamente lo que hacía el códec con una tarea sin id.
        val tasks = batch.distinctBy { it.id }
        if (tasks.isEmpty()) return
        sql.transaction {
            val deltas = Deltas()
            val seqs = LongArray(tasks.size) { nextSeq.getAndIncrement() }

            sql.batch(
                TaskRows.INSERT_NEW.trimIndent(),
                TaskRows.INSERT_PARAMS,
                tasks.asSequence().mapIndexed { i, task -> TaskRows.values(seqs[i], task, config) },
            )
            sql.batch(
                "INSERT INTO task_fts(rowid, title, body, tags_text, files_text) VALUES (?, ?, ?, ?, ?)",
                5,
                tasks.asSequence().mapIndexed { i, task ->
                    arrayOf<Any>(seqs[i], task.title, task.body, TaskRows.tagsText(task), TaskRows.filesText(task))
                },
            )
            sql.batch(
                "INSERT INTO tag (task_id, tag, repo, state, bookmarked, priority_rank, sort_date, " +
                    "undated, completed_at, due_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                10,
                tasks.asSequence().flatMap { task ->
                    task.tags.asSequence().map { tag ->
                        arrayOf<Any>(
                            task.id.value,
                            tag,
                            task.repo.value,
                            task.stateId.value,
                            if (task.bookmarked) 1 else 0,
                            TaskRows.rankOf(task, config),
                            TaskRows.sortDate(task, config),
                            if (TaskRows.undated(task, config)) 1 else 0,
                            TaskRows.millis(task.completedAt),
                            TaskRows.millis(task.dueDate),
                        )
                    }
                },
            )
            sql.batch(
                "INSERT INTO anchor (task_id, pos, path, line, col, text) VALUES (?, ?, ?, ?, ?, ?)",
                6,
                tasks.asSequence().flatMap { task ->
                    task.anchors.asSequence().mapIndexed { pos, anchor ->
                        arrayOf<Any>(task.id.value, pos, anchor.path, anchor.line, anchor.column, anchor.text)
                    }
                },
            )
            sql.batch(
                "INSERT INTO blob_ref (task_id, blob_id, repo) VALUES (?, ?, ?)",
                3,
                tasks.asSequence().flatMap { task ->
                    task.attachments.asSequence().map { it.id.value }.distinct().map { blob ->
                        arrayOf<Any>(task.id.value, blob, task.repo.value)
                    }
                },
            )
            for (task in tasks) deltas.add(task, config)
            deltas.flush(sql)
        }
    }

    fun markImported(repo: RepoKey, version: Int, tasks: Int, complete: Boolean, at: Long) {
        sql.update(
            "INSERT INTO imported (repo, version, tasks, complete, at) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(repo) DO UPDATE SET version = excluded.version, tasks = excluded.tasks, " +
                "complete = excluded.complete, at = excluded.at",
            repo.value,
            version,
            tasks,
            if (complete) 1 else 0,
            at,
        )
    }

    /** ¿Ya está migrado del todo este repositorio? */
    fun isImported(repo: RepoKey): Boolean =
        sql.count("SELECT count(*) FROM imported WHERE repo = ? AND complete = 1", repo.value) > 0

    /** Cuántas tareas del `tasks.xml` ya se escribieron. Es por donde continúa una migración a medias. */
    fun importedCount(repo: RepoKey): Int =
        sql.count("SELECT coalesce(max(tasks), 0) FROM imported WHERE repo = ?", repo.value)

    /** Una transacción, para quien tenga que componer varias operaciones en un solo `commit`. */
    fun <T> write(block: () -> T): T = sql.transaction(block)

    // ------------------------------------------------------------------ lecturas de la vista

    /**
     * Las tareas vencidas de un repositorio, **acotadas**.
     *
     * El filtro de «vencidas» no se pagina en SQL y no es una omisión: `due_date` no es
     * una columna del orden, así que paginarlo obligaría a recorrer el estado entero por
     * página. Lo que sí es cierto es que lo vencido está acotado por su propia
     * naturaleza —son las tareas con fecha en el pasado y sin cerrar—, así que se traen
     * y las ordena `MemoryPager`, igual que los aciertos de una búsqueda.
     *
     * El tope existe de todos modos: un proyecto importado con un millón de fechas
     * pasadas no puede tumbar la ventana. Se dice en la lista cuando se alcanza.
     */
    fun overdue(repo: RepoKey, now: Instant, limit: Int = OVERDUE_LIMIT): List<Task> {
        val rows = db.reader.rows(
            "SELECT ${TaskRows.COLUMNS} FROM task WHERE repo = ? AND completed_at = ${TaskSchema.NO_DATE} " +
                "AND due_date <> ${TaskSchema.NO_DATE} AND due_date < ? ORDER BY due_date LIMIT ?",
            repo.value,
            now.toEpochMilli(),
            limit,
            read = TaskRows::read,
        )
        return hydrate(db.reader, rows)
    }

    /**
     * Todo lo de un repositorio. Lo piden la exportación y «Exportar y quitar», que son
     * O(n) por definición: exportar un millón de tareas es leer un millón de tareas.
     * Escribirlo en *streaming* es la Fase 5.
     */
    fun allOf(repo: RepoKey): List<Task> = hydrate(
        db.reader,
        db.reader.rows(
            "SELECT ${TaskRows.COLUMNS} FROM task WHERE repo = ? ORDER BY ord",
            repo.value,
            read = TaskRows::read,
        ),
    )

    /**
     * Qué tareas cuelgan de un fichero, para marcar sus líneas en el editor.
     *
     * Es el §3.6: `AnchorMarkers` preguntaba recorriendo **el modelo entero** en cada
     * cambio, así que abrir un fichero costaba lo mismo que abrir el proyecto. Ahora es
     * un salto sobre `anchor_by_path` y un puñado de filas.
     *
     * Lo terminal no sale, y se filtra aquí y no después: una tarea en un estado que
     * significa «hecho» ya no es una nota sobre el código, y traerla para descartarla
     * sería traer la historia entera del fichero.
     */
    fun anchorsIn(path: String): List<AnchoredTask> {
        val rows = db.reader.rows(
            "SELECT ${TaskRows.columns("t")}, a.path, a.line, a.col, a.text " +
                "FROM anchor a JOIN task t ON t.id = a.task_id WHERE a.path = ? AND t.state_terminal = 0",
            path,
        ) {
            TaskRows.read(it) to CodeAnchor(
                path = it.getString(13).orEmpty(),
                line = it.getInt(14),
                column = it.getInt(15),
                text = it.getString(16).orEmpty(),
            )
        }
        if (rows.isEmpty()) return emptyList()
        val hydrated = hydrate(db.reader, rows.map { it.first }).associateBy { it.id }
        return rows.map { (task, anchor) -> AnchoredTask(hydrated[task.id] ?: task, anchor) }
    }

    /** El retrato de un repositorio para el informe de diagnóstico, en agregados. */
    fun statsOf(repo: RepoKey): TaskStats = db.reader.first(
        """
        SELECT count(*), coalesce(sum(length(body)), 0),
               (SELECT count(*) FROM anchor a JOIN task k ON k.id = a.task_id WHERE k.repo = task.repo),
               (SELECT count(*) FROM tag g WHERE g.repo = task.repo),
               (SELECT count(*) FROM blob_ref b WHERE b.repo = task.repo),
               (SELECT count(DISTINCT blob_id) FROM blob_ref b WHERE b.repo = task.repo),
               (SELECT count(*) FROM task k WHERE k.repo = task.repo AND k.extra <> '')
          FROM task WHERE repo = ?
        """.trimIndent(),
        repo.value,
    ) {
        TaskStats(
            tasks = it.getInt(0),
            bodyChars = it.getLong(1),
            anchors = it.getInt(2),
            tags = it.getInt(3),
            imageRefs = it.getInt(4),
            distinctImages = it.getInt(5),
            orphans = it.getInt(6),
        )
    } ?: TaskStats()

    /** Cuántas tareas hay en cada estado, en todos los repositorios. Cinco filas de `counter`. */
    fun countsByState(): Map<StateId, Int> = db.reader
        .rows("SELECT state, sum(n) FROM counter GROUP BY state") {
            StateId(it.getString(0).orEmpty()) to it.getInt(1)
        }
        .toMap()

    fun countsByPriority(): Map<PriorityId, Int> = db.reader
        .rows("SELECT priority, sum(n) FROM priority_counter GROUP BY priority") {
            PriorityId(it.getString(0).orEmpty()) to it.getInt(1)
        }
        .toMap()

    /** Cuántas siguen sin cerrar en un estado. Lo pregunta el ofrecimiento de rellenar `completedAt`. */
    fun openCountOf(state: StateId): Int =
        db.reader.count("SELECT coalesce(sum(n_open), 0) FROM counter WHERE state = ?", state.value)

    /** Los ids de blob que alguna tarea sigue nombrando. Es lo que el recolector respeta. */
    fun referencedBlobs(repo: RepoKey): Set<String> = db.reader
        .rows("SELECT DISTINCT blob_id FROM blob_ref WHERE repo = ?", repo.value) { it.getString(0).orEmpty() }
        .toSet()

    // --------------------------------------------------------------------- internos

    private class Fts(
        val seq: Long,
        val title: String,
        val body: String,
        val tags: String,
        val files: String,
        val state: String = "",
        val repo: String = "",
        val priority: String = "",
        val open: Boolean = false,
        val marked: Boolean = false,
    )

    private fun previous(id: TaskId): Fts? = sql.first(
        "SELECT seq, title, body, tags_text, files_text, state, repo, priority, completed_at, bookmarked " +
            "FROM task WHERE id = ?",
        id.value,
    ) {
        Fts(
            seq = it.getLong(0),
            title = it.getString(1).orEmpty(),
            body = it.getString(2).orEmpty(),
            tags = it.getString(3).orEmpty(),
            files = it.getString(4).orEmpty(),
            state = it.getString(5).orEmpty(),
            repo = it.getString(6).orEmpty(),
            priority = it.getString(7).orEmpty(),
            open = it.getLong(8) == TaskSchema.NO_DATE,
            marked = it.getInt(9) != 0,
        )
    }

    private fun ftsDelete(old: Fts) = sql.update(
        "INSERT INTO task_fts(task_fts, rowid, title, body, tags_text, files_text) " +
            "VALUES ('delete', ?, ?, ?, ?, ?)",
        old.seq,
        old.title,
        old.body,
        old.tags,
        old.files,
    )

    private fun ftsInsert(seq: Long, task: Task) = sql.update(
        "INSERT INTO task_fts(rowid, title, body, tags_text, files_text) VALUES (?, ?, ?, ?, ?)",
        seq,
        task.title,
        task.body,
        TaskRows.tagsText(task),
        TaskRows.filesText(task),
    )

    /** Pone al día la copia de la tupla de orden que vive en `tag`. Ver la nota 4 del esquema. */
    private fun syncTags(repo: RepoKey, from: StateId, to: StateId) = sql.update(
        """
        UPDATE tag
           SET state = ?,
               sort_date = (SELECT t.sort_date FROM task t WHERE t.id = tag.task_id),
               undated = (SELECT t.undated FROM task t WHERE t.id = tag.task_id),
               completed_at = (SELECT t.completed_at FROM task t WHERE t.id = tag.task_id)
         WHERE repo = ? AND state = ?
        """.trimIndent(),
        to.value,
        repo.value,
        from.value,
    )

    private class Counter(val n: Int, val open: Int, val marked: Int)

    private fun counterOf(repo: RepoKey, state: StateId): Counter? = sql.first(
        "SELECT n, n_open, n_marked FROM counter WHERE repo = ? AND state = ? AND n > 0",
        repo.value,
        state.value,
    ) { Counter(it.getInt(0), it.getInt(1), it.getInt(2)) }

    /**
     * `sort_date` y `undated` de golpe, para un anclaje dado.
     *
     * Las dos columnas van juntas siempre y por eso se escriben juntas: la fecha de
     * orden cae a `updated_at` cuando el anclaje no existe, y es `undated` quien
     * recuerda que no existía. Separarlas es exactamente el error que hacía que las
     * tareas sin completar de un estado terminal salieran ordenadas por identificador.
     *
     * [updated] es la expresión que vale `updated_at` después de la sentencia —el
     * instante nuevo en una reasignación, la propia columna cuando no se toca— y
     * [completed] lo mismo para `completed_at`.
     */
    private fun anchorSet(
        anchor: DateAnchor,
        updated: String = "updated_at",
        completed: String = "completed_at",
    ): String = when (anchor) {
        DateAnchor.CREATED -> "sort_date = created_at, undated = 0"
        DateAnchor.UPDATED -> "sort_date = $updated, undated = 0"
        DateAnchor.COMPLETED ->
            "sort_date = CASE WHEN ($completed) = ${TaskSchema.NO_DATE} THEN $updated ELSE ($completed) END, " +
                "undated = CASE WHEN ($completed) = ${TaskSchema.NO_DATE} THEN 1 ELSE 0 END"
    }

    /** `sort_date` después de tocar `updated_at`: sólo cambia en los estados anclados ahí. */
    private fun updatedAnchorExpr(config: TasklaneConfig, now: Long): String {
        val anchored = config.states.filter { it.anchor == DateAnchor.UPDATED }
        if (anchored.isEmpty()) return "sort_date"
        val list = anchored.joinToString(", ") { "'" + it.id.value.replace("'", "''") + "'" }
        return "CASE WHEN state IN ($list) THEN $now ELSE sort_date END"
    }

    // ---------------------------------------------------------------- contabilidad

    /**
     * Los contadores de esta transacción, en memoria hasta el final.
     *
     * Se acumulan en vez de escribirse fila a fila porque un solo comando puede tocar
     * varias tareas del mismo estado, y ahí son tres sentencias en vez de tres por
     * tarea. Y porque el sitio donde se aplican es el mismo `commit`: un contador que
     * sobreviviera a un `rollback` sería peor que no tener contador.
     */
    private class Deltas {
        private val states = HashMap<Pair<String, String>, IntArray>()
        private val priorities = HashMap<Pair<String, String>, Int>()
        private val dropped = HashSet<String>()

        @Suppress("UNUSED_PARAMETER")
        fun add(task: Task, config: TasklaneConfig) {
            bump(task.repo.value, task.stateId.value, 1, if (task.completedAt == null) 1 else 0, if (task.bookmarked) 1 else 0)
            // El id CRUDO, no el resuelto: `priority_counter` es también la enumeración
            // de qué prioridades hay en uso, y es justo la que ya no existe la que
            // `Renormalize` tiene que encontrar para aparcar sus tareas.
            priorities.merge(task.repo.value to task.priorityId.value, 1, Int::plus)
        }

        fun remove(old: Fts) {
            bump(old.repo, old.state, -1, if (old.open) -1 else 0, if (old.marked) -1 else 0)
            priorities.merge(old.repo to old.priority, -1, Int::plus)
        }

        fun move(repo: RepoKey, from: StateId, to: StateId, counter: Counter, toTerminal: Boolean) {
            bump(repo.value, from.value, -counter.n, -counter.open, -counter.marked)
            bump(repo.value, to.value, counter.n, if (toTerminal) 0 else counter.open, counter.marked)
        }

        fun movePriority(repo: RepoKey, from: PriorityId, to: PriorityId, n: Int) {
            priorities.merge(repo.value to from.value, -n, Int::plus)
            priorities.merge(repo.value to to.value, n, Int::plus)
        }

        fun forget(repo: RepoKey) {
            dropped += repo.value
        }

        private fun bump(repo: String, state: String, n: Int, open: Int, marked: Int) {
            val slot = states.getOrPut(repo to state) { IntArray(3) }
            slot[0] += n
            slot[1] += open
            slot[2] += marked
        }

        fun flush(sql: Sql) {
            for ((key, delta) in states) {
                if (key.first in dropped) continue
                if (delta[0] == 0 && delta[1] == 0 && delta[2] == 0) continue
                sql.update(
                    "INSERT INTO counter (repo, state, n, n_open, n_marked) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT(repo, state) DO UPDATE SET n = n + excluded.n, " +
                        "n_open = n_open + excluded.n_open, n_marked = n_marked + excluded.n_marked",
                    key.first,
                    key.second,
                    delta[0],
                    delta[1],
                    delta[2],
                )
            }
            for ((key, delta) in priorities) {
                if (key.first in dropped || delta == 0) continue
                sql.update(
                    "INSERT INTO priority_counter (repo, priority, n) VALUES (?, ?, ?) " +
                        "ON CONFLICT(repo, priority) DO UPDATE SET n = n + excluded.n",
                    key.first,
                    key.second,
                    delta,
                )
            }
            sql.update("DELETE FROM counter WHERE n <= 0")
            sql.update("DELETE FROM priority_counter WHERE n <= 0")
        }
    }

    companion object {
        /**
         * El hueco del orden manual. Disperso para poder insertar entre dos sin
         * renumerar la lista.
         */
        const val ORDER_GAP = 1000L

        /** Tope de tareas vencidas que se traen a memoria. Ver [overdue]. */
        const val OVERDUE_LIMIT = 5_000

        /** Lo que se le presta a SQLite mientras se importa, en KiB. Ver [bulk]. */
        const val BULK_CACHE_KB = 256_000

        /** Etiquetas y anclas de un puñado de tareas, con dos consultas en total. */
        fun hydrate(sql: Sql, tasks: List<Task>): List<Task> {
            if (tasks.isEmpty()) return tasks
            val ids = tasks.map { it.id.value }
            val tags = HashMap<String, MutableList<String>>()
            val anchors = HashMap<String, MutableList<com.tasklane.domain.model.CodeAnchor>>()

            for (chunk in ids.chunked(Sql.MAX_VARIABLES)) {
                val params = chunk.map { it as Any }.toTypedArray()
                val holes = Sql.placeholders(chunk.size)
                sql.rows("SELECT task_id, tag FROM tag WHERE task_id IN ($holes)", *params) {
                    tags.getOrPut(it.getString(0).orEmpty()) { ArrayList() } += it.getString(1).orEmpty()
                }
                sql.rows(
                    "SELECT task_id, path, line, col, text FROM anchor WHERE task_id IN ($holes) ORDER BY pos",
                    *params,
                ) {
                    anchors.getOrPut(it.getString(0).orEmpty()) { ArrayList() } += TaskRows.anchorOf(it)
                }
            }
            if (tags.isEmpty() && anchors.isEmpty()) return tasks
            return tasks.map { task ->
                val myTags = tags[task.id.value]
                val myAnchors = anchors[task.id.value]
                if (myTags == null && myAnchors == null) task
                else task.copy(tags = myTags.orEmpty(), anchors = myAnchors.orEmpty())
            }
        }
    }
}
