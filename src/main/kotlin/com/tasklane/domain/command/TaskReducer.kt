package com.tasklane.domain.command

import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.LinkExtractor
import java.time.Clock
import java.time.Instant

/**
 * `(config, las tareas que el comando nombra, comando) -> qué hay que cambiar`.
 *
 * Aquí viven las invariantes del modelo y nada más: sin IO, sin Swing, sin `Project`.
 * Sigue siendo la clase que concentra la lógica y la única que hace falta testear para
 * confiar en el comportamiento.
 *
 * ## Lo que cambió en la Fase 3, y por qué
 *
 * Hasta la Fase 2 la firma era `(Snapshot, Command) -> Snapshot`: el reducer recibía
 * **el modelo entero** y devolvía otro modelo entero. Eso era correcto mientras el
 * modelo cupiera en memoria, y dejó de serlo: a un millón de tareas el snapshot son
 * 12,9 GB y copiar la lista de un repositorio por cada pulsación del marcador es O(n)
 * por gesto.
 *
 * Ahora recibe [Subject] —la configuración y **las tareas que el comando nombra**, que
 * son una o un puñado— y devuelve [Mutation]s. Lo que no cabe fila a fila —reasignar un
 * estado con un millón de tareas dentro— no se materializa: se describe, y el almacén
 * lo resuelve con un `UPDATE … WHERE`.
 *
 * Lo que **no** cambió es todo lo demás: sellar `completedAt` al entrar en un estado
 * terminal y conservarlo al salir, que marcar no toque `updatedAt`, que una tarea vacía
 * no sea una tarea, que las etiquetas y las anclas se limpien y se deduplican, y que un
 * comando que no cambia nada no produzca nada. Esas reglas están donde estaban y se
 * prueban como se probaban.
 *
 * ## Dónde fue a parar `normalize`
 *
 * La red de seguridad ante configuración desincronizada —aparcar en el estado por
 * defecto lo que apunta a configuración que ya no existe, y devolverlo cuando vuelve—
 * ya no se aplica tarea a tarea al cargar: cargar ya no existe. Vive en el almacén, que
 * la resuelve por claves —[Mutation.Renormalize]— apoyándose en un índice parcial de
 * las tareas aparcadas. La regla es la misma; lo que cambia es que cuesta lo que cuestan
 * las aparcadas y no lo que cuesta el proyecto.
 */
class TaskReducer(private val clock: Clock = Clock.systemUTC()) {

    /**
     * Lo que un comando necesita además de sí mismo.
     *
     * [tasks] son **sólo** las que el comando nombra —[targetsOf] dice cuáles—, ya
     * leídas del almacén. [nextOrder] es una función y no un valor porque sólo
     * [TaskCommand.Create] lo pide, y calcularlo es un salto de índice que no hay que
     * pagar en los demás comandos.
     */
    class Subject(
        val config: TasklaneConfig,
        val tasks: List<Task> = emptyList(),
        val nextOrder: () -> Long = { 0L },
    ) {
        fun task(id: TaskId): Task? = tasks.firstOrNull { it.id == id }
    }

    /**
     * Lo que produce un comando: qué cambiar y con qué configuración.
     *
     * [config] sólo se mueve con [TaskCommand.ConfigChanged], y va aquí porque el
     * almacén necesita la configuración **nueva** para recalcular lo que guarda
     * derivado de ella, mientras que [Mutation.Renormalize] lleva la **anterior** para
     * poder tocar sólo lo que cambió.
     */
    data class Plan(val mutations: List<Mutation>, val config: TasklaneConfig) {
        val isEmpty: Boolean get() = mutations.isEmpty()
    }

