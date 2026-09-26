package com.tasklane.domain

import com.tasklane.data.sqlite.TaskStore
import com.tasklane.domain.command.Change
import com.tasklane.domain.command.RepoScoped
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot

/**
 * El almacén de mentira con el que se prueba el reducer.
 *
 * Hasta la Fase 2 el reducer era `(Snapshot, Command) -> Snapshot` y los tests podían
 * encadenar comandos directamente. Ahora es `(Subject, Command) -> Mutations`, que es lo
 * que permite que un comando cueste lo que cuesta el comando y no lo que pesa el
 * proyecto — pero un test que sólo mire las mutaciones no puede encadenar dos comandos
 * sobre la misma tarea.
 *
 * [Model] cierra ese hueco: una lista de tareas y una configuración, con
 * `TaskReducer.applyTo` aplicando por encima. Se prueba lo mismo que antes y se sigue
 * pudiendo escribir `crear → editar → completar`.
 *
 * **Sólo entiende las mutaciones que llevan filas.** Lo de alcance de proyecto
 * —reasignar un estado, rellenar `completedAt`, aparcar lo que apunta a configuración
 * que ya no existe— no se simula aquí: su semántica vive en SQL y se prueba contra
 * SQLite de verdad en `TaskStoreTest`. Simularla en Kotlin sería mantener dos
 * implementaciones de la misma regla y descubrir que divergieron el día que un usuario
 * borre un estado.
 */
internal class Model(
    val config: TasklaneConfig = TasklaneConfig.DEFAULT.normalized(),
    val tasks: List<Task> = emptyList(),
    val snapshot: TasklaneSnapshot = TasklaneSnapshot.EMPTY.copy(config = config),
) {
    /** Lo que se vería en la pestaña: las del repositorio activo. */
    val activeTasks: List<Task> get() = tasksOf(snapshot.activeRepo)

    fun tasksOf(repo: RepoKey): List<Task> = tasks.filter { it.repo == repo }

    fun task(id: TaskId): Task? = tasks.firstOrNull { it.id == id }

    val repositories get() = snapshot.repositories
    val activeRepo get() = snapshot.activeRepo
    val loading get() = snapshot.loading
}

/**
 * Un comando aplicado al modelo de mentira. Devuelve el **mismo** [Model] cuando no
 * cambió nada, que es lo que deja escribir `assertSame` sobre «esto no hizo nada».
 */
internal fun TaskReducer.step(model: Model, command: TaskCommand): Model = recorded(model, command).first

/**
 * Como [step], y además lo que la pila de `⌘Z` guardaría del comando (2.16.0): las tareas
 * que nombraba antes, contra las mutaciones. Es lo mismo que hace el servicio dentro de la
 * transacción, sin las vecinas de un reordenar, que las sabe el almacén.
 */
internal fun TaskReducer.recorded(model: Model, command: TaskCommand): Pair<Model, Change> {
    val targets = targetsOf(command).toSet()
    val repo = (command as? RepoScoped)?.repo ?: model.snapshot.activeRepo
    val subject = TaskReducer.Subject(
        config = model.config,
        tasks = model.tasks.filter { it.id in targets },
        nextOrder = { (model.tasksOf(repo).maxOfOrNull { it.order } ?: 0L) + TaskStore.ORDER_GAP },
    )
    val plan = plan(subject, command)
    val change = Change.of(subject.tasks, plan.mutations)
    val view = view(model.snapshot, command)

    // `ForgetRepo` es la única cuya mutación no lleva filas y sí tiene efecto aquí: se
    // lleva las tareas del repositorio, que es lo que el almacén hace con un `DELETE`.
    val tasks = when (command) {
        // La MISMA lista si no había nada suyo: `filterNot` siempre construye una
        // nueva, y una lista nueva aquí haría fallar el «esto no hizo nada».
        is TaskCommand.ForgetRepo ->
            model.tasks.filterNot { it.repo == command.repo }.takeIf { it.size != model.tasks.size } ?: model.tasks

        else -> TaskReducer.applyTo(model.tasks, plan.mutations)
    }

    if (tasks === model.tasks && view === model.snapshot && plan.config == model.config) return model to change
    return Model(plan.config, tasks, view.copy(config = plan.config)) to change
}
