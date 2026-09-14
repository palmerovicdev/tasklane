package com.tasklane.domain.query

/**
 * Una consulta ya interpretada: texto libre más filtros.
 *
 * Todos los textos vienen **normalizados** (`TextNormalizer`), de modo que quien
 * evalúa la consulta no tiene que acordarse de normalizar nada — sólo el documento
 * de la tarea, que además está cacheado.
 *
 * La semántica es la que espera cualquiera que haya usado un buscador: **OR dentro
 * de un mismo operador, AND entre operadores distintos**. `state:todo state:doing
 * fallo` es «(ToDo o Doing) y que contenga fallo». Los tags son la excepción
 * deliberada: `#api #urgente` pide las dos etiquetas, porque para eso se ponen.
 */
data class TaskQuery(
    /** Términos de texto libre. Todos tienen que aparecer. */
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
    val has: Set<Facet> = emptySet(),
) {
    enum class Facet { LINK, IMAGE, CODE }

    /** Una consulta vacía no filtra nada: la UI la usa para volver a la vista normal. */
    val isEmpty: Boolean
        get() = terms.isEmpty() && states.isEmpty() && priorities.isEmpty() &&
            repos.isEmpty() && tags.isEmpty() && files.isEmpty() && done == null && has.isEmpty()

    /** El texto libre unido, que es lo que se resalta en la fila. */
    val text: String get() = terms.joinToString(" ")

    companion object {
        val EMPTY = TaskQuery()
    }
}