    fun plan(subject: Subject, command: TaskCommand): Plan {
        val now = Instant.now(clock)
        val config = subject.config

        return when (command) {
            // ------------------------------------------------ alcance proyecto

            is TaskCommand.ConfigChanged -> {
                val next = command.config.normalized()
                // `ConfigChanged` se emite en cada arranque y en cada paso por los
                // ajustes, y casi siempre no cambia nada.
                if (next == config) Plan(emptyList(), config)
                else Plan(listOf(Mutation.Renormalize(config)), next)
            }

            is TaskCommand.ReassignState ->
                if (command.from == command.to) nothing(config)
                else Plan(listOf(Mutation.Reassign(command.from, command.to, now)), config)

            is TaskCommand.ReassignPriority ->
                if (command.from == command.to) nothing(config)
                else Plan(listOf(Mutation.Reprioritize(command.from, command.to, now)), config)

            is TaskCommand.BackfillCompletedAt ->
                if (command.states.isEmpty()) nothing(config)
                else Plan(listOf(Mutation.Backfill(command.states)), config)

            // El catálogo y el repositorio activo son estado de la vista: no tocan
            // ninguna fila. Ver `TaskReducer.view`.
            is TaskCommand.RepositoriesChanged, is TaskCommand.SelectRepo -> nothing(config)

            // ----------------------------------------------------- acotados

            is TaskCommand.ForgetRepo -> Plan(listOf(Mutation.Forget(command.repo)), config)

            is TaskCommand.Create -> {
                val body = command.body.trim()
                // Una tarea vacía no es una tarea: se ignora en vez de crear ruido.
                if (body.isEmpty()) return nothing(config)
                val state = command.stateId?.let { config.stateOrDefault(it) } ?: config.defaultState
                val priority = command.priorityId?.let { config.priorityOrDefault(it) } ?: config.defaultPriority
                upsert(
                    config,
                    Task(
                        id = TaskId.random(),
                        repo = command.repo,
                        body = body,
                        links = LinkExtractor.extract(body),
                        attachments = ImageRefParser.parse(body),
                        stateId = state.id,
                        priorityId = priority.id,
                        createdAt = now,
                        updatedAt = now,
                        completedAt = if (state.terminal) now else null,
                        order = subject.nextOrder(),
                        tags = clean(command.tags),
                        dueDate = command.dueDate,
                        anchors = command.anchors.distinct(),
                    ),
                )
            }

            is TaskCommand.Batch -> batch(subject, command)

            is TaskCommand.Delete -> {
                // Sólo las que existían. Antes esto se notaba en que borrar un id
                // inexistente reconstruía la lista igualmente: un repintado y un volcado
                // a disco por un borrado que no borraba nada.
                //
                // El conjunto se construye UNA vez. Estaba dentro del `filter`, o sea se
                // reconstruía por cada tarea: borrar diez mil filas seleccionadas eran
                // cien millones de inserciones en un `HashSet`. Con una fila no se nota,
                // y la Fase 5 es la que borra selecciones enteras.
                val wanted = command.ids.toHashSet()
                val ids = subject.tasks.map { it.id }.filter { it in wanted }
                if (ids.isEmpty()) nothing(config) else Plan(listOf(Mutation.Delete(ids)), config)
            }

            is TaskCommand.UpdateBody -> edit(subject, command.id) { task ->
                val body = command.body.trim()
                if (body.isEmpty() || body == task.body) task
                else withDerived(task.copy(body = body, updatedAt = now))
            }

            // Los seis cambios del diálogo a la vez. Ver el KDoc de [TaskCommand.UpdateTask].
            is TaskCommand.UpdateTask -> edit(subject, command.id) { task ->
                var next = task
                command.body?.trim()?.takeIf { it.isNotEmpty() && it != next.body }?.let {
                    next = withDerived(next.copy(body = it))
                }
                command.stateId?.let { next = applyState(next, it, config, now) }
                command.priorityId?.takeIf { it != next.priorityId }?.let {
                    // Igual que ChangePriority: elegir a mano borra la marca que haría
                    // volver sola a una tarea aparcada.
                    next = next.copy(priorityId = it, extra = next.extra - ORIG_PRIORITY)
                }
                clean(command.tags).takeIf { command.tags != null && it != next.tags }?.let {
                    next = next.copy(tags = it)
                }
                if (command.dueDate != null) {
                    // `Optional.empty` es «quítasela», que es distinto de «no la toques».
                    val due = command.dueDate.orElse(null)
                    if (due != next.dueDate) next = next.copy(dueDate = due)
                }
                command.anchors?.distinct()?.takeIf { it != next.anchors }?.let {
                    next = next.copy(anchors = it)
                }
                // `updatedAt` una sola vez y sólo si algo cambió de verdad: seis
                // comandos seguidos la tocaban seis veces, y un diálogo aceptado sin
                // tocar nada la tocaba igual.
                if (next === task) task else next.copy(updatedAt = now)
            }

            is TaskCommand.ChangeState -> edit(subject, command.id) { applyState(it, command.stateId, config, now) }

            is TaskCommand.ChangePriority -> edit(subject, command.id) { task ->
                if (task.priorityId == command.priorityId) task
                // Mover a mano es una decisión: deja de ser una huérfana aparcada
                // y por eso pierde la marca que la haría volver sola.
                else task.copy(
                    priorityId = command.priorityId,
                    updatedAt = now,
                    extra = task.extra - ORIG_PRIORITY,
                )
            }

            is TaskCommand.SetAnchors -> edit(subject, command.id) { task ->
                val anchors = command.anchors.distinct()
                if (task.anchors == anchors) task else task.copy(anchors = anchors, updatedAt = now)
            }

            is TaskCommand.SetDueDate -> edit(subject, command.id) { task ->
                if (task.dueDate == command.dueDate) task
                else task.copy(dueDate = command.dueDate, updatedAt = now)
            }

            is TaskCommand.ToggleBookmark -> edit(subject, command.id) { task ->
                // Marcar no es editar: no toca `updatedAt`. Si lo tocara, marcar una
                // tarea vieja para no perderla de vista la movería al grupo de hoy,
                // que es justo lo contrario de lo que el usuario pidió.
                task.copy(bookmarked = !task.bookmarked)
            }

            is TaskCommand.SetTags -> edit(subject, command.id) { task ->
                val tags = clean(command.tags)
                if (tags == task.tags) task else task.copy(tags = tags, updatedAt = now)
            }

            is TaskCommand.ToggleComplete -> edit(subject, command.id) { task ->
                val isDone = config.stateOrDefault(task.stateId).terminal
                val target = if (isDone) {
                    config.defaultState.id
                } else {
                    (config.states.firstOrNull { it.terminal } ?: config.defaultState).id
                }
                applyState(task, target, config, now)
            }
        }
    }

