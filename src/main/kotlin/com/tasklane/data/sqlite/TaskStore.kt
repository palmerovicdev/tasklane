package com.tasklane.data.sqlite

import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.attachment.Chore
import com.tasklane.diagnostics.BlobStats
import com.tasklane.diagnostics.TaskStats
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.AnchoredTask
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.DueCount
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

    /**
     * ¿Le queda alguna tarea a este repositorio? Por la conexión de lectura, porque lo
     * pregunta el catálogo desde su propia corrutina y no tiene por qué esperar a que
     * termine una escritura.
     */
    fun hasTasks(repo: RepoKey): Boolean =
        db.reader.count("SELECT count(*) FROM counter WHERE repo = ? AND n > 0", repo.value) > 0

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

    /**
     * Aplica las mutaciones en **una** transacción.
     *
     * [progress] recibe cuántas filas van escritas de cuántas, y es para las operaciones
     * masivas de la Fase 5: una selección de miles de filas es una transacción de
     * segundos, y la barra tiene que moverse **dentro** de ella. Si lanza —cancelar—, la
     * transacción se deshace entera, que es lo que un «cancelar» significa: no queda media
     * selección movida.
     */
    fun apply(
        config: TasklaneConfig,
        mutations: List<Mutation>,
        progress: (done: Int, total: Int) -> Unit = NO_PROGRESS,
    ): Applied {
        if (mutations.isEmpty() || db.readOnly) return Applied(0)
        val total = mutations.sumOf {
            when (it) {
                is Mutation.Upsert -> it.tasks.size
                is Mutation.Delete -> it.ids.size
                else -> 0
            }
        }
        return sql.transaction {
            val deltas = Deltas()
            var rows = 0
            var done = 0
            var parked = Parked.NONE
            for (mutation in mutations) {
                when (mutation) {
                    is Mutation.Upsert -> {
                        upsertAll(mutation.tasks, config, deltas) {
                            if (++done % PROGRESS_EVERY == 0) progress(done, total)
                        }
                        rows += mutation.tasks.size
                    }

                    is Mutation.Delete -> {
                        rows += delete(mutation.ids, deltas) { if (++done % PROGRESS_EVERY == 0) progress(done, total) }
                    }

                    is Mutation.Reassign -> rows += reassign(config, mutation, deltas)
                    is Mutation.Reprioritize -> rows += reprioritize(config, mutation, deltas)
                    is Mutation.Backfill -> rows += backfill(config, mutation.states)
                    is Mutation.Forget -> rows += forget(mutation.repo, deltas)
                    is Mutation.Place -> rows += place(mutation)
                    is Mutation.SeedOrder -> rows += seedOrder(mutation.state)
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

    private fun upsert(task: Task, config: TasklaneConfig, deltas: Deltas) = upsertAll(listOf(task), config, deltas)

    /**
     * Escribe muchas tareas **con una sentencia preparada por tipo de fila**, no con
     * treinta por tarea.
     *
     * Es la escritura de las operaciones masivas de la Fase 5, y la de cualquier comando
     * suelto. Medido sobre el corpus de la especificación, escribir una tarea tarea a
     * tarea eran ~240 µs, y **el 90 % no era el índice de texto**: eran las sentencias que
     * se preparaban y se cerraban por fila —la tarea, sus ocho etiquetas, sus cinco anclas,
     * sus diez referencias a imágenes y los tres borrados de delante—. Es exactamente lo que
     * la migración ya resolvía con [Sql.batch], y aquí se hace igual: una lectura de lo que
     * había por tanda, y un lote por tabla.
     *
     * **El índice de texto sólo se toca si el texto cambió.** Mover, completar, marcar o
     * cambiar la prioridad no cambian ni el título, ni el cuerpo, ni las etiquetas, ni las
     * rutas —las cuatro columnas de `task_fts`—, y hasta ahora se borraban y se volvían a
     * tokenizar igual.
     *
     * Si la misma tarea viene dos veces **gana la última**, que es lo que pasaba
     * escribiéndolas una detrás de otra; sin eso, una tarea nueva repetida recibiría dos
     * `seq` y el índice de texto quedaría apuntando a una fila que no existe.
     */
    private fun upsertAll(input: List<Task>, config: TasklaneConfig, deltas: Deltas, onRow: () -> Unit = {}) {
        if (input.isEmpty()) return
        val tasks = if (input.size == 1) input else input.associateByTo(LinkedHashMap()) { it.id }.values.toList()

        for (chunk in tasks.chunked(Sql.MAX_VARIABLES)) {
            val ids = chunk.map { it.id.value as Any }.toTypedArray()
            val holes = Sql.placeholders(chunk.size)
            val olds = HashMap<String, Fts>(chunk.size * 2)
            sql.rows("SELECT $FTS_COLUMNS, id FROM task WHERE id IN ($holes)", *ids) {
                olds[it.getString(10).orEmpty()] = ftsRow(it)
            }

            val seqs = LongArray(chunk.size)
            val ftsGone = ArrayList<Array<Any>>()
            val ftsNew = ArrayList<Array<Any>>()
            chunk.forEachIndexed { i, task ->
                val old = olds[task.id.value]
                val tags = TaskRows.tagsText(task)
                val files = TaskRows.filesText(task)
                seqs[i] = old?.seq ?: nextSeq.getAndIncrement()
                if (old != null) deltas.remove(old)
                val sameText = old != null && old.title == task.title && old.body == task.body &&
                    old.tags == tags && old.files == files
                if (!sameText) {
                    if (old != null) ftsGone += arrayOf(old.seq, old.title, old.body, old.tags, old.files)
                    ftsNew += arrayOf(seqs[i], task.title, task.body, tags, files)
                }
            }

            if (ftsGone.isNotEmpty()) sql.batch(FTS_DELETE, 5, ftsGone.asSequence())
            sql.batch(
                TaskRows.UPSERT.trimIndent(),
                TaskRows.INSERT_PARAMS,
                chunk.asSequence().mapIndexed { i, task -> TaskRows.values(seqs[i], task, config) },
            )
            if (ftsNew.isNotEmpty()) sql.batch(FTS_INSERT, 5, ftsNew.asSequence())

            // Las filas hijas se rehacen enteras: tres borrados por tanda y un lote por tabla.
            sql.update("DELETE FROM tag WHERE task_id IN ($holes)", *ids)
            sql.update("DELETE FROM anchor WHERE task_id IN ($holes)", *ids)
            sql.update("DELETE FROM blob_ref WHERE task_id IN ($holes)", *ids)
            insertSides(chunk, config)

            for (task in chunk) {
                deltas.add(task, config)
                onRow()
            }
        }
    }

    /**
     * Las filas de `tag`, `anchor` y `blob_ref` de unas tareas **que ya no tienen
     * ninguna**, en tres lotes. La comparten la escritura normal y la migración: son las
     * mismas filas, y dos copias de cómo se escriben divergirían.
     */
    private fun insertSides(tasks: List<Task>, config: TasklaneConfig) {
        val tags = tasks.flatMap { task ->
            if (task.tags.isEmpty()) return@flatMap emptyList()
            val rank = TaskRows.rankOf(task, config)
            val sortDate = TaskRows.sortDate(task, config)
            val undated = if (TaskRows.undated(task, config)) 1 else 0
            task.tags.map { tag ->
                arrayOf<Any>(
                    task.id.value,
                    tag,
                    task.repo.value,
                    task.stateId.value,
                    if (task.bookmarked) 1 else 0,
                    rank,
                    sortDate,
                    undated,
                    TaskRows.millis(task.completedAt),
                    TaskRows.millis(task.dueDate),
                )
            }
        }
        if (tags.isNotEmpty()) sql.batch(TAG_INSERT, 10, tags.asSequence())

        val anchors = tasks.flatMap { task ->
            task.anchors.mapIndexed { pos, anchor ->
                arrayOf<Any>(task.id.value, pos, anchor.path, anchor.line, anchor.column, anchor.text)
            }
        }
        if (anchors.isNotEmpty()) sql.batch(ANCHOR_INSERT, 6, anchors.asSequence())

        val refs = tasks.flatMap { task ->
            task.attachments.map { it.id.value }.distinct().map { blob -> arrayOf<Any>(task.id.value, blob, task.repo.value) }
        }
        if (refs.isNotEmpty()) sql.batch(BLOB_REF_INSERT, 3, refs.asSequence())
    }

    private fun delete(ids: List<TaskId>, deltas: Deltas, onRow: () -> Unit = {}): Int {
        var removed = 0
        for (id in ids) {
            onRow()
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

    // ------------------------------------------------------------- orden manual (2.11.0)

    /**
     * Le da a una tarea el `ord` que la deja entre sus dos vecinas. Ver [Mutation.Place].
     *
     * Sólo cuentan las vecinas del mismo estado y del mismo lado de la marca: lo marcado
     * va siempre arriba, así que soltar una tarea sin marcar entre dos marcadas es
     * soltarla al principio de las suyas. La que falta se busca en la base, porque lo que
     * hay debajo de la última fila cargada puede estar en una página que nadie ha pedido.
     *
     * Si no queda hueco —dos vecinas con `ord` seguidos, tras muchas vueltas al mismo
     * sitio— se reparte el estado otra vez con [TaskStore.ORDER_GAP] de separación, y se
     * vuelve a intentar. Pasa muy de tarde en tarde: cada hueco nuevo aguanta diez
     * bisecciones.
     */
    private fun place(move: Mutation.Place): Int {
        val repo = move.repo.value
        val me = sql.first(
            "SELECT state, bookmarked FROM task WHERE id = ? AND repo = ?",
            move.id.value,
            repo,
        ) { it.getString(0).orEmpty() to it.getInt(1) } ?: return 0
        val (state, marked) = me

        fun ordOf(id: TaskId?): Long? = id?.takeIf { it != move.id }?.let {
            sql.first(
                "SELECT ord FROM task WHERE id = ? AND repo = ? AND state = ? AND bookmarked = ?",
                it.value,
                repo,
                state,
                marked,
            ) { row -> row.getLong(0) }
        }

        fun neighbour(sqlText: String, from: Long): Long? = sql.first(
            sqlText,
            repo,
            state,
            marked,
            from,
            move.id.value,
        ) { it.getLong(0) }.takeIf { it != Long.MIN_VALUE }

        repeat(2) {
            var hi = ordOf(move.above)
            var lo = ordOf(move.below)
            if (hi == null && lo == null) return 0
            if (lo == null) {
                lo = neighbour(
                    "SELECT coalesce(max(ord), ${Long.MIN_VALUE}) FROM task " +
                        "WHERE repo = ? AND state = ? AND bookmarked = ? AND ord < ? AND id <> ?",
                    hi!!,
                ) ?: (hi - 2 * ORDER_GAP)
            }
            if (hi == null) {
                hi = neighbour(
                    "SELECT coalesce(min(ord), ${Long.MIN_VALUE}) FROM task " +
                        "WHERE repo = ? AND state = ? AND bookmarked = ? AND ord > ? AND id <> ?",
                    lo,
                ) ?: (lo + 2 * ORDER_GAP)
            }
            val top = maxOf(hi, lo)
            val bottom = minOf(hi, lo)
            if (top - bottom >= 2) {
                sql.update("UPDATE task SET ord = ? WHERE id = ?", bottom + (top - bottom) / 2, move.id.value)
                return 1
            }
            respace(repo, state)
        }
        return 0
    }

    /**
     * Vuelve a espaciar el orden manual de un estado de un repositorio: las mismas
     * posiciones, a [ORDER_GAP] unas de otras. Empieza en el `ord` más bajo que ya hubiera,
     * para no mover el estado respecto a lo recién creado, que se crea por encima de todo.
     */
    private fun respace(repo: String, state: String) {
        sql.update(
            "WITH ranked AS MATERIALIZED (" +
                "SELECT id, ROW_NUMBER() OVER (ORDER BY ord ASC, id ASC) AS rn FROM task WHERE repo = ? AND state = ?), " +
                "base AS MATERIALIZED (SELECT coalesce(min(ord), 0) AS m FROM task WHERE repo = ? AND state = ?) " +
                "UPDATE task SET ord = base.m + (ranked.rn - 1) * $ORDER_GAP FROM ranked, base WHERE task.id = ranked.id",
            repo,
            state,
            repo,
            state,
        )
    }

    /**
     * El orden manual de [state] sembrado con el que se estaba viendo. Ver
     * [Mutation.SeedOrder]. En todos los repositorios —el modo es del estado, y el estado
     * es del proyecto— y por encima del `ord` más alto de cada uno, así que no toca el
     * orden de ningún otro estado.
     */
    private fun seedOrder(state: StateId): Int {
        val n = sql.count("SELECT count(*) FROM task WHERE state = ?", state.value)
        if (n == 0) return 0
        sql.update(
            "WITH ranked AS MATERIALIZED (" +
                "SELECT id, repo, ROW_NUMBER() OVER (PARTITION BY repo ORDER BY ${TaskSchema.ORDER_BY_ASC}) AS rn " +
                "FROM task WHERE state = ?), " +
                "tops AS MATERIALIZED (SELECT repo, max(ord) AS m FROM task GROUP BY repo) " +
                "UPDATE task SET ord = tops.m + ranked.rn * $ORDER_GAP " +
                "FROM ranked JOIN tops ON tops.repo = ranked.repo WHERE task.id = ranked.id",
            state.value,
        )
        return n
    }

    private fun forget(repo: RepoKey, deltas: Deltas): Int {
        val total = countOf(repo)
        while (true) {
            val batch = forgetRows(repo, FORGET_BATCH, deltas)
            if (batch == 0) break
        }
        sql.update("DELETE FROM counter WHERE repo = ?", repo.value)
        sql.update("DELETE FROM priority_counter WHERE repo = ?", repo.value)
        sql.update("DELETE FROM imported WHERE repo = ?", repo.value)
        deltas.forget(repo)
        return total
    }

    /**
     * Borra **una tanda** de las tareas de un repositorio, en su propia transacción, y
     * devuelve cuántas.
     *
     * Es la mitad destructiva de «Exportar y quitar» a escala (Fase 5). [Mutation.Forget]
     * lo hace todo en **una** transacción, y con un millón de tareas eso es tener el
     * escritor tomado durante minutos: cualquier otro comando —marcar una tarea de otro
     * repositorio desde el EDT— esperaría a que acabase. Por tandas, lo más que espera
     * alguien es una tanda.
     *
     * **Cada tanda deja los contadores exactos**, que es la regla del almacén: si el IDE
     * se cae a mitad, lo que queda es un repositorio con menos tareas y unas pestañas que
     * lo cuentan bien, no uno con contadores que no cuadran con nada.
     */
    fun forgetBatch(repo: RepoKey, limit: Int = FORGET_BATCH): Int {
        if (db.readOnly) return 0
        return sql.transaction {
            val deltas = Deltas()
            val removed = forgetRows(repo, limit, deltas)
            deltas.flush(sql)
            removed
        }
    }

    /**
     * Las filas de `blob` de un repositorio, a tandas. Lo que queda después de quitar sus
     * tareas: sin ellas nadie las nombra, y el directorio se va a borrar entero.
     */
    fun forgetBlobRows(repo: RepoKey, limit: Int = FORGET_BATCH): Int {
        if (db.readOnly) return 0
        return sql.transaction {
            val before = sql.count("SELECT total_changes()")
            sql.update(
                "DELETE FROM blob WHERE rowid IN (SELECT rowid FROM blob WHERE repo = ? LIMIT ?)",
                repo.value,
                limit,
            )
            sql.count("SELECT total_changes()") - before
        }
    }

    /**
     * El índice de texto no cascadea: sus filas se borran a mano, con el contenido que la
     * tabla virtual necesita para localizarlas. Y los contadores se descuentan fila a fila
     * porque la tanda puede cruzar estados.
     */
    private fun forgetRows(repo: RepoKey, limit: Int, deltas: Deltas): Int {
        val batch = sql.rows(
            "SELECT $FTS_COLUMNS FROM task WHERE repo = ? LIMIT ?",
            repo.value,
            limit,
            read = ::ftsRow,
        )
        if (batch.isEmpty()) return 0
        for (row in batch) {
            ftsDelete(row)
            deltas.remove(row)
        }
        for (chunk in batch.chunked(Sql.MAX_VARIABLES)) {
            sql.update(
                "DELETE FROM task WHERE seq IN (${Sql.placeholders(chunk.size)})",
                *chunk.map { it.seq as Any }.toTypedArray(),
            )
        }
        return batch.size
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
     *
     * **[checkpointPages] es de la Fase 5**, y es para trabajo de **muchas transacciones
     * pequeñas seguidas** —quitar un repositorio por tandas—. SQLite vuelca el diario a la
     * base cada mil páginas; con una confirmación cada quinientas tareas eso es un volcado
     * por tanda, copiando una y otra vez las mismas páginas interiores de los índices.
     * Medido sobre 100.000 tareas: quitarlas por tandas eran 33,9 s; con la caché grande
     * sola, 32,4 s; y espaciando el volcado a veinte mil páginas, **20,7 s**, con la tanda
     * típica de 172 ms a 80. El diario crece hasta ~80 MB entre volcados, y en cuanto
     * termina el bloque vuelve a su ritmo de siempre.
     */
    fun <T> bulk(checkpointPages: Int = 0, block: () -> T): T {
        sql.execute("PRAGMA cache_size = -$BULK_CACHE_KB")
        if (checkpointPages > 0) sql.execute("PRAGMA wal_autocheckpoint = $checkpointPages")
        return try {
            block()
        } finally {
            runCatching { sql.execute(TaskSchema.PRAGMAS.first { it.contains("cache_size") }) }
            if (checkpointPages > 0) runCatching { sql.execute("PRAGMA wal_autocheckpoint = $DEFAULT_CHECKPOINT_PAGES") }
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
                FTS_INSERT,
                5,
                tasks.asSequence().mapIndexed { i, task ->
                    arrayOf<Any>(seqs[i], task.title, task.body, TaskRows.tagsText(task), TaskRows.filesText(task))
                },
            )
            insertSides(tasks, config)
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
     * Cuántas tareas de [repo] están vencidas en [now], y cuándo vence la siguiente que
     * todavía no lo está. Lo pide el widget de la barra de estado (2.14.0): lo primero
     * es lo que enseña, lo segundo cuándo tiene que volver a preguntar —una tarea vence
     * sin que nadie escriba nada—.
     *
     * Una sola pasada por el índice parcial `task_due`, que sólo tiene lo abierto con
     * fecha: con él, la pregunta cuesta lo que hay por vencer y no lo que hay en el
     * repositorio. La condición va escrita igual que la del índice por eso mismo.
     */
    fun dueCount(repo: RepoKey, now: Instant): DueCount {
        val at = now.toEpochMilli()
        return db.reader.first(
            "SELECT coalesce(sum(due_date < ?), 0), coalesce(min(CASE WHEN due_date >= ? THEN due_date END), " +
                "${TaskSchema.NO_DATE}) FROM task WHERE repo = ? AND completed_at = ${TaskSchema.NO_DATE} " +
                "AND due_date <> ${TaskSchema.NO_DATE}",
            at,
            at,
            repo.value,
        ) {
            val next = it.getLong(1)
            DueCount(it.getInt(0), if (next == TaskSchema.NO_DATE) null else Instant.ofEpochMilli(next))
        } ?: DueCount(0, null)
    }

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

    /**
     * Las tareas que apuntan a alguna de [paths] **o a algo que cuelga de ellas**, con su
     * repositorio (2.13.0). Es lo que pregunta renombrar un fichero o un directorio: qué
     * anclas tienen que irse con él.
     *
     * Un salto por `anchor_by_path` por ruta: la exacta y el rango `ruta/ … ruta0`, que
     * es «empieza por `ruta/`» dicho de forma que el índice lo entienda —`0` es el
     * carácter que sigue a `/`—. Un `LIKE` no lo usaría, y además trataría `_` y `%` del
     * nombre del fichero como comodines.
     */
    fun anchoredUnder(paths: Collection<String>): List<Pair<TaskId, RepoKey>> =
        underPaths(
            paths,
            "SELECT DISTINCT a.task_id, t.repo FROM anchor a JOIN task t ON t.id = a.task_id WHERE",
            "a.path",
        ) { TaskId(it.getString(0).orEmpty()) to RepoKey(it.getString(1).orEmpty()) }

    /**
     * Las rutas ancladas distintas, todas o sólo las que caen en [under] (2.13.0). Lo que
     * `AnchorFiles` comprueba en el disco para saber qué anclas están rotas: entero al
     * abrir el proyecto, y bajo lo que se borró o se creó cuando llega un evento.
     *
     * Rutas y no anclas: diez tareas sobre el mismo fichero son una sola pregunta al
     * disco. El `DISTINCT` lo resuelve el propio índice, que ya está ordenado por ruta.
     */
    fun anchorPaths(under: Collection<String>? = null): List<String> =
        if (under == null) db.reader.rows("SELECT DISTINCT path FROM anchor") { it.getString(0).orEmpty() }
        else underPaths(under, "SELECT DISTINCT path FROM anchor WHERE", "path") { it.getString(0).orEmpty() }

    private fun <T> underPaths(paths: Collection<String>, select: String, column: String, read: (Sql.Row) -> T): List<T> {
        val out = LinkedHashSet<T>()
        // Tres variables por ruta: la exacta y los dos extremos del rango.
        for (chunk in paths.distinct().chunked(Sql.MAX_VARIABLES / 3)) {
            val where = chunk.joinToString(" OR ") { "$column = ? OR ($column >= ? AND $column < ?)" }
            val params = chunk.flatMap { listOf(it, "$it/", "${it}0") }.toTypedArray<Any>()
            out += db.reader.rows("$select ($where)", *params, read = read)
        }
        return out.toList()
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

    /**
     * Cuáles **de éstos** sigue nombrando alguna tarea.
     *
     * Por tandas y no «todos los del repositorio», que es como lo preguntaba la Fase 3:
     * con diez millones de blobs referenciados, el conjunto entero son cientos de
     * megabytes de cadenas en memoria — exactamente el O(n) que esta fase vino a quitar,
     * reintroducido en el sitio más delicado de todos. Lo que el recolector necesita
     * saber es si **estos mil candidatos** están libres, y eso es un salto por
     * `blob_ref_by_repo`.
     */
    fun referencedAmong(repo: RepoKey, ids: List<AttachmentId>): Set<String> {
        val out = HashSet<String>()
        for (chunk in ids.chunked(Sql.MAX_VARIABLES)) {
            db.reader.rows(
                "SELECT DISTINCT blob_id FROM blob_ref WHERE repo = ? AND blob_id IN " +
                    "(${Sql.placeholders(chunk.size)})",
                repo.value,
                *chunk.map { it.value as Any }.toTypedArray(),
            ) { out += it.getString(0).orEmpty() }
        }
        return out
    }

    // ------------------------------------------------------- adjuntos (Fase 4, §4.2)

    /**
     * Apunta un blob recién escrito, o vuelve a dar por presente uno que ya estaba.
     *
     * **`created_at` no se toca al reescribir**, y es lo único delicado de esta
     * sentencia: es la fecha que abre el periodo de gracia del recolector, y ponerla al
     * día en cada paso del reconciliador convertiría la gracia de 24 horas en «24 horas
     * desde el último escaneo», o sea en nunca. Lo que sí se pone al día es `seen_at`
     * —acabamos de verlo— y `missing`, porque un blob que vuelve deja de faltar.
     */
    fun recordBlob(repo: RepoKey, blob: BlobRecord, at: Long) = sql.update(
        "INSERT INTO blob (repo, id, bytes, width, height, created_at, seen_at, missing) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 0) " +
            "ON CONFLICT(repo, id) DO UPDATE SET bytes = excluded.bytes, width = excluded.width, " +
            "height = excluded.height, seen_at = excluded.seen_at, missing = 0",
        repo.value,
        blob.id.value,
        blob.bytes,
        blob.width,
        blob.height,
        blob.createdAt.toEpochMilli(),
        at,
    )

    /**
     * Los candidatos a recoger: los que nadie nombra y llevan más de la gracia escritos.
     *
     * Es la consulta del §4.2 del plan, y la diferencia con lo que había no es de
     * velocidad sino de **orden de complejidad**: antes el recolector pedía «todo lo que
     * hay en el directorio» —un `Files.list` que con diez millones de entradas no
     * termina— y descartaba en memoria. Esto entra por `blob_by_age`, se planta en
     * [limit] filas y deja el resto para la tanda siguiente.
     *
     * Lo que falta (`missing = 1`) **sí sale**: su fichero ya no está, pero su fila
     * ocupa sitio y miente sobre lo que hay guardado. Borrarla es exactamente lo que
     * hace falta, y borrar un fichero que no existe no le pasa nada a nadie.
     */
    fun collectibleBlobs(repo: RepoKey, before: Long, limit: Int): List<BlobRecord> = db.reader.rows(
        """
        SELECT id, bytes, width, height, created_at, missing
          FROM blob
         WHERE repo = ? AND created_at < ?
           AND NOT EXISTS (SELECT 1 FROM blob_ref r WHERE r.repo = blob.repo AND r.blob_id = blob.id)
         ORDER BY created_at
         LIMIT ?
        """.trimIndent(),
        repo.value,
        before,
        limit,
        read = ::blobRow,
    )

    /** Quita las filas de lo que el recolector ya borró del disco. */
    fun forgetBlobs(repo: RepoKey, ids: List<AttachmentId>) {
        for (chunk in ids.chunked(Sql.MAX_VARIABLES)) {
            sql.update(
                "DELETE FROM blob WHERE repo = ? AND id IN (${Sql.placeholders(chunk.size)})",
                repo.value,
                *chunk.map { it.value as Any }.toTypedArray(),
            )
        }
    }

    // --------------------------------------------------- reconciliación (§4.3)

    /** Cuáles de estos ids ya conoce la tabla. Lo pregunta el reconciliador por tandas. */
    fun knownBlobs(repo: RepoKey, ids: List<AttachmentId>): Set<String> {
        val out = HashSet<String>(ids.size)
        for (chunk in ids.chunked(Sql.MAX_VARIABLES)) {
            db.reader.rows(
                "SELECT id FROM blob WHERE repo = ? AND id IN (${Sql.placeholders(chunk.size)})",
                repo.value,
                *chunk.map { it.value as Any }.toTypedArray(),
            ) { out += it.getString(0).orEmpty() }
        }
        return out
    }

    /**
     * Sella como vistos los blobs que el recorrido acaba de encontrar.
     *
     * Una sentencia preparada y un lote, que es donde [Sql.batch] gana de verdad: un
     * recorrido de diez millones son diez mil tandas, y preparar una sentencia por
     * fichero multiplicaría por cien lo que cuesta la reconciliación.
     */
    fun seeBlobs(repo: RepoKey, ids: List<AttachmentId>, at: Long) = sql.batch(
        "UPDATE blob SET seen_at = ?, missing = 0 WHERE repo = ? AND id = ?",
        3,
        ids.asSequence().map { arrayOf<Any>(at, repo.value, it.value) },
    )

    /**
     * Adopta los blobs que están en disco y la tabla no conocía.
     *
     * **Con la fecha del fichero y no con la de ahora**, que es lo que dice el §4.3: un
     * blob adoptado entra en el periodo de gracia como si siempre hubiera estado
     * apuntado, en vez de ganar 24 horas de indulto por haber sido descubierto. Si su
     * fichero es de hace un año y no lo nombra nadie, la recolección siguiente se lo
     * lleva — que es justo lo que se quiere de lo que quedó suelto.
     */
    fun adoptBlobs(repo: RepoKey, blobs: List<BlobRecord>, at: Long) = sql.batch(
        "INSERT INTO blob (repo, id, bytes, width, height, created_at, seen_at, missing) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 0) ON CONFLICT(repo, id) DO NOTHING",
        7,
        blobs.asSequence().map {
            arrayOf<Any>(repo.value, it.id.value, it.bytes, it.width, it.height, it.createdAt.toEpochMilli(), at)
        },
    )

    /**
     * Marca como ausentes las filas que el recorrido **no** volvió a ver.
     *
     * Es la otra dirección de la deriva: el fichero se borró por fuera del plugin —un
     * `rm`, un `.idea` restaurado a medias, un disco que se llenó— y la tarjeta tiene
     * que poder decirlo. `missing` es exactamente lo que la lista ya sabe pintar.
     *
     * Recorre las filas del repositorio, sin índice por `seen_at`: es una pasada por la
     * tabla al final de un recorrido que ya visitó **el árbol entero de ficheros**, y un
     * índice más costaría disco en cada escritura para ahorrar en algo que pasa una vez
     * por semana y en segundo plano.
     */
    fun markMissingBlobs(repo: RepoKey, before: Long): Int {
        val n = db.reader.count(
            "SELECT count(*) FROM blob WHERE repo = ? AND seen_at < ? AND missing = 0",
            repo.value,
            before,
        )
        if (n > 0) {
            sql.update("UPDATE blob SET missing = 1 WHERE repo = ? AND seen_at < ? AND missing = 0", repo.value, before)
        }
        return n
    }

    // -------------------------------------------------------- cuentas y tareas de fondo

    /**
     * El peso de los adjuntos de un repositorio, en agregados y sin tocar el disco.
     *
     * Es la cifra que los ajustes enseñan siempre desde la 2.3. Los bytes son **los de lo
     * que está en disco**: una fila cuyo fichero ya no está —la marca la reconciliación—
     * cuenta como ausente y no pesa, porque «cuánto ocupan mis imágenes» es una pregunta
     * sobre el disco.
     *
     * Recorre las filas del repositorio, sin índice que lo cubra: lo piden los ajustes al
     * abrirse y el informe, fuera del EDT, y un contador mantenido en cada escritura
     * costaría en todas ellas para ahorrar en algo que pasa a mano.
     */
    fun blobStatsOf(repo: RepoKey): BlobStats = db.reader.first(
        """
        SELECT count(*), coalesce(sum(CASE WHEN missing = 0 THEN bytes ELSE 0 END), 0), coalesce(sum(missing), 0)
          FROM blob WHERE repo = ?
        """.trimIndent(),
        repo.value,
    ) {
        BlobStats(
            count = it.getInt(0),
            bytes = it.getLong(1),
            missing = it.getInt(2),
        )
    } ?: BlobStats()

    /** Lo que ocupan los adjuntos del **proyecto entero**: la cifra que vigila la cuota. */
    fun blobBytes(): Long =
        db.reader.first("SELECT coalesce(sum(bytes), 0) FROM blob WHERE missing = 0") { it.getLong(0) } ?: 0L

    /** Cuándo se hizo por última vez una tarea de mantenimiento, y con qué número. Ver `chore`. */
    fun chore(name: String): Chore? = db.reader.first(
        "SELECT at, n FROM chore WHERE name = ?",
        name,
    ) { Chore(it.getLong(0), it.getLong(1)) }

    /** Olvida una marca de mantenimiento: la tarea vuelve a estar pendiente. */
    fun forgetChore(name: String) = sql.update("DELETE FROM chore WHERE name = ?", name)

    fun saveChore(name: String, at: Long, n: Long) = sql.update(
        "INSERT INTO chore (name, at, n) VALUES (?, ?, ?) " +
            "ON CONFLICT(name) DO UPDATE SET at = excluded.at, n = excluded.n",
        name,
        at,
        n,
    )

    private fun blobRow(row: Sql.Row) = BlobRecord(
        id = AttachmentId(row.getString(0).orEmpty()),
        bytes = row.getLong(1),
        width = row.getInt(2),
        height = row.getInt(3),
        createdAt = Instant.ofEpochMilli(row.getLong(4)),
        missing = row.getInt(5) != 0,
    )

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

    private fun previous(id: TaskId): Fts? = sql.first("SELECT $FTS_COLUMNS FROM task WHERE id = ?", id.value, read = ::ftsRow)

    /** Lo que hace falta de una fila para quitarla del índice de texto y de los contadores. */
    private fun ftsRow(row: Sql.Row) = Fts(
        seq = row.getLong(0),
        title = row.getString(1).orEmpty(),
        body = row.getString(2).orEmpty(),
        tags = row.getString(3).orEmpty(),
        files = row.getString(4).orEmpty(),
        state = row.getString(5).orEmpty(),
        repo = row.getString(6).orEmpty(),
        priority = row.getString(7).orEmpty(),
        open = row.getLong(8) == TaskSchema.NO_DATE,
        marked = row.getInt(9) != 0,
    )

    private fun ftsDelete(old: Fts) = sql.update(FTS_DELETE, old.seq, old.title, old.body, old.tags, old.files)

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

        /** Lo que [ftsRow] lee, en su orden: lo de una fila que el índice de texto y los contadores necesitan. */
        private const val FTS_COLUMNS =
            "seq, title, body, tags_text, files_text, state, repo, priority, completed_at, bookmarked"

        /** Quitar una fila del índice de texto: con contenido externo, hay que decirle qué había. */
        private const val FTS_DELETE =
            "INSERT INTO task_fts(task_fts, rowid, title, body, tags_text, files_text) VALUES ('delete', ?, ?, ?, ?, ?)"

        private const val FTS_INSERT = "INSERT INTO task_fts(rowid, title, body, tags_text, files_text) VALUES (?, ?, ?, ?, ?)"

        private const val TAG_INSERT = "INSERT INTO tag (task_id, tag, repo, state, bookmarked, priority_rank, " +
            "sort_date, undated, completed_at, due_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        private const val ANCHOR_INSERT = "INSERT INTO anchor (task_id, pos, path, line, col, text) VALUES (?, ?, ?, ?, ?, ?)"

        private const val BLOB_REF_INSERT = "INSERT INTO blob_ref (task_id, blob_id, repo) VALUES (?, ?, ?)"

        /** El volcado del diario mientras se quita un repositorio. Ver [bulk]. */
        const val REMOVAL_CHECKPOINT_PAGES = 20_000

        /** El de SQLite, al que se vuelve. */
        private const val DEFAULT_CHECKPOINT_PAGES = 1_000

        /**
         * Tareas por transacción al quitar un repositorio. Ver [forgetBatch].
         *
         * Es lo que decide cuánto puede llegar a esperar cualquier otro comando mientras
         * se borra, y se midió: sobre 10.000 tareas, con dos mil la tanda más larga eran
         * 170 ms y con quinientas 87 ms. Sobre 100.000 cada tanda cuesta más —la base ya no
         * cabe en la caché— y con el volcado espaciado de [bulk] la típica son 80 ms. El
         * precio de tantas confirmaciones se paga en el total, en segundo plano y una vez.
         */
        const val FORGET_BATCH = 500

        /** Cada cuántas filas se avisa del progreso de una transacción. Ver [apply]. */
        const val PROGRESS_EVERY = 64

        private val NO_PROGRESS: (Int, Int) -> Unit = { _, _ -> }

        /**
         * Todo un repositorio en el orden del `tasks.xml` —el orden manual—, **a tandas**.
         *
         * Lo pide la exportación a XML. Hasta la Fase 5 era `allOf`, que devolvía la lista
         * entera: un millón de `Task` vivas —12,9 GB— antes de escribir el primer byte.
         * Ahora es *keyset* sobre `task_ord` —`(ord, seq)`, que el índice ya lleva en ese
         * orden porque el rowid va detrás de toda clave— y lo retenido es la tanda.
         *
         * Recibe la conexión porque quien exporta la abre propia y con una transacción:
         * ver `SqlitePager.snapshot`.
         */
        fun eachInOrder(sql: Sql, repo: RepoKey, chunk: Int, block: (List<Task>) -> Unit) {
            var ord = Long.MIN_VALUE
            var seq = Long.MIN_VALUE
            while (true) {
                var lastOrd = ord
                var lastSeq = seq
                val rows = sql.rows(
                    "SELECT ${TaskRows.COLUMNS} FROM task WHERE repo = ? AND (ord, seq) > (?, ?) " +
                        "ORDER BY ord, seq LIMIT ?",
                    repo.value,
                    ord,
                    seq,
                    chunk,
                ) {
                    lastOrd = it.getLong(6)
                    lastSeq = TaskRows.seqOf(it)
                    TaskRows.read(it)
                }
                if (rows.isEmpty()) break
                block(hydrate(sql, rows))
                if (rows.size < chunk) break
                ord = lastOrd
                seq = lastSeq
            }
        }

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
