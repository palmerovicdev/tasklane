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
) {
    val activeTasks: List<Task> get() = tasksByRepo[activeRepo].orEmpty()

    val activeRepository: RepositoryRef? get() = repositories.firstOrNull { it.key == activeRepo }

    fun tasksOf(repo: RepoKey): List<Task> = tasksByRepo[repo].orEmpty()

    fun task(id: TaskId): Task? = tasksByRepo.values.asSequence().flatten().firstOrNull { it.id == id }

    /** Siguiente hueco de orden. Disperso para poder insertar entre dos sin renumerar. */
    fun nextOrder(repo: RepoKey): Long = (tasksOf(repo).maxOfOrNull { it.order } ?: 0L) + ORDER_GAP

    companion object {
        const val ORDER_GAP = 1000L

        val EMPTY = TasklaneSnapshot(
            config = TasklaneConfig.DEFAULT,
            tasksByRepo = emptyMap(),
            activeRepo = RepoKey.ROOT,
        )
    }
}
