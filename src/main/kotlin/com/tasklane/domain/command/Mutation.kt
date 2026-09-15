package com.tasklane.domain.command

import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import java.time.Instant

/**
 * Lo que hay que cambiar en el almacén, dicho por el reducer y aplicado por el store.
 *
 * Es el cambio de alcance del `docs/plan-escala.md` §3.4. Hasta la Fase 2 el reducer
 * recibía el modelo entero y devolvía un modelo entero; un comando que tocaba una
 * tarea copiaba la lista del repositorio —O(n) por pulsación— y el servicio escribía
 * el fichero completo detrás. Ahora el reducer recibe **sólo las tareas que el comando
 * nombra** y devuelve **sólo las filas que hay que tocar**.
 *
 * La división en dos familias es la que importa y no es cosmética:
 *
 * - [Upsert] y [Delete] llevan filas. Su coste es el número de tareas que el usuario
 *   señaló, que es uno o un puñado.
 * - [Reassign], [Reprioritize], [Backfill], [Renormalize] y [Forget] **no llevan
 *   filas**: describen un cambio de alcance de proyecto que el almacén resuelve con un
 *   `UPDATE … WHERE`. Borrar un estado con un millón de tareas dentro deja de ser un
 *   `map` sobre un millón de objetos y pasa a ser una sentencia.
 *
 * Esa segunda familia es la razón de que el reducer no pueda limitarse a devolver
 * tareas: las tareas que reasignar no caben en memoria, y el único sitio donde el
 * cambio se puede expresar sin materializarlas es el propio almacén.
 */
sealed interface Mutation {

    /** Filas que nacen o cambian. El reducer ya las dejó normalizadas. */
    data class Upsert(val tasks: List<Task>) : Mutation

    data class Delete(val ids: List<TaskId>) : Mutation

    /**
     * Todo lo que está en [from] pasa a [to], con la misma semántica que
     * `TaskReducer.applyState`: se sella `completedAt` al entrar en un estado terminal
     * y se conserva al salir.
     */
    data class Reassign(val from: StateId, val to: StateId, val at: Instant) : Mutation

    data class Reprioritize(val from: PriorityId, val to: PriorityId, val at: Instant) : Mutation

    /** Rellena `completedAt` desde `updatedAt` en las tareas de [states] que no lo tengan. */
    data class Backfill(val states: Set<StateId>) : Mutation

    /**
     * La configuración cambió: hay que rehacer lo que se guarda derivado de ella
     * —`priority_rank`, `state_terminal`, `sort_date`— y recolocar las tareas que
     * apuntan a configuración que ya no existe, en los dos sentidos.
     *
     * Lleva la configuración **anterior** porque es lo que permite tocar sólo lo que
     * cambió: sin ella habría que reescribir el millón de filas en cada paso por los
     * ajustes, y en los ajustes casi nunca se toca un estado.
     */
    data class Renormalize(val before: com.tasklane.domain.model.TasklaneConfig) : Mutation

    /** Saca del almacén un repositorio entero. Lo emite «Exportar y quitar». */
    data class Forget(val repo: RepoKey) : Mutation
}
