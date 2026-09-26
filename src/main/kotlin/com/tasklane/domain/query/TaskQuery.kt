package com.tasklane.domain.query

import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.TextNormalizer
import java.time.Instant

/**
 * Una consulta ya interpretada: texto libre más filtros.
 *
 * Todos los textos vienen **normalizados** (`TextNormalizer`), de modo que quien
 * evalúa la consulta no tiene que acordarse de normalizar nada — sólo el documento
 * de la tarea, que además está cacheado. Las fechas vienen igual de resueltas: `today`
 * o `<7d` llegan ya como instantes, y evaluarlas es comparar números.
 *
 * La semántica es la que espera cualquiera que haya usado un buscador: **OR dentro
 * de un mismo operador, AND entre operadores distintos**. `state:todo state:doing
 * fallo` es «(ToDo o Doing) y que contenga fallo». Hay dos excepciones deliberadas:
 * los tags —`#api #urgente` pide las dos etiquetas, porque para eso se ponen— y las
 * fechas —`created:>2026-09-01 created:<2026-09-10` es un intervalo, que es para lo que
 * se escriben dos—.
 */
data class TaskQuery(
    /** Términos de texto libre. Todos tienen que aparecer. Nunca los negados: ésos van en [excluded]. */
    val terms: List<String> = emptyList(),
    /** Prefijos de nombre de estado. */
    val states: Set<String> = emptySet(),
    /** Prefijos de nombre de prioridad. */
    val priorities: Set<String> = emptySet(),
    /** Prefijos de nombre de repositorio. */
    val repos: Set<String> = emptySet(),
    /** Prefijos de etiqueta. Se piden todas. */
    val tags: Set<String> = emptySet(),
    /**
     * Trozos de la ruta de un ancla de código. **Contiene**, no empieza por: lo que se
     * teclea es el nombre del fichero y la ruta lleva sus directorios delante.
     */
    val files: Set<String> = emptySet(),
    /** `is:done` / `is:open`. `null` = da igual. */
    val done: Boolean? = null,
    /** `has:…`, y también `is:bookmarked`: propiedades sí/no de la tarea. Se piden todas. */
    val has: Set<Facet> = emptySet(),
    /** `due:`, `closed:`, `created:` y `updated:` (2.21.0). Se piden todas: ver el KDoc de la clase. */
    val dates: List<DateFilter> = emptyList(),
    /**
     * `is:overdue` (2.21.0): vencidas **en este instante**, el de cuando se interpretó la
     * consulta. Lleva el reloj dentro porque es lo único que cambia sin que nadie escriba,
     * y `Task.isOverdue` pide un «ahora»: que sea uno solo para toda la búsqueda.
     */
    val overdue: Instant? = null,
    /**
     * Lo negado con `-` (2.21.0): `-#wip`, `-p:low`, `-is:done`. Una tarea sale si **no**
     * casa con ninguna. Cada una es **una sola condición** —un token con su `-` delante—, y
     * quien evalúa puede contar con ello: negar una consulta de una condición es negar esa
     * condición, sin De Morgan de por medio.
     */
    val excluded: List<TaskQuery> = emptyList(),
) {
    /**
     * [BROKEN_ANCHOR] (2.13.0) es la única que no sale de la tarea: si el fichero sigue
     * ahí lo dice el disco, y quien busca lo recibe en `SearchCorpus.brokenAnchors`.
     * [CHECKLIST] (2.21.0) sale del cuerpo y no de una columna: ver `Fts5Index`.
     */
    enum class Facet { LINK, IMAGE, CODE, BROKEN_ANCHOR, DUE, CHECKLIST, TAG, BOOKMARKED }

    /** Una consulta vacía no filtra nada: la UI la usa para volver a la vista normal. */
    val isEmpty: Boolean
        get() = terms.isEmpty() && states.isEmpty() && priorities.isEmpty() &&
            repos.isEmpty() && tags.isEmpty() && files.isEmpty() && done == null && has.isEmpty() &&
            dates.isEmpty() && overdue == null && excluded.isEmpty()

    /**
     * ¿Pregunta la consulta por lo cerrado? `is:done`, `-is:open`, `closed:week` o un estado
     * terminal por su nombre —`state:Done`— sólo tienen sentido mirando lo terminado, así
     * que quien lo esconde por defecto —la herramienta MCP— tiene que dejar de hacerlo.
     * Pide la [config] por el último caso: qué estados cierran lo dice ella, y los nombres
     * casan por prefijo, como al buscar.
     */
    fun mentionsClosed(config: TasklaneConfig): Boolean =
        done != null || dates.any { it.field == DateField.CLOSED } || excluded.any { it.done != null } ||
            states.any { prefix -> config.states.any { it.terminal && TextNormalizer.normalize(it.name).startsWith(prefix) } }

    /** El texto libre unido, que es lo que se resalta en la fila. */
    val text: String get() = terms.joinToString(" ")

    companion object {
        val EMPTY = TaskQuery()
    }
}

/** De qué fecha de la tarea habla un [DateFilter]. */
enum class DateField { DUE, CLOSED, CREATED, UPDATED }

/**
 * Un intervalo sobre una fecha de la tarea, ya resuelto: desde [from], incluido, hasta
 * [until], excluido; `null` es «sin límite» por ese lado. Una tarea sin esa fecha —sin
 * vencimiento, sin cerrar— no casa nunca, ni con un intervalo abierto por los dos lados.
 */
data class DateFilter(val field: DateField, val from: Instant?, val until: Instant?) {
    fun accepts(date: Instant?): Boolean =
        date != null && (from == null || !date.isBefore(from)) && (until == null || date.isBefore(until))
}