    /**
     * La mitad del comando que sólo cambia la **vista**: qué repositorios hay, cuál está
     * activo y qué configuración se está pintando. Ninguna de las tres toca una fila.
     *
     * Devuelve el **mismo** snapshot cuando no cambia nada, y eso no es una
     * optimización: es lo que impide que un evento de VCS repetido —llegan repetidos— o
     * el `ConfigChanged` de cada arranque provoquen un repintado.
     */
    fun view(snapshot: TasklaneSnapshot, command: TaskCommand): TasklaneSnapshot = when (command) {
        is TaskCommand.ConfigChanged -> {
            val config = command.config.normalized()
            if (config == snapshot.config) snapshot else snapshot.copy(config = config)
        }

        is TaskCommand.RepositoriesChanged -> {
            val repos = command.repositories
            // Que el activo sobreviva es la invariante: perderlo dejaría el árbol
            // pintando un repositorio que ya no está en la lista.
            val active = when {
                repos.isEmpty() -> snapshot.activeRepo
                repos.any { it.key == snapshot.activeRepo } -> snapshot.activeRepo
                else -> repos.first().key
            }
            if (repos == snapshot.repositories && active == snapshot.activeRepo) snapshot
            else snapshot.copy(repositories = repos, activeRepo = active)
        }

        // No se valida contra el catálogo a propósito: al abrir el proyecto se restaura
        // la selección guardada antes de que la detección haya terminado.
        is TaskCommand.SelectRepo ->
            if (command.repo == snapshot.activeRepo) snapshot else snapshot.copy(activeRepo = command.repo)

        is TaskCommand.ForgetRepo -> {
            val remaining = snapshot.repositories.filterNot { it.key == command.repo }
            if (remaining.size == snapshot.repositories.size && command.repo !in snapshot.loading) {
                snapshot
            } else {
                snapshot.copy(
                    repositories = remaining,
                    activeRepo = if (snapshot.activeRepo != command.repo) snapshot.activeRepo
                    else remaining.firstOrNull()?.key ?: snapshot.activeRepo,
                    loading = snapshot.loading - command.repo,
                )
            }
        }

        else -> snapshot
    }

