package com.tasklane.data.sqlite

import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.DateGrouper
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.LinkExtractor
import com.tasklane.domain.text.TextNormalizer
import org.jetbrains.sqlite.SqliteResultSet
import java.time.Instant

/**
 * Entre [Task] y una fila de `task`. Es el [com.tasklane.data.store.TasksCodec] de
 * esta fase, y se parece a él a propósito: puro, sin IDE, y con las decisiones de
 * formato escritas donde se toman.
 *
 * **Lo derivado del cuerpo no se guarda.** `Task.links` y `Task.attachments` salen de
 * `body` con una regex, y la fila no los lleva: se vuelven a extraer al leer. Parece
 * al revés que el modelo —que los cachea justamente para no ejecutar una regex al
 * pintar— y no lo es: lo que el modelo evita es reextraerlos **en cada repintado**, y
 * ahora sólo se leen las cincuenta filas de una página, una vez. Guardarlos sería
 * guardar una segunda copia del cuerpo troceada.
 *
 * Lo que sí se guarda derivado es lo que **un índice necesita**: `title`, `tags_text`
 * y `files_text` para FTS5, `priority_rank`, `state_terminal` y `sort_date` para el
 * orden, y `has_link` / `has_image` / `has_anchor` para los operadores `has:`.
 */
internal object TaskRows {

    /** Las columnas que hacen falta para reconstruir una tarea, en orden de lectura. */
    private val COLUMN_NAMES = listOf(
        "seq", "id", "repo", "body", "state", "priority", "ord", "created_at", "updated_at",
        "completed_at", "due_date", "bookmarked", "extra",
    )

    const val COLUMNS =
        "seq, id, repo, body, state, priority, ord, created_at, updated_at, completed_at, due_date, " +
            "bookmarked, extra"

    /**
     * Las mismas, cualificadas con un alias de tabla.
     *
     * Hace falta porque paginar un grupo de etiqueta une `tag` con `task`, y la lista
     * de columnas tiene que seguir siendo **la misma y en el mismo orden**: [read] lee
     * por posición, y dos listas de columnas mantenidas a mano acabarían divergiendo el
     * día que alguien añada una.
     */
    fun columns(alias: String): String = COLUMN_NAMES.joinToString(", ") { "$alias.$it" }

