package com.tasklane.data.sqlite

import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.DateGrouper
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.paging.Before
import com.tasklane.paging.Cursor
import com.tasklane.paging.GroupOutline
import com.tasklane.paging.PageQuery
import com.tasklane.paging.Reveal
import com.tasklane.paging.TaskPage
import com.tasklane.paging.TaskPager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * El [TaskPager] de la Fase 3: la lista sale de un índice y un `LIMIT`.
 *
 * Sustituye a `MemoryPager` sin que la UI se entere —ésa era la promesa de la costura
 * de la Fase 2— y borra de un plumazo los 740 ms que costaba ordenar y agrupar un
 * millón de tareas: no se ordena nada, porque el índice ya está ordenado.
 *
 * ## La mezcla de cubos, que es lo que no es obvio
 *
 * El orden de la lista es `bookmarked DESC, priority_rank DESC, fecha DESC`. Un grupo
 * de fecha es un **rango** de esa tercera columna. Pedirle a SQLite las dos cosas a la
 * vez —rango en la tercera columna, orden por las tres— le obliga a recorrer el estado
 * entero: un índice ordena por prefijo, y la columna del rango no es el prefijo.
 *
 * La salida es que las dos primeras columnas tienen **cardinalidad diminuta**: marcada
 * o no, por el puñado de prioridades configuradas. Ocho combinaciones, y dentro de cada
 * una el rango de fechas **sí** es un tramo contiguo del índice. Así que la página no
 * sale de una consulta sino de recorrer esos cubos en orden, cada uno con su salto de
 * índice y su `LIMIT` de lo que falte. Ocho saltos en vez de un millón de filas, y el
 * orden que sale es exactamente el mismo que producía el comparador del `MemoryPager`.
 *
 * ## El cursor lleva la cuenta
 *
 * Un cursor de *keyset* dice **dónde** seguir, pero no **cuántas** filas quedaron
 * detrás — y la lista necesita las dos cosas: el centinela de arriba dice «4.213 más».
 * Contar lo que hay por encima sería recorrerlo, o sea justo lo que el cursor evita.
 * Por eso [Key] guarda también cuántas se han saltado: la produce quien ya lo sabía
 * —paginar hacia abajo, o el salto de «enséñame esta tarea», que cuenta una vez—.
 *
 * ## Qué no pasa por aquí
 *
 * Buscar y el filtro de «vencidas» tienen resultados **acotados por su naturaleza**
 * —doscientos aciertos, las tareas con fecha pasada— y van por `MemoryPager`, que los
 * ordena en memoria. Es la misma regla de siempre: SQL para lo que no cabe, Kotlin para
 * lo que sí.
 */
