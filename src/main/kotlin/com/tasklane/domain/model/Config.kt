package com.tasklane.domain.model

/** Cómo agrupa un estado sus tareas en la Tool Window. */
/**
 * Cómo se parte en bloques la lista de un estado.
 *
 * Sólo [BY_DATE] usa [DateAnchor]; el resto ignoran ese ajuste. Una tarea con varias
 * etiquetas aparece en [BY_TAG] bajo **todas** las suyas: agrupar por etiqueta sirve
 * para ver de un vistazo todo lo de una, y esconderla en la primera por orden
 * alfabético haría justo lo contrario.
 */
enum class Grouping { NONE, BY_DATE, BY_PRIORITY, BY_TAG }

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
    /**
     * A partir de cuántos megabytes de imágenes se avisa, o `0` para no avisar nunca.
     *
     * Es la cuota con aviso del §4.5 del plan de escala, y **es un aviso y no un tope**:
     * nada se rechaza al cruzarlo y nada se borra. La política la decide
     * [com.tasklane.data.attachment.AttachmentQuota]; esto es sólo el número.
     */
    val imageQuotaMegabytes: Int = DEFAULT_IMAGE_QUOTA_MB,
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
            // El cero es «apagado» y tiene que sobrevivir a la normalización: acotarlo
            // al mínimo convertiría «no me avises» en «avísame a partir de un giga».
            imageQuotaMegabytes = if (imageQuotaMegabytes <= NO_IMAGE_QUOTA) NO_IMAGE_QUOTA
            else imageQuotaMegabytes.coerceIn(MIN_IMAGE_QUOTA_MB, MAX_IMAGE_QUOTA_MB),
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

        /**
         * El umbral de aviso por defecto: 5 GB **por repositorio**, el valor del §4.5.
         *
         * Desde la 2.3 las capturas se guardan a su tamaño —una de 2880 px en PNG ronda
         * el mega y cuarto—, así que cinco gigas son unas cuatro mil. Por debajo de eso,
         * avisar sería ruido.
         */
        const val DEFAULT_IMAGE_QUOTA_MB = 5 * 1024

        /** Con esto se apaga el aviso. Quien sabe lo que hace no necesita que se lo recuerden. */
        const val NO_IMAGE_QUOTA = 0

        /** Por debajo de un giga el aviso sería ruido; por encima de un tera ya no es un aviso. */
        const val MIN_IMAGE_QUOTA_MB = 1024
        const val MAX_IMAGE_QUOTA_MB = 1024 * 1024

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
