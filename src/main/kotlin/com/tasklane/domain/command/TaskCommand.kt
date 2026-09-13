package com.tasklane.domain.command

import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId

/**
 * Todas las mutaciones posibles del modelo. `sealed` a propósito: añadir un caso
 * nuevo rompe la compilación del reducer en vez de pasar desapercibido.
 */
sealed interface TaskCommand {
    val repo: RepoKey

    data class Create(
        override val repo: RepoKey,
        val body: String,
        val stateId: StateId? = null,
        val priorityId: PriorityId? = null,
    ) : TaskCommand

    data class UpdateBody(override val repo: RepoKey, val id: TaskId, val body: String) : TaskCommand

    data class ChangeState(override val repo: RepoKey, val id: TaskId, val stateId: StateId) : TaskCommand

    data class ChangePriority(override val repo: RepoKey, val id: TaskId, val priorityId: PriorityId) : TaskCommand

    /** Alterna entre el estado terminal y el estado por defecto. */
    data class ToggleComplete(override val repo: RepoKey, val id: TaskId) : TaskCommand

    data class Delete(override val repo: RepoKey, val ids: List<TaskId>) : TaskCommand

    /** Carga inicial desde disco. No es una edición del usuario, no marca el repo como sucio. */
    data class Loaded(override val repo: RepoKey, val tasks: List<Task>) : TaskCommand
}