internal class SqlitePager(
    private val sql: Sql,
    private val repo: RepoKey,
    private val config: TasklaneConfig,
    private val filter: TaskFilter,
    private val now: Instant,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: LocalDate = LocalDate.now(zone),
    /**
     * Cómo abrir una conexión de lectura **propia** para [snapshot]. `null` == este
     * paginador no sabe congelarse y exporta sobre la conexión compartida, que es lo que
     * pasa en los tests que no lo necesitan.
     */
    private val detach: (() -> Sql)? = null,
) : TaskPager {

    /** Una combinación de `(marcada, prioridad)`. Ver «la mezcla de cubos» arriba. */
    private data class Bucket(val bookmarked: Int, val rank: Int)

    /**
     * Por dónde sigue una página, y cuántas filas quedaron detrás.
     *
     * Opaco para quien lo recibe, como manda [Cursor]: el panel lo guarda y lo
     * devuelve. Aquí dentro es la tupla del orden más el cubo en el que estaba.
     */
    private data class Key(
        val bucket: Int,
        val sortDate: Long,
        val id: String,
        /**
         * Cuántas filas quedan por encima de la **ventana que abre este cursor**, que es
         * la posición de la fila a la que apunta más uno: un cursor dice cuál fue la
         * última fila servida, y la página siguiente empieza en la de después.
         */
        val skipped: Int,
    ) : Cursor

    private val buckets: List<Bucket> = buildList {
        // De arriba abajo: marcadas primero —marcar es decir «que no se me pierda
        // esto»—, y dentro, de la prioridad más alta a la más baja.
        val ranks = config.priorities.indices.reversed()
        for (marked in intArrayOf(1, 0)) {
            if (filter == TaskFilter.BOOKMARKED && marked == 0) continue
            for (rank in ranks) add(Bucket(marked, rank))
        }
    }

    /** Lo que ya se ha contado de cada lista. Ver el KDoc de `MemoryPager` sobre la concurrencia. */
    private val sizes = ConcurrentHashMap<Pair<StateId, GroupKey?>, Int>()
    private val outlines = ConcurrentHashMap<StateId, List<GroupOutline>>()

    // ------------------------------------------------------------------ contadores

    /**
     * Los contadores de las pestañas, de la tabla `counter`: cinco filas, sin recorrer
     * nada. Es el §2.5.5, y la razón de que `counter` se mantenga dentro de la misma
     * transacción que la escritura.
     */
    override fun counts(): Map<StateId, Int> {
        val column = when (filter) {
            TaskFilter.ALL -> "n"
            TaskFilter.OPEN -> "n_open"
            TaskFilter.BOOKMARKED -> "n_marked"
            // No llega aquí —«vencidas» va por `MemoryPager`— pero si llegara, contar
            // es mejor que mentir.
            TaskFilter.OVERDUE -> "n"
        }
        return sql.rows("SELECT state, $column FROM counter WHERE repo = ? AND $column > 0", repo.value) {
            StateId(it.getString(0).orEmpty()) to it.getInt(1)
        }.toMap()
    }

    // ------------------------------------------------------------------- cabeceras

    override fun outline(stateId: StateId): List<GroupOutline> =
        outlines.computeIfAbsent(stateId, ::buildOutline)

    private fun buildOutline(stateId: StateId): List<GroupOutline> {
        val state = config.state(stateId) ?: return emptyList()
        val groups = when (state.grouping) {
            Grouping.NONE -> return emptyList()
            Grouping.BY_DATE -> dateOutline(stateId)
            Grouping.BY_PRIORITY -> priorityOutline(stateId)
            Grouping.BY_TAG -> tagOutline(stateId)
        }
        for (group in groups) sizes[stateId to group.key] = group.size
        return groups
    }

    /**
     * Las cabeceras de fecha, **sin recorrer las tareas**: se salta al grupo siguiente
     * que tenga algo y se cuenta su tramo.
     *
     * `sort_date < corte ORDER BY sort_date DESC LIMIT 1` es un salto de índice, y dice
     * a qué grupo pertenece la siguiente fila que existe; de ahí sale el intervalo del
     * grupo —[DateGrouper.rangeOf]— y su cuenta es un rango contiguo del mismo índice.
     * Son dos consultas por cabecera **que se va a pintar**, y ninguna por las que no.
     *
     * Desde la 2.3.0 cada día con tareas es una cabecera —antes los años anteriores se
     * juntaban por meses—, así que el coste es proporcional a **los días distintos con
     * tareas**, y sigue sin serlo al número de tareas: una lista de dos años son como
     * mucho unas setecientas cabeceras, se tenga cien tareas o un millón. Ocurre fuera
     * del EDT, y el árbol pagina las cabeceras como todo lo demás.
     */
    private fun dateOutline(stateId: StateId): List<GroupOutline> {
        val out = ArrayList<GroupOutline>()
        var ceiling = Long.MAX_VALUE
        while (true) {
            val next = sql.first(
                "SELECT sort_date FROM task WHERE repo = ? AND state = ? AND undated = 0 AND sort_date < ?" +
                    "${filterSql("")}${bookmarkedSql("")} ORDER BY sort_date DESC LIMIT 1",
                *params(repo.value, stateId.value, ceiling),
            ) { it.getLong(0) } ?: break

            val group = DateGrouper.groupOf(Instant.ofEpochMilli(next), today, zone)
            val range = DateGrouper.rangeOf(group, today, zone) ?: break
            val n = countRange(stateId, range.first, range.last)
            if (n > 0) out += GroupOutline(GroupKey.OfDate(group), n)
            // Estrictamente por debajo del principio del grupo que se acaba de contar.
            ceiling = range.first
        }

        // Las «sin fecha» van al final en vez de desaparecer, y no salen del recorrido
        // de arriba: su fecha de orden es la de modificación, así que caerían en el
        // grupo del día en que se tocaron. Ver [TaskSchema.UNDATED].
        countUndated(stateId).takeIf { it > 0 }?.let {
            out += GroupOutline(GroupKey.OfDate(DateGroup.Undated), it)
        }

        // «Hoy» se enseña aunque esté vacío, pero sólo cuando la lista habla de todo:
        // con un filtro puesto, un «no queda nada» diría que no hay tareas hoy cuando
        // lo que pasa es que no casan con lo que se pidió. Igual que `MemoryPager`.
        val todayKey = GroupKey.OfDate(DateGroup.Today)
        if (filter != TaskFilter.ALL || out.isEmpty() || out.any { it.key == todayKey }) return out
        return listOf(GroupOutline(todayKey, 0)) + out
    }

    private fun priorityOutline(stateId: StateId): List<GroupOutline> =
        config.priorities
            .sortedByDescending { it.order }
            .mapNotNull { priority ->
                val n = buckets
                    .filter { it.rank == priority.order }
                    .sumOf { bucket -> countBucket(stateId, bucket, GroupKey.OfPriority(priority.id)) }
                if (n > 0) GroupOutline(GroupKey.OfPriority(priority.id), n) else null
            }

    /**
     * Las etiquetas de un estado, con su cuenta, de la tabla de unión.
     *
     * `GROUP BY tag` sobre `tag_board` es un recorrido **del índice**, no de las tareas:
     * lee las filas de etiqueta del estado en orden y las agrupa sin ordenar nada. El
     * cajón de «sin etiqueta» sale del índice parcial `task_untagged`, que contiene
     * exactamente eso.
     */
    private fun tagOutline(stateId: StateId): List<GroupOutline> {
        val tagged = sql.rows(
            "SELECT tag, count(*) FROM tag WHERE repo = ? AND state = ?${filterSql("")}${bookmarkedSql("")} " +
                "GROUP BY tag",
            *params(repo.value, stateId.value),
        ) { GroupOutline(GroupKey.OfTag(it.getString(0).orEmpty()), it.getInt(1)) }

        val untagged = sql.count(
            "SELECT count(*) FROM task WHERE repo = ? AND state = ? AND has_tag = 0" +
                "${filterSql("")}${bookmarkedSql("")}",
            *params(repo.value, stateId.value),
        )

        val out = tagged.sortedWith(
            compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { (it.key as GroupKey.OfTag).name },
        )
        return if (untagged > 0) out + GroupOutline(GroupKey.OfTag(null), untagged) else out
    }

    // ---------------------------------------------------------------------- página

    /**
     * Una ventana ya traída, que crece en vez de releerse.
     *
     * Existe porque la ventana **se vuelve a pedir entera** en cada sincronización del
     * árbol: `ListSync` guarda «desde aquí, tantas filas» y no un cursor, precisamente
     * para poder pedir otra vez lo mismo y sincronizar por diferencias. Sin esto,
     * desplazarse una página releía del almacén todo lo que ya estaba cargado —medido:
     * 27 ms de EDT con la ventana en cien filas, y creciendo— cuando lo único nuevo son
     * las cincuenta de abajo.
     *
     * Vive en el paginador y no más arriba porque un paginador **describe un instante**:
     * cuando el modelo cambia llega otro, y con él una ventana vacía. Así lo cacheado no
     * puede quedarse hablando de un almacén que ya no es ése.
     */
    private class Window(var bucket: Int, var cursor: Key?) {
        val rows = ArrayList<Task>()
        var exhausted = false
    }

    private val windows = ConcurrentHashMap<Triple<StateId, GroupKey?, Any?>, Window>()

    override fun page(query: PageQuery, from: Cursor?): TaskPage {
        val key = from as? Key
        val skipped = key?.skipped ?: 0
        val window = windows.computeIfAbsent(Triple(query.stateId, query.group, key)) {
            Window(key?.bucket ?: 0, key)
        }

        val items = synchronized(window) {
            while (window.rows.size < query.limit && !window.exhausted) {
                if (window.bucket >= buckets.size) {
                    window.exhausted = true
                    break
                }
                val want = query.limit - window.rows.size
                val rows = select(
                    query.stateId,
                    buckets[window.bucket],
                    query.group,
                    window.cursor?.takeIf { it.bucket == window.bucket },
                    want,
                )
                if (rows.isNotEmpty()) {
                    window.rows += hydrate(rows.map { it.task })
                    val last = rows.last()
                    window.cursor = Key(window.bucket, last.sortDate, last.id, skipped + window.rows.size)
                }
                if (rows.size < want) {
                    // Este cubo se acabó: al siguiente, y desde su principio.
                    window.bucket++
                    window.cursor = null
                }
            }
            ArrayList(window.rows.subList(0, minOf(query.limit, window.rows.size)))
        }

        val total = sizeOf(query.stateId, query.group)
        return TaskPage(
            items = items,
            before = if (skipped == 0 || key == null) null else Before(skipped, back(query, key)),
            after = (total - skipped - items.size).coerceAtLeast(0),
        )
    }

    /**
     * Todo lo de un trozo de la lista, a tandas y **sin la ventana**.
     *
     * Por el mismo camino que [page] —cubo a cubo, *keyset* dentro de cada uno— pero sin
     * pasar por [Window], que existe para crecer y retenerlo todo: es exactamente lo que
     * una exportación de un millón de filas no puede hacer. Cada tanda es un salto de
     * índice y un `LIMIT`, y lo único que sobrevive entre una y la siguiente es la tupla
     * del orden de su última fila.
     */
    override fun each(query: PageQuery, chunk: Int, block: (List<Task>) -> Unit) {
        for (index in buckets.indices) {
            var from: Key? = null
            while (true) {
                val rows = select(query.stateId, buckets[index], query.group, from, chunk)
                if (rows.isEmpty()) break
                block(hydrate(rows.map { it.task }))
                if (rows.size < chunk) break
                val last = rows.last()
                from = Key(index, last.sortDate, last.id, 0)
            }
        }
    }

    /**
     * Una conexión de lectura propia con una transacción abierta mientras dure [block].
     *
     * En WAL, una transacción de lectura ve la base **tal y como estaba cuando empezó**,
     * y los escritores siguen escribiendo sin esperarla: la ventana responde y la
     * exportación no ve nada de lo que pase después. Propia y no la del paginador de
     * siempre porque esa la comparte la lista, y abrirle una transacción congelaría
     * también lo que la ventana pinta.
     *
     * El precio lo paga el diario: mientras la foto esté abierta, el WAL no se puede
     * vaciar más allá de ella y crece con lo que se escriba entretanto. Es proporcional a
     * lo que dure la exportación, y se recupera en el siguiente *checkpoint*.
     */
    override fun <T> snapshot(block: (TaskPager) -> T): T {
        val open = detach ?: return block(this)
        val sql = open()
        try {
            return sql.transaction {
                block(SqlitePager(sql, repo, config, filter, now, zone, today, detach = null))
            }
        } finally {
            sql.close()
        }
    }

    // --------------------------------------------------------------------- enseñar

    override fun reveal(stateId: StateId, id: TaskId): Reveal? {
        val task = sql.first(
            "SELECT ${TaskRows.COLUMNS}, sort_date, priority_rank, undated " +
                "FROM task WHERE id = ? AND repo = ? AND state = ?",
            id.value,
            repo.value,
            stateId.value,
        ) { Located(TaskRows.read(it), it.getLong(13), it.getInt(14), it.getInt(15) != 0) } ?: return null
        if (!filter.accepts(task.task, now)) return null

        val group = groupOf(stateId, task)
        val bucket = buckets.indexOf(Bucket(if (task.task.bookmarked) 1 else 0, task.rank))
        if (bucket < 0) return null

        // Cuántas filas tiene por encima: los cubos enteros que van antes, más lo que
        // le precede dentro del suyo. Es un recorrido de índice y ocurre **una vez**,
        // en un gesto suelto del usuario, no en un repintado.
        var rank = 0
        for (index in 0 until bucket) rank += countBucket(stateId, buckets[index], group)
        rank += countBucket(stateId, buckets[bucket], group, above = task)

        // Cerca del principio se carga DESDE el principio: así se ve el contexto de
        // arriba y la lista queda como si nunca hubiera saltado.
        if (rank < NEAR) {
            val rows = ((rank + TaskPager.PAGE) / TaskPager.PAGE) * TaskPager.PAGE
            return Reveal(group, null, rows)
        }
        // `rank + 1` y no `rank`: un cursor identifica la última fila **servida**, así
        // que la ventana que abre empieza en la siguiente. Ver [Key.skipped].
        val here = Key(bucket, task.sortDate, task.task.id.value, rank + 1)
        val start = back(PageQuery(stateId, group), here, TaskPager.PAGE / 2)
        return Reveal(group, start, TaskPager.PAGE)
    }

    // ---------------------------------------------------------------------- privado

    private class Located(val task: Task, val sortDate: Long, val rank: Int, val undated: Boolean)

    /** En qué cabecera cae una tarea, según cómo agrupe su estado. */
    private fun groupOf(stateId: StateId, located: Located): GroupKey? {
        val state = config.state(stateId) ?: return null
        return when (state.grouping) {
            Grouping.NONE -> null
            Grouping.BY_DATE -> GroupKey.OfDate(
                if (located.undated) DateGroup.Undated
                else DateGrouper.groupOf(Instant.ofEpochMilli(located.sortDate), today, zone),
            )

            Grouping.BY_PRIORITY -> GroupKey.OfPriority(config.priorityOrDefault(located.task.priorityId).id)
            // Una tarea con varias etiquetas sale bajo todas: para enseñarla vale la
            // primera, que es la que el orden alfabético pondría antes.
            Grouping.BY_TAG -> GroupKey.OfTag(located.task.tags.minWithOrNull(String.CASE_INSENSITIVE_ORDER))
        }
    }

    private class Row(val task: Task, val sortDate: Long) {
        val id: String get() = task.id.value
    }

    /**
     * El `FROM` y el `WHERE` comunes a las tres consultas que hacen falta —traer,
     * retroceder y contar—, que sólo se diferencian en el orden y el tope.
     *
     * Está junto y no repetido tres veces porque es **donde vive la corrección**: qué
     * cubo, qué grupo y qué filtro. Tres copias de esto divergirían, y la forma en que
     * divergirían sería «contar una cosa y enseñar otra», que es justo el fallo que
     * `VisibleTasks` existe para evitar del otro lado.
     */
    private class Clause(val source: String, val alias: String, val where: String, val params: List<Any>)

    private fun clause(stateId: StateId, bucket: Bucket, group: GroupKey?): Clause? {
        val tag = (group as? GroupKey.OfTag)?.name
        // Un grupo de etiqueta se pagina por la tabla de unión, que lleva copiada la
        // tupla de orden justamente para esto. Ver la nota 4 de `TaskSchema`.
        val source = if (tag != null) "tag g JOIN task t ON t.id = g.task_id" else "task t"
        val alias = if (tag != null) "g" else "t"
        val params = ArrayList<Any>()
        val where = StringBuilder(
            "$alias.repo = ? AND $alias.state = ? AND $alias.bookmarked = ? AND $alias.priority_rank = ?",
        )
        params += repo.value
        params += stateId.value
        params += bucket.bookmarked
        params += bucket.rank

        if (tag != null) {
            where.append(" AND g.tag = ?")
            params += tag
        } else if (group is GroupKey.OfTag) {
            where.append(" AND t.has_tag = 0")
        }

        when (group) {
            is GroupKey.OfDate -> {
                val range = DateGrouper.rangeOf(group.group, today, zone)
                if (range == null) {
                    // «Sin fecha» no es un rango: es la marca. La fila tiene fecha de
                    // orden —la de modificación— y por eso no se puede reconocer por ella.
                    where.append(" AND $alias.undated = 1")
                } else {
                    if (range.isEmpty()) return null
                    where.append(" AND $alias.undated = 0 AND $alias.sort_date >= ? AND $alias.sort_date <= ?")
                    params += range.first
                    params += range.last
                }
            }

            is GroupKey.OfPriority -> {
                where.append(" AND t.priority = ?")
                params += group.id.value
            }

            else -> Unit
        }

        where.append(filterSql(alias))
        params.addAll(filterParams())
        return Clause(source, alias, where.toString(), params)
    }

    /**
     * Una tanda de filas de un cubo. Es la única consulta que devuelve tareas, y la
     * forma de la que depende toda la fase: igualdad en `repo`, `state`, `bookmarked` y
     * `priority_rank`, rango en `sort_date`, y el orden ya puesto por el índice.
     */
    private fun select(stateId: StateId, bucket: Bucket, group: GroupKey?, from: Key?, limit: Int): List<Row> {
        val clause = clause(stateId, bucket, group) ?: return emptyList()
        val params = ArrayList(clause.params)
        val where = StringBuilder(clause.where)
        if (from != null) {
            where.append(" AND (${clause.alias}.sort_date, ${tie(clause.alias)}) < (?, ?)")
            params += from.sortDate
            params += from.id
        }
        params += limit

        return sql.rows(
            "SELECT ${TaskRows.columns("t")}, ${clause.alias}.sort_date FROM ${clause.source} WHERE $where " +
                "ORDER BY ${clause.alias}.sort_date DESC, ${tie(clause.alias)} DESC LIMIT ?",
            *params.toTypedArray(),
        ) { Row(TaskRows.read(it), it.getLong(13)) }
    }

    /**
     * El cursor de una ventana ampliada [rows] filas hacia arriba.
     *
     * Se recorre al revés —orden ascendente desde el cursor— y se toma la última, que
     * es la más lejana. Cuesta lo que la ampliación, nunca lo que hay por encima.
     */
    private fun back(query: PageQuery, from: Key, rows: Int = TaskPager.PAGE): Cursor {
        var want = rows
        var bucket = from.bucket
        var cursor: Key = from
        var walked = 0

        while (want > 0 && bucket >= 0) {
            val found = above(query.stateId, buckets[bucket], query.group, if (bucket == from.bucket) from else null, want)
            if (found.isNotEmpty()) {
                val last = found.last()
                cursor = Key(bucket, last.sortDate, last.id, 0)
                walked += found.size
                want -= found.size
            }
            if (want > 0) bucket--
        }
        return cursor.copy(skipped = (from.skipped - walked).coerceAtLeast(0))
    }

    /** Las [limit] filas inmediatamente **por encima** de [from] dentro de un cubo. */
    private fun above(stateId: StateId, bucket: Bucket, group: GroupKey?, from: Key?, limit: Int): List<Row> {
        val clause = clause(stateId, bucket, group) ?: return emptyList()
        val params = ArrayList(clause.params)
        val where = StringBuilder(clause.where)
        if (from != null) {
            where.append(" AND (${clause.alias}.sort_date, ${tie(clause.alias)}) > (?, ?)")
            params += from.sortDate
            params += from.id
        }
        params += limit

        return sql.rows(
            "SELECT ${TaskRows.columns("t")}, ${clause.alias}.sort_date FROM ${clause.source} WHERE $where " +
                "ORDER BY ${clause.alias}.sort_date ASC, ${tie(clause.alias)} ASC LIMIT ?",
            *params.toTypedArray(),
        ) { Row(TaskRows.read(it), it.getLong(13)) }
    }

    /** Cuántas filas tiene un cubo dentro de un grupo, opcionalmente sólo las de más arriba. */
    private fun countBucket(stateId: StateId, bucket: Bucket, group: GroupKey?, above: Located? = null): Int {
        val clause = clause(stateId, bucket, group) ?: return 0
        val params = ArrayList(clause.params)
        val where = StringBuilder(clause.where)
        if (above != null) {
            where.append(" AND (${clause.alias}.sort_date, ${tie(clause.alias)}) > (?, ?)")
            params += above.sortDate
            params += above.task.id.value
        }
        return sql.count("SELECT count(*) FROM ${clause.source} WHERE $where", *params.toTypedArray())
    }

    private fun countRange(stateId: StateId, from: Long, until: Long): Int = sql.count(
        "SELECT count(*) FROM task WHERE repo = ? AND state = ? AND undated = 0 " +
            "AND sort_date >= ? AND sort_date <= ?${filterSql("")}${bookmarkedSql("")}",
        *params(repo.value, stateId.value, from, until),
    )

    private fun countUndated(stateId: StateId): Int = sql.count(
        "SELECT count(*) FROM task WHERE repo = ? AND state = ? AND undated = 1" +
            "${filterSql("")}${bookmarkedSql("")}",
        *params(repo.value, stateId.value),
    )

    /** Los parámetros fijos de una consulta, con los del filtro detrás y en su orden. */
    private fun params(vararg head: Any): Array<Any> = (head.toList() + filterParams()).toTypedArray()

    private fun sizeOf(stateId: StateId, group: GroupKey?): Int =
        sizes.computeIfAbsent(stateId to group) {
            if (group == null) counts()[stateId] ?: 0
            else buckets.sumOf { countBucket(stateId, it, group) }
        }

    private fun hydrate(tasks: List<Task>): List<Task> = TaskStore.hydrate(sql, tasks)

    /**
     * Lo que cierra la tupla del orden, en cada tabla. En `task` es `id`; en la tabla de
     * unión de etiquetas la misma columna se llama `task_id`.
     */
    private fun tie(alias: String): String = if (alias == "g") "g.task_id" else "$alias.id"

    // ------------------------------------------------------------------- filtro

    /**
     * El filtro de vista, en SQL.
     *
     * `BOOKMARKED` no aparece: lo resuelve la lista de cubos, porque `bookmarked` es la
     * primera columna del orden y restringirla es quedarse con la mitad de los cubos.
     * `OVERDUE` sí aparece aunque este paginador no lo atienda —va por `MemoryPager`—:
     * una rama que no cubre el `when` sería una tarea de menos el día que alguien lo
     * enrute aquí.
     */
    private fun filterSql(alias: String): String {
        val p = if (alias.isEmpty()) "" else "$alias."
        return when (filter) {
            TaskFilter.ALL, TaskFilter.BOOKMARKED -> ""
            TaskFilter.OPEN -> " AND ${p}completed_at = ${TaskSchema.NO_DATE}"
            TaskFilter.OVERDUE ->
                " AND ${p}completed_at = ${TaskSchema.NO_DATE} AND ${p}due_date <> ${TaskSchema.NO_DATE} " +
                    "AND ${p}due_date < ?"
        }
    }

    private fun filterParams(): List<Any> =
        if (filter == TaskFilter.OVERDUE) listOf(now.toEpochMilli()) else emptyList()

    /** Las consultas que no van por cubos tienen que aplicar «marcadas» a mano. */
    private fun bookmarkedSql(alias: String): String {
        val p = if (alias.isEmpty()) "" else "$alias."
        return if (filter == TaskFilter.BOOKMARKED) " AND ${p}bookmarked = 1" else ""
    }

    private companion object {
        /**
         * Hasta dónde se considera que una tarea está «arriba» y se carga desde el
         * principio. Dos páginas, igual que en `MemoryPager`.
         */
        const val NEAR = 2 * TaskPager.PAGE
    }
}
