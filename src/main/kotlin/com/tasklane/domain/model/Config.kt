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
 * Estados y prioridades del proyecto.
 *
 * El dominio sólo conoce este tipo inmutable. Desde la Fase 2 su origen es
 * `TasklaneConfigService`, un `PersistentStateComponent` que la plataforma escribe
 * en `.idea/tasklane.xml`; [DEFAULT] queda como semilla de fábrica. Que el resto del
 * código consuma siempre [TasklaneConfig] es lo que hizo que cambiar el origen no
 * tocara ni el reducer ni la UI.
 */
data class TasklaneConfig(
    val states: List<TaskState>,
    val priorities: List<TaskPriority>,
    /** Interruptor general de los triggers de prioridad. Fase 4. */
    val triggersEnabled: Boolean = true,
    /**
     * Hasta qué profundidad se buscan repositorios bajo la raíz del proyecto. 1 =
     * hijos directos, que es lo que pide el requisito. Es un filtro de **vista**:
     * un repositorio más profundo que ya tenga tareas nunca se oculta, aparece bajo
     * «Other». Ver `RepoCatalog`.
     */
    val repoDepth: Int = DEFAULT_REPO_DEPTH,
) {
    val defaultState: TaskState get() = states.firstOrNull { it.isDefault } ?: states.first()
    val defaultPriority: TaskPriority get() = priorities.firstOrNull { it.isDefault } ?: priorities.first()

    fun state(id: StateId): TaskState? = states.firstOrNull { it.id == id }
    fun priority(id: PriorityId): TaskPriority? = priorities.firstOrNull { it.id == id }

    /** Un estado que ya no existe no puede tumbar la carga: se remapea. Ver `TaskReducer`. */
    fun stateOrDefault(id: StateId): TaskState = state(id) ?: defaultState
    fun priorityOrDefault(id: PriorityId): TaskPriority = priority(id) ?: defaultPriority

    /**
     * Reafirma las invariantes que la UI y el fichero podrían haber roto: `order`
     * coincide con la posición, y hay exactamente un elemento por defecto.
     *
     * Se aplica al salir de los ajustes y al leer de disco, de modo que ningún
     * consumidor tenga que preguntarse si lo que recibe está bien formado.
     */
    fun normalized(): TasklaneConfig {
        val defaultStateIndex = states.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        val defaultPriorityIndex = priorities.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        return copy(
            repoDepth = repoDepth.coerceIn(0, MAX_REPO_DEPTH),
            states = states.mapIndexed { i, s -> s.copy(order = i, isDefault = i == defaultStateIndex) },
            priorities = priorities.mapIndexed { i, p ->
                p.copy(
                    order = i,
                    isDefault = i == defaultPriorityIndex,
                    trigger = p.trigger?.trim()?.takeIf(String::isNotEmpty),
                )
            },
        )
    }

    companion object {
        /** Hijos directos de la raíz: lo que pide el requisito. */
        const val DEFAULT_REPO_DEPTH = 1

        /** Más allá de esto el selector deja de ser un selector. */
        const val MAX_REPO_DEPTH = 5

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
