package com.tasklane.domain.command

import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import java.time.Clock
import java.time.Instant

/**
 * `(Snapshot, Command) -> Snapshot`. Aquí viven las invariantes del modelo y nada más:
 * sin IO, sin Swing, sin `Project`. Es la clase que concentra la lógica y la única
 * que hace falta testear para confiar en el comportamiento.
 */
class TaskReducer(private val clock: Clock = Clock.systemUTC()) {

    fun reduce(snapshot: TasklaneSnapshot, command: TaskCommand): TasklaneSnapshot {
        val now = Instant.now(clock)

        return when (command) {
            // ------------------------------------------------ alcance proyecto

            is TaskCommand.ConfigChanged -> {
                val config = command.config.normalized()
                if (config == snapshot.config) snapshot
                else snapshot.copy(config = config).mapAllTasks { normalize(it, config) }
            }

            is TaskCommand.ReassignState -> {
                if (command.from == command.to) return snapshot
                snapshot.mapAllTasks { task ->
                    if (task.stateId != command.from) task
                    else applyState(task, command.to, snapshot.config, now)
                }
            }

            is TaskCommand.ReassignPriority -> {
                if (command.from == command.to) return snapshot
                snapshot.mapAllTasks { task ->
                    if (task.priorityId != command.from) task
                    else task.copy(
                        priorityId = command.to,
                        updatedAt = now,
                        extra = task.extra - ORIG_PRIORITY,
                    )
                }
            }

            is TaskCommand.BackfillCompletedAt -> snapshot.mapAllTasks { task ->
                // `updatedAt` no se toca: la fecha que se rellena se DERIVA de ella,
                // así que pisarla destruiría justamente el dato que se está usando.
                if (task.stateId in command.states && task.completedAt == null) {
                    task.copy(completedAt = task.updatedAt)
                } else {
                    task
                }
            }

            // ----------------------------------------------------- acotados

            is RepoScoped -> reduceScoped(snapshot, command, now)
        }
    }

    private fun reduceScoped(
        snapshot: TasklaneSnapshot,
        command: RepoScoped,
        now: Instant,
    ): TasklaneSnapshot {
        val repo = command.repo
        val current = snapshot.tasksOf(repo)
        val config = snapshot.config

        val next: List<Task> = when (command) {
            is TaskCommand.Loaded -> return snapshot.copy(
                tasksByRepo = snapshot.tasksByRepo + (repo to command.tasks.map { normalize(it, config) }),
                loading = snapshot.loading - repo,
            )

            is TaskCommand.Create -> {
                val body = command.body.trim()
                // Una tarea vacía no es una tarea: se ignora en vez de crear ruido.
                if (body.isEmpty()) return snapshot
                val state = command.stateId?.let { config.stateOrDefault(it) } ?: config.defaultState
                val priority = command.priorityId?.let { config.priorityOrDefault(it) } ?: config.defaultPriority
                current + Task(
                    id = TaskId.random(),
                    repo = repo,
                    body = body,
                    stateId = state.id,
                    priorityId = priority.id,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = if (state.terminal) now else null,
                    order = snapshot.nextOrder(repo),
                )
            }

            is TaskCommand.UpdateBody -> current.mapTask(command.id) { task ->
                val body = command.body.trim()
                if (body.isEmpty() || body == task.body) task
                else task.copy(body = body, updatedAt = now)
            }

            is TaskCommand.ChangeState -> current.mapTask(command.id) { task ->
                applyState(task, command.stateId, config, now)
            }

            is TaskCommand.ChangePriority -> current.mapTask(command.id) { task ->
                if (task.priorityId == command.priorityId) task
                // Mover a mano es una decisión: deja de ser una huérfana aparcada
                // y por eso pierde la marca que la haría volver sola.
                else task.copy(
                    priorityId = command.priorityId,
                    updatedAt = now,
                    extra = task.extra - ORIG_PRIORITY,
                )
            }

            is TaskCommand.ToggleComplete -> current.mapTask(command.id) { task ->
                val isDone = config.stateOrDefault(task.stateId).terminal
                val target = if (isDone) {
                    config.defaultState.id
                } else {
                    (config.states.firstOrNull { it.terminal } ?: config.defaultState).id
                }
                applyState(task, target, config, now)
            }

            is TaskCommand.Delete -> {
                val ids = command.ids.toSet()
                current.filterNot { it.id in ids }
            }
        }

        if (next === current) return snapshot
        return snapshot.copy(tasksByRepo = snapshot.tasksByRepo + (repo to next))
    }

