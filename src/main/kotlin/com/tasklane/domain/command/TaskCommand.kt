package com.tasklane.domain.command

import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import java.time.Instant
import java.util.Optional

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
        val tags: List<String> = emptyList(),
        val dueDate: Instant? = null,
        val anchors: List<CodeAnchor> = emptyList(),
    ) : RepoScoped

    data class UpdateBody(override val repo: RepoKey, val id: TaskId, val body: String) : RepoScoped

    /**
     * Lo que el diálogo de edición devuelve al aceptar: **todo a la vez**.
     *
     * Existe porque no hacerlo era caro de dos maneras distintas. `TaskEditDialog`
     * puede cambiar seis cosas de una tarea, y hasta la Fase 1 se enviaban seis
     * comandos seguidos desde el EDT. Eso son seis copias de la lista del repositorio
     * —a 100.000 tareas, 3,6 MB de basura por edición— y, peor, **seis snapshots**, o
     * sea hasta seis repintados del árbol por un solo clic en *Guardar*.
     *
     * Los comandos sueltos siguen existiendo y siguen haciendo falta: *Move To*,
     * el distintivo de prioridad y el marcador cambian **una** cosa, y para eso son.
     * Lo que no tenía sentido era componer una edición con ellos.
     *
     * Cada campo es opcional con el sentido de «no lo toques»: [dueDate] es
     * `Optional`-como-envoltorio y no un `Instant?` porque aquí `null` significaría
     * las dos cosas a la vez —«no lo toques» y «quítale la fecha»— y quitar una fecha
     * de vencimiento tiene que poder pedirse.
     */
    data class UpdateTask(
        override val repo: RepoKey,
        val id: TaskId,
        val body: String? = null,
        val stateId: StateId? = null,
        val priorityId: PriorityId? = null,
        val tags: List<String>? = null,
        val dueDate: Optional<Instant>? = null,
        val anchors: List<CodeAnchor>? = null,
    ) : RepoScoped

    data class ChangeState(override val repo: RepoKey, val id: TaskId, val stateId: StateId) : RepoScoped

    data class ChangePriority(override val repo: RepoKey, val id: TaskId, val priorityId: PriorityId) : RepoScoped

    /** Fija o quita la fecha de vencimiento. `null` la quita. */
    data class SetDueDate(override val repo: RepoKey, val id: TaskId, val dueDate: Instant?) : RepoScoped

    /** Alterna la marca de la tarea. */
    data class ToggleBookmark(override val repo: RepoKey, val id: TaskId) : RepoScoped

    /** Sustituye las etiquetas de una tarea por las indicadas. */
    data class SetTags(override val repo: RepoKey, val id: TaskId, val tags: List<String>) : RepoScoped

    /**
     * Sustituye las anclas de código. Sólo se quitan, nunca se añaden por aquí: se
     * capturan en el editor al crear la tarea, que es el único sitio que sabe dónde
     * estaba el cursor.
     */
    data class SetAnchors(
        override val repo: RepoKey,
        val id: TaskId,
        val anchors: List<CodeAnchor>,
    ) : RepoScoped

    /** Alterna entre el estado terminal y el estado por defecto. */
    data class ToggleComplete(override val repo: RepoKey, val id: TaskId) : RepoScoped

    data class Delete(override val repo: RepoKey, val ids: List<TaskId>) : RepoScoped

    /** Carga inicial desde disco. No es una edición del usuario, no ensucia el repo. */
    data class Loaded(override val repo: RepoKey, val tasks: List<Task>) : RepoScoped

    /**
     * Saca del modelo un repositorio entero. Lo emite «Exportar y quitar», y sólo
     * después de que sus tareas estén en el portapapeles.
     *
     * No es un `Delete` con todas las tareas: eso dejaría el repositorio en el
     * snapshot con la lista vacía, lo marcaría como sucio y volvería a escribir un
     * `tasks.xml` justo detrás de haberlo borrado del disco.
     */
    data class ForgetRepo(override val repo: RepoKey) : RepoScoped

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
     * El registro acaba de publicar un catálogo nuevo —arranque, evento de VCS o
     * cambio de profundidad—.
     *
     * Fija también qué repositorio queda activo: si el que estaba desapareció, se
     * cae al primero de la lista en vez de dejar el árbol apuntando a una clave que
     * ya no existe.
     */
    data class RepositoriesChanged(val repositories: List<RepositoryRef>) : TaskCommand

    /**
     * El usuario cambió de repositorio en el selector. No se valida contra el
     * catálogo a propósito: al abrir el proyecto se restaura la selección guardada
     * antes de que la detección haya terminado.
     */
    data class SelectRepo(val repo: RepoKey) : TaskCommand

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
