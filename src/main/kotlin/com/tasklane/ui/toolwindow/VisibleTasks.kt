package com.tasklane.ui.toolwindow

import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.service.SearchResults
import java.time.Instant

/**
 * Qué tareas se ven en la ventana, una sola vez y para todo el mundo.
 *
 * Existe por el contador de las pestañas. La lista y el contador tienen que salir de
 * la **misma** cuenta: en cuanto cada uno filtra por su cuenta, un `Todo 3` acaba
 * encima de una lista de dos y el usuario cree que le falta una tarea. Así que aquí
 * está la regla —universo, búsqueda y filtro de vista— y la usan la lista y la fila
 * de estados, que se pintan las dos desde [TasklanePanel].
 *
 * Lo que **no** está aquí es ordenar ni agrupar: eso sólo lo necesita quien pinta, y
 * contar no debe pagarlo. De ahí la única discrepancia que queda viva y es correcta:
 * agrupando por etiqueta, una tarea con dos etiquetas sale en dos filas y el contador
 * sigue diciendo una. Cuenta tareas, no filas.
 */
internal object VisibleTasks {

    /** Las de un estado, listas para agrupar y ordenar. */
    fun of(
        snapshot: TasklaneSnapshot,
        found: SearchResults,
        filter: TaskFilter,
        stateId: StateId,
        now: Instant,
    ): List<Task> = pool(snapshot, found)
        .filter { it.stateId == stateId && found.accepts(it.id) && filter.accepts(it, now) }

    /**
     * Cuántas hay en cada estado. Se cuentan de una pasada y no estado por estado
     * porque las pestañas las piden todas a la vez en cada repintado.
     */
    fun countsByState(
        snapshot: TasklaneSnapshot,
        found: SearchResults,
        filter: TaskFilter,
        now: Instant,
    ): Map<StateId, Int> = pool(snapshot, found)
        .filter { found.accepts(it.id) && filter.accepts(it, now) }
        .groupingBy { it.stateId }
        .eachCount()

    /**
     * El universo de la ventana. Con búsqueda activa es el snapshot entero: el
     * alcance ya lo aplicó el índice, y si el usuario pidió «todos los repositorios»
     * los resultados de fuera del activo tienen que poder salir.
     */
    private fun pool(snapshot: TasklaneSnapshot, found: SearchResults): List<Task> =
        if (found.active) snapshot.tasksByRepo.values.flatten() else snapshot.activeTasks
}