    private fun applyState(
        task: Task,
        target: StateId,
        config: TasklaneConfig,
        now: Instant,
    ): Task {
        if (task.stateId == target) return task
        val state = config.stateOrDefault(target)
        return task.copy(
            stateId = state.id,
            updatedAt = now,
            // Entrar en terminal sella la fecha de finalización; salir la conserva.
            // Conservarla no es un descuido: es lo que hace estable la agrupación
            // histórica si el usuario reabre y vuelve a cerrar una tarea.
            completedAt = if (state.terminal) task.completedAt ?: now else task.completedAt,
            extra = task.extra - ORIG_STATE,
        )
    }

    /**
     * Red de seguridad ante configuración desincronizada, en los dos sentidos.
     *
     * **Se fue:** una tarea que apunta a un estado o prioridad inexistente NO se
     * descarta; se remapea al valor por defecto y el ID original queda en
     * [Task.extra] —que el códec persiste, así que sobrevive a cerrar el proyecto—.
     *
     * **Volvió:** si ese ID reaparece en la configuración, la tarea regresa sola a
     * su sitio. La marca sólo sobrevive mientras la tarea siga aparcada en el
     * fallback: moverla a mano la borra, de modo que la vuelta nunca pisa una
     * decisión del usuario.
     */
    private fun normalize(task: Task, config: TasklaneConfig): Task {
        var stateId = task.stateId
        var priorityId = task.priorityId
        val extra = task.extra.toMutableMap()

        extra[ORIG_STATE]?.let { orig ->
            config.state(StateId(orig))?.let { stateId = it.id; extra -= ORIG_STATE }
        }
        extra[ORIG_PRIORITY]?.let { orig ->
            config.priority(PriorityId(orig))?.let { priorityId = it.id; extra -= ORIG_PRIORITY }
        }

        if (config.state(stateId) == null) {
            // putIfAbsent y no put: si la tarea ya venía huérfana, el ID que vale la
            // pena recordar es el primero, no el fallback por el que pasó después.
            extra.putIfAbsent(ORIG_STATE, stateId.value)
            stateId = config.defaultState.id
        }
        if (config.priority(priorityId) == null) {
            extra.putIfAbsent(ORIG_PRIORITY, priorityId.value)
            priorityId = config.defaultPriority.id
        }

        if (stateId == task.stateId && priorityId == task.priorityId && extra == task.extra) return task
        return task.copy(stateId = stateId, priorityId = priorityId, extra = extra)
    }

    /**
     * Aplica [transform] a todas las tareas de todos los repositorios conservando la
     * identidad de lo que no cambia: si un repositorio sale intacto se devuelve su
     * misma lista, y si ninguno cambia se devuelve el mismo snapshot.
     *
     * No es micro-optimización. `ConfigChanged` se emite en cada arranque y en cada
     * paso por los ajustes, y casi siempre no tiene nada que remapear; reconstruir
     * ahí todas las listas haría que el servicio marcara como sucios —y reescribiera
     * en disco— repositorios en los que no ha pasado nada.
     */
    private inline fun TasklaneSnapshot.mapAllTasks(transform: (Task) -> Task): TasklaneSnapshot {
        var anyChanged = false
        val next = tasksByRepo.mapValues { (_, tasks) ->
            var changed = false
            val mapped = tasks.map { task -> transform(task).also { if (it !== task) changed = true } }
            if (!changed) return@mapValues tasks
            anyChanged = true
            mapped
        }
        return if (anyChanged) copy(tasksByRepo = next) else this
    }

    private inline fun List<Task>.mapTask(id: TaskId, transform: (Task) -> Task): List<Task> {
        var changed = false
        val next = map { task ->
            if (task.id != id) task else transform(task).also { if (it !== task) changed = true }
        }
        // Devolver la MISMA lista cuando nada cambió deja que el servicio decida por
        // identidad qué repositorios hay que reescribir, sin comparar contenido.
        return if (changed) next else this
    }

    companion object {
        const val ORIG_STATE = "origStateId"
        const val ORIG_PRIORITY = "origPriorityId"

        /** Tareas aparcadas en el fallback por configuración ausente. */
        fun orphans(tasks: List<Task>): List<Task> =
            tasks.filter { ORIG_STATE in it.extra || ORIG_PRIORITY in it.extra }
    }
}
