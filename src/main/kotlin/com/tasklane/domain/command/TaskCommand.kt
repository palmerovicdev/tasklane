package com.tasklane.domain.command

import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig

/**
 * Todas las mutaciones posibles del modelo. `sealed` a propósito: añadir un caso
 * nuevo rompe la compilación del reducer en vez de pasar desapercibido.
 *
 * Se divide en dos familias porque desde la Fase 2 no todo cabe en un repositorio:
 * cambiar la configuración o reasignar un estado que se borra afectan a **todos**
 * los repositorios cargados a la vez. Sacar `repo` de la raíz obliga a que cada
 * comando diga a qué alcance pertenece, y es lo que le permite al servicio saber
 * qué ficheros tiene que reescribir sin adivinarlo.
 */
sealed interface TaskCommand {

    // ------------------------------------------------------ acotados a un repo

    data class Create(
        override val repo: RepoKey,
        val body: String,
        val stateId: StateId? = null,
        val priorityId: PriorityId? = null,
    ) : RepoScoped

    data class UpdateBody(override val repo: RepoKey, val id: TaskId, val body: String) : RepoScoped

    data class ChangeState(override val repo: RepoKey, val id: TaskId, val stateId: StateId) : RepoScoped

    data class ChangePriority(override val repo: RepoKey, val id: TaskId, val priorityId: PriorityId) : RepoScoped

    /** Alterna entre el estado terminal y el estado por defecto. */
    data class ToggleComplete(override val repo: RepoKey, val id: TaskId) : RepoScoped

    data class Delete(override val repo: RepoKey, val ids: List<TaskId>) : RepoScoped

    /** Carga inicial desde disco. No es una edición del usuario, no ensucia el repo. */
    data class Loaded(override val repo: RepoKey, val tasks: List<Task>) : RepoScoped

    // ----------------------------------------------------- alcance de proyecto

    /**
     * Entró una configuración nueva —del fichero, de los ajustes o de la plantilla—.
     * Re-normaliza todas las tareas: las huérfanas se remapean y las que vuelven a
     * tener su estado disponible lo recuperan.
     */
    data class ConfigChanged(val config: TasklaneConfig) : TaskCommand

    /**
     * Mueve a [to] todo lo que estaba en [from]. Es el comando que respalda el
     * diálogo de borrado de un estado: la reasignación es explícita y del usuario,
     * no el remapeo automático al estado por defecto.
     */
    data class ReassignState(val from: StateId, val to: StateId) : TaskCommand

    data class ReassignPriority(val from: PriorityId, val to: PriorityId) : TaskCommand

    /**
     * Rellena `completedAt` desde `updatedAt` en las tareas de [states] que aún no
     * lo tengan. Se emite una sola vez, cuando el usuario marca como terminal un
     * estado que ya tenía tareas dentro y acepta el ofrecimiento.
     */
    data class BackfillCompletedAt(val states: Set<StateId>) : TaskCommand
}

/** Comando acotado a un único repositorio. */
sealed interface RepoScoped : TaskCommand {
    val repo: RepoKey
}