    /**
     * Escribir una tarea. El `seq` va delante y **no** se toca al actualizar: es la
     * llave con la que `task_fts` la encuentra, y renumerarla dejaría la búsqueda
     * apuntando a otra tarea. Ver la nota 3 de [TaskSchema].
     */
    const val INSERT_NEW = """
        INSERT INTO task (
          seq, id, repo, title, body, tags_text, files_text, state, priority, priority_rank, state_terminal,
          sort_date, undated, ord, created_at, updated_at, completed_at, due_date, bookmarked,
          has_link, has_image, has_anchor, has_tag, extra
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    /** Lo mismo, tolerando que la fila ya existiera. Es el camino de una edición. */
    const val UPSERT = """$INSERT_NEW
        ON CONFLICT(id) DO UPDATE SET
          repo = excluded.repo, title = excluded.title, body = excluded.body,
          tags_text = excluded.tags_text, files_text = excluded.files_text,
          state = excluded.state, priority = excluded.priority,
          priority_rank = excluded.priority_rank, state_terminal = excluded.state_terminal,
          sort_date = excluded.sort_date, undated = excluded.undated, ord = excluded.ord,
          created_at = excluded.created_at, updated_at = excluded.updated_at,
          completed_at = excluded.completed_at, due_date = excluded.due_date,
          bookmarked = excluded.bookmarked, has_link = excluded.has_link,
          has_image = excluded.has_image, has_anchor = excluded.has_anchor,
          has_tag = excluded.has_tag, extra = excluded.extra
    """

    /** Cuántos `?` llevan [INSERT_NEW] y [UPSERT]: el `seq` y las veintitrés columnas. */
    const val INSERT_PARAMS = 24

    /** Los parámetros de [INSERT_NEW] para una tarea, en su orden. */
    fun values(seq: Long, task: Task, config: TasklaneConfig): Array<Any> = arrayOf(
        seq,
        task.id.value,
        task.repo.value,
        task.title,
        task.body,
        tagsText(task),
        filesText(task),
        task.stateId.value,
        task.priorityId.value,
        rankOf(task, config),
        if (config.stateOrDefault(task.stateId).terminal) 1 else 0,
        sortDate(task, config),
        if (undated(task, config)) 1 else 0,
        task.order,
        task.createdAt.toEpochMilli(),
        task.updatedAt.toEpochMilli(),
        millis(task.completedAt),
        millis(task.dueDate),
        if (task.bookmarked) 1 else 0,
        if (task.links.isNotEmpty()) 1 else 0,
        if (task.attachments.isNotEmpty()) 1 else 0,
        if (task.anchors.isNotEmpty()) 1 else 0,
        if (task.tags.isNotEmpty()) 1 else 0,
        encodeExtra(task.extra),
    )

    /**
     * La tarea de una fila, **sin etiquetas ni anclas**: ésas viven en sus tablas y las
     * pone [TaskStore] de una sola consulta para toda la página, no una por tarea.
     */
    fun read(rows: SqliteResultSet): Task {
        val body = rows.getString(3).orEmpty()
        return Task(
            id = TaskId(rows.getString(1).orEmpty()),
            repo = RepoKey(rows.getString(2).orEmpty()),
            body = body,
            stateId = StateId(rows.getString(4).orEmpty()),
            priorityId = PriorityId(rows.getString(5).orEmpty()),
            createdAt = Instant.ofEpochMilli(rows.getLong(7)),
            updatedAt = Instant.ofEpochMilli(rows.getLong(8)),
            completedAt = instant(rows.getLong(9)),
            order = rows.getLong(6),
            links = LinkExtractor.extract(body),
            attachments = ImageRefParser.parse(body),
            dueDate = instant(rows.getLong(10)),
            bookmarked = rows.getInt(11) != 0,
            extra = decodeExtra(rows.getString(12).orEmpty()),
        )
    }

    /** El `seq` de la fila que [read] acaba de leer. Lo necesita quien borra del FTS. */
    fun seqOf(rows: SqliteResultSet): Long = rows.getLong(0)

    // ------------------------------------------------------------------ derivados

    /**
     * La fecha de la que cuelga el orden y la agrupación, ya resuelta para el estado en
     * el que está la tarea. Ver la nota 1 de [TaskSchema].
     *
     * Es literalmente `DateGrouper.anchorOf(...) ?: updatedAt`, que es el comparador de
     * `MemoryPager` copiado: por eso la agrupación por fecha de los dos paginadores no
     * puede divergir. El caso del `?:` no es teórico — un estado que agrupa por fecha de
     * completado y una tarea sin completar— y ordenar ésas por identificador, que es lo
     * que salía de guardar «sin fecha» en esta columna, se nota en la lista.
     */
    fun sortDate(task: Task, config: TasklaneConfig): Long =
        (DateGrouper.anchorOf(task, config.stateOrDefault(task.stateId).anchor) ?: task.updatedAt).toEpochMilli()

    /** Si la fecha del anclaje no existe: la tarea va al grupo «sin fecha». Ver [TaskSchema.UNDATED]. */
    fun undated(task: Task, config: TasklaneConfig): Boolean =
        DateGrouper.anchorOf(task, config.stateOrDefault(task.stateId).anchor) == null

    fun rankOf(task: Task, config: TasklaneConfig): Int =
        config.priorityOrDefault(task.priorityId).order

    /** Las etiquetas como las indexa FTS5: normalizadas y separadas por espacios. */
    fun tagsText(task: Task): String =
        task.tags.joinToString(" ") { TextNormalizer.normalize(it) }

    /**
     * Las rutas ancladas, normalizadas y **también troceadas por separadores**.
     *
     * El tokenizador `unicode61` ya parte `src/main/Auth.kt` en `src`, `main`, `auth` y
     * `kt`, así que la ruta entera bastaría. Se guarda de todos modos completa porque
     * es lo que se busca cuando alguien escribe `file:` con un trozo de directorio.
     */
    fun filesText(task: Task): String =
        task.anchors.joinToString(" ") { TextNormalizer.normalize(it.path) }

    fun millis(instant: Instant?): Long = instant?.toEpochMilli() ?: TaskSchema.NO_DATE

    fun instant(millis: Long): Instant? =
        if (millis == TaskSchema.NO_DATE) null else Instant.ofEpochMilli(millis)

    // --------------------------------------------------------------------- extra

    /**
     * [Task.extra] en una celda.
     *
     * **Por qué no JSON**, que es lo que decía el §2.4: el plugin no tiene un parser de
     * JSON y meter uno por un mapa de dos claves —`origStateId` y `origPriorityId`, más
     * los atributos que escribiera una versión futura del `tasks.xml`— sería la misma
     * decisión que ya se rechazó con `kotlinx-serialization`. El formato es
     * `clave=valor` por línea, con `\` escapando `\`, `=` y el salto: alcanza para
     * cualquier par de cadenas y se lee de un vistazo cuando alguien abre la base con
     * un visor.
     */
    fun encodeExtra(extra: Map<String, String>): String {
        if (extra.isEmpty()) return ""
        return extra.entries.joinToString("\n") { (k, v) -> "${escape(k)}=${escape(v)}" }
    }

    fun decodeExtra(text: String): Map<String, String> {
        if (text.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val key = StringBuilder()
            val value = StringBuilder()
            var target = key
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '\\' && i + 1 < line.length -> {
                        target.append(unescape(line[i + 1]))
                        i++
                    }
                    ch == '=' && target === key -> target = value
                    else -> target.append(ch)
                }
                i++
            }
            // Una línea sin `=` no es un par: se descarta esa y se conservan las demás,
            // igual que `TasksCodec` descarta una tarea sin id sin tumbar el fichero.
            if (target === value) out[key.toString()] = value.toString()
        }
        return out
    }

    private fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '=' -> sb.append("\\e")
                '\n' -> sb.append("\\n")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescape(ch: Char): Char = when (ch) {
        'e' -> '='
        'n' -> '\n'
        else -> ch
    }

    // ------------------------------------------------------------------- anclas

    fun anchorOf(rows: SqliteResultSet): CodeAnchor = CodeAnchor(
        path = rows.getString(1).orEmpty(),
        line = rows.getInt(2),
        column = rows.getInt(3),
        text = rows.getString(4).orEmpty(),
    )
}
