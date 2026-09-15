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
 * está la regla —universo, búsqueda y filtro de vista— y de ella salen las dos cosas.
 *
 * Desde la Fase 2 la regla se aplica **una sola vez por repintado**: [byState] hace la
 * única pasada y [of] y [countsByState] son vistas de su resultado. Antes eran dos
 * recorridos del corpus por repintado, uno para contar y otro para listar.
 *
 * Lo que **no** está aquí es ordenar ni agrupar: eso sólo lo necesita quien pinta, y
 * contar no debe pagarlo. Vive en
 * [com.tasklane.paging.InMemoryPager]. De ahí la única discrepancia que queda viva y
 * es correcta: agrupando por etiqueta, una tarea con dos etiquetas sale en dos filas
 * y el contador sigue diciendo una. Cuenta tareas, no filas.
 */
internal object VisibleTasks {

    /**
     * Las tareas visibles de **cada** estado, repartidas en una sola pasada.
     *
     * Es el agregado del que salen los contadores de las pestañas y la lista de la
     * pestaña abierta. En la Fase 3 los contadores dejan de salir de aquí y pasan a
     * leerse de la tabla `counter`, que se mantiene en la misma transacción que la
     * escritura; el reparto por estado lo sustituye un `WHERE state = ?`.
     */
    fun byState(
        snapshot: TasklaneSnapshot,
        found: SearchResults,
        filter: TaskFilter,
        now: Instant,
    ): Map<StateId, List<Task>> {
        val byState = HashMap<StateId, MutableList<Task>>()
        for (task in pool(snapshot, found)) {
            if (!found.accepts(task.id)) continue
            if (!filter.accepts(task, now)) continue
            byState.getOrPut(task.stateId) { ArrayList() } += task
        }
        return byState
    }

    /** Las de un estado, listas para agrupar y ordenar. */
    fun of(
        snapshot: TasklaneSnapshot,
        found: SearchResults,
        filter: TaskFilter,
        stateId: StateId,
        now: Instant,
    ): List<Task> = byState(snapshot, found, filter, now)[stateId].orEmpty()

    /** Cuántas hay en cada estado. */
    fun countsByState(
        snapshot: TasklaneSnapshot,
        found: SearchResults,
        filter: TaskFilter,
        now: Instant,
    ): Map<StateId, Int> = byState(snapshot, found, filter, now).mapValues { it.value.size }

    /**
     * El universo de la ventana. Con búsqueda activa es el snapshot entero: el
     * alcance ya lo aplicó el índice, y si el usuario pidió «todos los repositorios»
     * los resultados de fuera del activo tienen que poder salir.
     */
    private fun pool(snapshot: TasklaneSnapshot, found: SearchResults): List<Task> =
        if (found.active) snapshot.tasksByRepo.values.flatten() else snapshot.activeTasks
}