    /**
     * Qué tareas hay que leer del almacén antes de planificar. Es la otra mitad del
     * cambio de alcance: el servicio carga **esto** y no el corpus.
     */
    fun targetsOf(command: TaskCommand): List<TaskId> = when (command) {
        is TaskCommand.Delete -> command.ids
        is TaskCommand.UpdateBody -> listOf(command.id)
        is TaskCommand.UpdateTask -> listOf(command.id)
        is TaskCommand.ChangeState -> listOf(command.id)
        is TaskCommand.ChangePriority -> listOf(command.id)
        is TaskCommand.SetAnchors -> listOf(command.id)
        is TaskCommand.SetDueDate -> listOf(command.id)
        is TaskCommand.ToggleBookmark -> listOf(command.id)
        is TaskCommand.SetTags -> listOf(command.id)
        is TaskCommand.ToggleComplete -> listOf(command.id)
        is TaskCommand.Batch -> command.commands.flatMapTo(LinkedHashSet()) { targetsOf(it) }.toList()
        else -> emptyList()
    }

    // ------------------------------------------------------------------- privado

    private fun nothing(config: TasklaneConfig) = Plan(emptyList(), config)

    /**
     * Los comandos de un [TaskCommand.Batch], uno detrás de otro, sobre **las tareas
     * como las dejó el anterior**.
     *
     * Eso es lo que hace que el lote sea la misma cosa que los comandos sueltos: si la
     * misma tarea sale dos veces —marcar y después completar—, el segundo ve la tarea ya
     * marcada, igual que la vería leyéndola del almacén. Lo que cambia es que al final
     * sale **una** mutación por tipo y no una por comando.
     *
     * Cada comando se planifica con su propio [Subject] de una tarea. No es por elegancia:
     * `Subject.task(id)` busca recorriendo, y con el lote entero dentro serían diez mil
     * recorridos de diez mil tareas.
     */
    private fun batch(subject: Subject, command: TaskCommand.Batch): Plan {
        val config = subject.config
        val current = HashMap<TaskId, Task>(subject.tasks.size * 2)
        subject.tasks.associateByTo(current) { it.id }
        val changed = LinkedHashSet<TaskId>()
        val deleted = LinkedHashSet<TaskId>()

        for (sub in command.commands) {
            val mine = targetsOf(sub).mapNotNull { current[it] }
            if (mine.isEmpty()) continue
            for (mutation in plan(Subject(config, mine, subject.nextOrder), sub).mutations) {
                when (mutation) {
                    is Mutation.Upsert -> mutation.tasks.forEach {
                        current[it.id] = it
                        changed += it.id
                    }

                    is Mutation.Delete -> mutation.ids.forEach {
                        current -= it
                        changed -= it
                        deleted += it
                    }

                    // Ningún comando sobre tareas existentes describe un cambio de
                    // proyecto; si alguno empezara a hacerlo, un lote que lo tragara en
                    // silencio sería un cambio que no llega al disco.
                    else -> error("Batch no sabe componer $mutation")
                }
            }
        }

        val mutations = buildList {
            if (changed.isNotEmpty()) add(Mutation.Upsert(changed.map { current.getValue(it) }))
            if (deleted.isNotEmpty()) add(Mutation.Delete(deleted.toList()))
        }
        return Plan(mutations, config)
    }

