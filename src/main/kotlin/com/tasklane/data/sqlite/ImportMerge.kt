package com.tasklane.data.sqlite

import com.tasklane.domain.command.Mutation
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TasklaneConfig

/**
 * Meter tareas que vienen de fuera —un archivo de [TaskArchive]— en un repositorio que ya
 * tiene las suyas, **sin pisar nada que el usuario haya hecho después**.
 *
 * La regla, por id:
 *
 * - No estaba → **se añade**.
 * - Estaba y la de fuera es **más reciente** (`updatedAt` mayor) → **se actualiza**.
 * - Estaba y es igual o más antigua → **se queda la de aquí**.
 * - Estaba, pero en **otro repositorio** de esta base → no se toca: el mismo id en dos
 *   sitios es la misma tarea que se movió, y traerla la duplicaría.
 *
 * Es un plan primero ([plan]) y una escritura después ([apply]) porque el usuario ve las
 * cuentas **antes** de que se toque nada. Sin IDE, para probar cada rama.
 */
internal object ImportMerge {

    data class Counts(
        val added: Int = 0,
        val updated: Int = 0,
        /** Iguales a las de aquí: nada que hacer. */
        val same: Int = 0,
        /** Más antiguas que las de aquí: se conservan las locales. */
        val kept: Int = 0,
        val elsewhere: Int = 0,
    ) {
        val total: Int get() = added + updated + same + kept + elsewhere
        val writes: Int get() = added + updated

        operator fun plus(o: Counts) =
            Counts(added + o.added, updated + o.updated, same + o.same, kept + o.kept, elsewhere + o.elsewhere)
    }

    class Plan(val counts: Counts, val write: List<Task>)

    /** Clasifica una tanda. Sólo lee. */
    fun plan(store: TaskStore, repo: RepoKey, config: TasklaneConfig, incoming: List<Task>): Plan {
        val existing = store.tasks(incoming.map { it.id }).associateBy { it.id }
        var counts = Counts()
        val write = ArrayList<Task>()
        for (task in incoming.distinctBy { it.id }) {
            val here = existing[task.id]
            counts += when {
                here == null -> Counts(added = 1).also { write += fit(task, repo, config) }
                here.repo != repo -> Counts(elsewhere = 1)
                task.updatedAt > here.updatedAt -> Counts(updated = 1).also { write += fit(task, repo, config) }
                task.updatedAt == here.updatedAt -> Counts(same = 1)
                else -> Counts(kept = 1)
            }
        }
        return Plan(counts, write)
    }

    /**
     * Una tarea de otro proyecto puede nombrar un estado o una prioridad que aquí no
     * existen: se queda en los de por defecto en vez de en uno que no se pinta.
     */
    private fun fit(task: Task, repo: RepoKey, config: TasklaneConfig): Task = task.copy(
        repo = repo,
        stateId = config.stateOrDefault(task.stateId).id,
        priorityId = config.priorityOrDefault(task.priorityId).id,
    )

    fun apply(store: TaskStore, config: TasklaneConfig, plan: Plan) {
        if (plan.write.isEmpty()) return
        store.apply(config, listOf(Mutation.Upsert(plan.write)))
    }
}
