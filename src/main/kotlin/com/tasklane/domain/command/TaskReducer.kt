package com.tasklane.domain.command

import com.tasklane.domain.model.Task
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
        val repo = command.repo
        val current = snapshot.tasksOf(repo)

        val next: List<Task> = when (command) {
            is TaskCommand.Loaded -> return snapshot.copy(
                tasksByRepo = snapshot.tasksByRepo + (repo to command.tasks.map { normalize(it, snapshot) }),
                loading = snapshot.loading - repo,
            )

            is TaskCommand.Create -> {
                val body = command.body.trim()
                // Una tarea vacía no es una tarea: se ignora en vez de crear ruido.
                if (body.isEmpty()) return snapshot
                val state = command.stateId?.let { snapshot.config.stateOrDefault(it) }
                    ?: snapshot.config.defaultState
                val priority = command.priorityId?.let { snapshot.config.priorityOrDefault(it) }
                    ?: snapshot.config.defaultPriority
                current + Task(
                    id = com.tasklane.domain.model.TaskId.random(),
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
                applyState(task, command.stateId, snapshot, now)
            }

            is TaskCommand.ChangePriority -> current.mapTask(command.id) { task ->
                if (task.priorityId == command.priorityId) task
                else task.copy(priorityId = command.priorityId, updatedAt = now)
            }

            is TaskCommand.ToggleComplete -> current.mapTask(command.id) { task ->
                val isDone = snapshot.config.stateOrDefault(task.stateId).terminal
                val target = if (isDone) {
                    snapshot.config.defaultState.id
                } else {
                    (snapshot.config.states.firstOrNull { it.terminal } ?: snapshot.config.defaultState).id
                }
                applyState(task, target, snapshot, now)
            }

            is TaskCommand.Delete -> {
                val ids = command.ids.toSet()
                current.filterNot { it.id in ids }
            }
        }

        return snapshot.copy(tasksByRepo = snapshot.tasksByRepo + (repo to next))
    }

    private fun applyState(
        task: Task,
        target: com.tasklane.domain.model.StateId,
        snapshot: TasklaneSnapshot,
        now: Instant,
    ): Task {
        if (task.stateId == target) return task
        val state = snapshot.config.stateOrDefault(target)
        return task.copy(
            stateId = state.id,
            updatedAt = now,
            // Entrar en terminal sella la fecha de finalización; salir la conserva.
            // Conservarla no es un descuido: es lo que hace estable la agrupación
            // histórica si el usuario reabre y vuelve a cerrar una tarea.
            completedAt = if (state.terminal) task.completedAt ?: now else task.completedAt,
        )
    }

    /**
     * Red de seguridad al cargar: una tarea que apunta a un estado o prioridad que
     * ya no existe NO se descarta. Se remapea al valor por defecto y el ID original
     * se guarda en [Task.extra], de modo que si la configuración vuelve a aparecer
     * se puede restaurar.
     */
    private fun normalize(task: Task, snapshot: TasklaneSnapshot): Task {
        val cfg = snapshot.config
        val knownState = cfg.state(task.stateId)
        val knownPriority = cfg.priority(task.priorityId)
        if (knownState != null && knownPriority != null) return task

        val extra = task.extra.toMutableMap()
        if (knownState == null) extra[ORIG_STATE] = task.stateId.value
        if (knownPriority == null) extra[ORIG_PRIORITY] = task.priorityId.value

        return task.copy(
            stateId = cfg.stateOrDefault(task.stateId).id,
            priorityId = cfg.priorityOrDefault(task.priorityId).id,
            extra = extra,
        )
    }

    private inline fun List<Task>.mapTask(
        id: com.tasklane.domain.model.TaskId,
        transform: (Task) -> Task,
    ): List<Task> = map { if (it.id == id) transform(it) else it }

    companion object {
        const val ORIG_STATE = "origStateId"
        const val ORIG_PRIORITY = "origPriorityId"
    }
}