    private fun upsert(config: TasklaneConfig, task: Task) =
        Plan(listOf(Mutation.Upsert(listOf(task))), config)

    /**
     * Aplica [transform] a la tarea que el comando nombra.
     *
     * Devolver la **misma** instancia significa «no ha cambiado nada», y entonces no
     * sale ninguna mutación: ni escritura, ni repintado. Es el mismo contrato de
     * identidad que la Fase 1 introdujo, y sigue costando un puntero.
     */
    private inline fun edit(subject: Subject, id: TaskId, transform: (Task) -> Task): Plan {
        val task = subject.task(id) ?: return nothing(subject.config)
        val next = transform(task)
        return if (next === task) nothing(subject.config) else upsert(subject.config, next)
    }

    /** Etiquetas como las guarda el modelo: sin espacios, sin vacías y sin repetidas. */
    private fun clean(tags: List<String>?): List<String> =
        tags.orEmpty().map(String::trim).filter(String::isNotEmpty).distinct()

    /**
     * Recalcula lo que se deriva del cuerpo: enlaces e imágenes.
     *
     * Se invoca **sólo donde el cuerpo entra o cambia** —crear y editar— y no en los
     * comandos que mueven estado o prioridad: ninguno de ellos puede tocar el texto.
     * Ver `docs/architecture.html` §9.
     */
    private fun withDerived(task: Task): Task {
        val links = LinkExtractor.extract(task.body)
        val attachments = ImageRefParser.parse(task.body)
        return if (links == task.links && attachments == task.attachments) task
        else task.copy(links = links, attachments = attachments)
    }

    private fun applyState(
        task: Task,
        target: StateId,
        config: TasklaneConfig,
        now: Instant,
    ): Task {
        if (task.stateId == target) return task
        val state = config.stateOrDefault(target)
        return task.copy(
            stateId = state.id,
            updatedAt = now,
            // Entrar en terminal sella la fecha de finalización; salir la conserva.
            // Conservarla no es un descuido: es lo que hace estable la agrupación
            // histórica si el usuario reabre y vuelve a cerrar una tarea.
            completedAt = if (state.terminal) task.completedAt ?: now else task.completedAt,
            extra = task.extra - ORIG_STATE,
        )
    }

    companion object {
        const val ORIG_STATE = "origStateId"
        const val ORIG_PRIORITY = "origPriorityId"

        /** Tareas aparcadas en el fallback por configuración ausente. */
        fun orphans(tasks: List<Task>): List<Task> =
            tasks.filter { ORIG_STATE in it.extra || ORIG_PRIORITY in it.extra }

        /**
         * Aplica unas mutaciones a una lista de tareas.
         *
         * Existe para los tests y el banco: es el almacén de mentira que permite probar
         * el reducer sin una base de datos, igual que antes se probaba con un snapshot.
         * **Sólo entiende las mutaciones que llevan filas** —[Mutation.Upsert] y
         * [Mutation.Delete]—; las de alcance de proyecto se prueban contra SQLite de
         * verdad en `TaskStoreTest`, que es donde vive su semántica.
         */
        fun applyTo(tasks: List<Task>, mutations: List<Mutation>): List<Task> {
            var out = tasks
            for (mutation in mutations) {
                out = when (mutation) {
                    is Mutation.Upsert -> {
                        val byId = mutation.tasks.associateBy { it.id }
                        val known = out.mapTo(HashSet()) { it.id }
                        val kept = out.map { byId[it.id] ?: it }
                        val fresh = mutation.tasks.filter { it.id !in known }
                        kept + fresh
                    }

                    is Mutation.Delete -> mutation.ids.toHashSet().let { gone -> out.filterNot { it.id in gone } }
                    else -> out
                }
            }
            return out
        }
    }
}
