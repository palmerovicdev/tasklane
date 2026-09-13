package com.tasklane.domain.model

/** Cómo agrupa un estado sus tareas en la Tool Window. */
enum class Grouping { NONE, BY_DATE }

/** Qué fecha usa la agrupación de un estado. */
enum class DateAnchor { CREATED, UPDATED, COMPLETED }

data class TaskState(
    val id: StateId,
    val name: String,
    val order: Int,
    val grouping: Grouping = Grouping.NONE,
    val anchor: DateAnchor = DateAnchor.UPDATED,
    /** Al entrar en un estado terminal se marca `completedAt`. */
    val terminal: Boolean = false,
    /** Destino por defecto de las tareas nuevas. Debe haber exactamente uno. */
    val isDefault: Boolean = false,
)

data class TaskPriority(
    val id: PriorityId,
    val name: String,
    val order: Int,
    /** RGB. Se combinan en un JBColor para que el tema los resuelva solo. */
    val colorLight: Int,
    val colorDark: Int,
    /** Prefijo que selecciona esta prioridad al escribir. Fase 4. */
    val trigger: String? = null,
    val isDefault: Boolean = false,
)

/**
 * Configuración de fábrica.
 *
 * En la Fase 2 esto pasa a ser un `PersistentStateComponent` de proyecto editable
 * desde Settings; aquí es la semilla. Se aísla en el dominio a propósito: el resto
 * del código ya consume [TasklaneConfig], así que cambiar el origen no toca nada más.
 */
data class TasklaneConfig(
    val states: List<TaskState>,
    val priorities: List<TaskPriority>,
) {
    val defaultState: TaskState get() = states.firstOrNull { it.isDefault } ?: states.first()
    val defaultPriority: TaskPriority get() = priorities.firstOrNull { it.isDefault } ?: priorities.first()

    fun state(id: StateId): TaskState? = states.firstOrNull { it.id == id }
    fun priority(id: PriorityId): TaskPriority? = priorities.firstOrNull { it.id == id }

    /** Un estado que ya no existe no puede tumbar la carga: se remapea. Ver [TaskReducer]. */
    fun stateOrDefault(id: StateId): TaskState = state(id) ?: defaultState
    fun priorityOrDefault(id: PriorityId): TaskPriority = priority(id) ?: defaultPriority

    companion object {
        val TODO = StateId("s-todo")
        val DOING = StateId("s-doing")
        val DONE = StateId("s-done")

        val LOW = PriorityId("p-low")
        val NORMAL = PriorityId("p-normal")
        val HIGH = PriorityId("p-high")

        val DEFAULT = TasklaneConfig(
            states = listOf(
                TaskState(TODO, "ToDo", 0, isDefault = true),
                TaskState(DOING, "Doing", 1),
                TaskState(DONE, "Done", 2, Grouping.BY_DATE, DateAnchor.COMPLETED, terminal = true),
            ),
            priorities = listOf(
                TaskPriority(LOW, "Low", 0, 0x6C8EBF, 0x7FA8CC, trigger = "!"),
                TaskPriority(NORMAL, "Normal", 1, 0xB0700F, 0xDBA646, trigger = "!!", isDefault = true),
                TaskPriority(HIGH, "High", 2, 0xB3392C, 0xE2705F, trigger = "!!!"),
            ),
        )
    }
}
