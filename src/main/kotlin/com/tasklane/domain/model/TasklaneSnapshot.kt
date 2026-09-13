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
    /** Tareas por repositorio. En la Fase 1 solo hay [RepoKey.ROOT]. */
    val tasksByRepo: Map<RepoKey, List<Task>>,
    val activeRepo: RepoKey,
    /** Repos cuyo fichero aún no se ha leído. Evita repintar como si estuvieran vacíos. */
    val loading: Set<RepoKey> = emptySet(),
) {
    val activeTasks: List<Task> get() = tasksByRepo[activeRepo].orEmpty()

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
