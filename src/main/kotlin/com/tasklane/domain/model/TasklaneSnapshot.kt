package com.tasklane.domain.model

/**
 * Estado completo e inmutable que consume la UI.
 *
 * La UI nunca lee del almacén ni muta tareas: recibe un snapshot y lo pinta. Eso
 * elimina toda una clase de bugs de desincronización, y hace que el reducer sea
 * testeable sin arrancar un IDE.
 */
data class TasklaneSnapshot(
    val config: TasklaneConfig,
    /** Tareas por repositorio. Sólo están los que ya se han leído de disco. */
    val tasksByRepo: Map<RepoKey, List<Task>>,
    val activeRepo: RepoKey,
    /**
     * Los repositorios de la ventana, ya filtrados y ordenados por `RepoCatalog`.
     * Están en el snapshot y no en un servicio aparte porque la UI los pinta junto
     * a las tareas: dos fuentes distintas darían un selector desincronizado del
     * árbol durante un repintado.
     */
    val repositories: List<RepositoryRef> = emptyList(),
    /** Repos cuyo fichero aún no se ha leído. Evita repintar como si estuvieran vacíos. */
    val loading: Set<RepoKey> = emptySet(),
    /**
     * El `order` más alto de cada repositorio, que es lo único que [nextOrder]
     * necesita saber.
     *
     * Lo mantiene [com.tasklane.domain.command.TaskReducer]: lo calcula al cargar
     * —que es el único momento en que ya se recorre la lista entera de todos modos— y
     * lo sube al crear, que es el único comando que mueve el techo. Antes `nextOrder`
     * hacía un `maxOfOrNull` sobre el repositorio entero **cada vez que se creaba una
     * tarea**; con 100.000 eso era medio milisegundo de EDT por cada alta.
     *
     * Borrar no lo baja, y no hace falta que lo baje: los huecos del orden disperso
     * son justamente lo que permite insertar entre dos vecinos sin renumerar.
     */
    val maxOrder: Map<RepoKey, Long> = emptyMap(),
) {
    val activeTasks: List<Task> get() = tasksByRepo[activeRepo].orEmpty()

    val activeRepository: RepositoryRef? get() = repositories.firstOrNull { it.key == activeRepo }

    fun tasksOf(repo: RepoKey): List<Task> = tasksByRepo[repo].orEmpty()

    /**
     * Índice por id de **todas** las tareas del snapshot.
     *
     * `lazy` y no un campo, y la diferencia importa: construirlo cuesta O(n), y si
     * fuera un campo se pagaría en **cada** snapshot —o sea, en cada pulsación de
     * tecla— para atender una búsqueda por id que casi ningún comando hace. En
     * producción sólo hay un sitio que pregunte por id: `TasklanePanel.waitFor`, al
     * pulsar una marca del editor. Así el coste lo paga quien pregunta, y sólo la
     * primera vez.
     *
     * `PUBLICATION` como el resto de los `lazy` del modelo: el snapshot lo leen el EDT
     * y las corrutinas de fondo a la vez, y aquí calcularlo dos veces es inocuo
     * —da el mismo mapa— mientras que un cerrojo sería un candado en el camino de
     * pintado.
     */
    private val byId: Map<TaskId, Task> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val index = HashMap<TaskId, Task>(tasksByRepo.values.sumOf { it.size })
        for (tasks in tasksByRepo.values) for (task in tasks) index[task.id] = task
        index
    }

    fun task(id: TaskId): Task? = byId[id]

    /**
     * Siguiente hueco de orden. Disperso para poder insertar entre dos sin renumerar.
     *
     * O(1): sale de [maxOrder], que el reducer mantiene al día.
     */
    fun nextOrder(repo: RepoKey): Long = (maxOrder[repo] ?: 0L) + ORDER_GAP

    companion object {
        const val ORDER_GAP = 1000L

        val EMPTY = TasklaneSnapshot(
            config = TasklaneConfig.DEFAULT,
            tasksByRepo = emptyMap(),
            activeRepo = RepoKey.ROOT,
        )
    }
}
