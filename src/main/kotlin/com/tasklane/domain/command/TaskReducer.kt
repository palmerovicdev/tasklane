package com.tasklane.domain.command

import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
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
 * `(Snapshot, Command) -> Snapshot`. Aquí viven las invariantes del modelo y nada más:
 * sin IO, sin Swing, sin `Project`. Es la clase que concentra la lógica y la única
 * que hace falta testear para confiar en el comportamiento.
 */
class TaskReducer(private val clock: Clock = Clock.systemUTC()) {

    /**
     * Lo que produce un comando: el snapshot nuevo **y el recibo de lo que hizo falta
     * para producirlo**.
     *
     * El recibo existe porque antes el servicio lo reconstruía a posteriori recorriendo
     * el modelo entero: `reportRemapped` aplanaba el snapshot **dos veces por comando**
     * y `markDirty` comparaba las listas de todos los repositorios. A 100.000 tareas eso
     * eran 0,6 ms por pulsación gastados en volver a deducir algo que el reducer acababa
     * de saber de primera mano.
     *
     * En la Fase 3 esto es lo que pasará a devolver `List<Mutation>` (ver
     * `docs/plan-escala.md` §3.4): la forma ya es la buena, lo que cambiará es que en
     * vez de un snapshot entero llevará las filas que tocar.
     */
    data class Reduction(
        val snapshot: TasklaneSnapshot,
        /**
         * Repositorios cuya lista de tareas cambió, y por tanto hay que reescribir.
         *
         * Vacío para una carga: leer de disco no es editar. Y vacío también cuando el
         * comando no cambió nada, que es el caso más frecuente de todos —los eventos
         * de VCS llegan repetidos y `ConfigChanged` se emite en cada arranque—.
         */
        val dirty: Set<RepoKey>,
        /**
         * Tareas que **este** comando acaba de aparcar en el estado o la prioridad por
         * defecto por apuntar a configuración que ya no existe. Ver [normalize].
         *
         * Sólo las nuevas: una tarea que ya venía aparcada no se vuelve a anunciar en
         * cada comando, que es la razón por la que el servicio comparaba el antes y el
         * después.
         */
        val remapped: List<Task>,
    )

    /**
     * El comando completo, con recibo. Es lo que usa [com.tasklane.service.TaskService].
     */
    fun plan(snapshot: TasklaneSnapshot, command: TaskCommand): Reduction {
        val now = Instant.now(clock)
        // Se comparte con `normalize` y casi siempre se queda vacía: sólo la carga y un
        // cambio de configuración pueden aparcar tareas.
        val remapped = ArrayList<Task>(0)

        return when (command) {
            // ------------------------------------------------ alcance proyecto

            is TaskCommand.ConfigChanged -> {
                val config = command.config.normalized()
                if (config == snapshot.config) unchanged(snapshot)
                else snapshot.copy(config = config)
                    .mapAllTasks { normalize(it, config, remapped) }
                    .with(remapped)
            }

            is TaskCommand.ReassignState -> {
                if (command.from == command.to) return unchanged(snapshot)
                snapshot.mapAllTasks { task ->
                    if (task.stateId != command.from) task
                    else applyState(task, command.to, snapshot.config, now)
                }.with(remapped)
            }

            is TaskCommand.ReassignPriority -> {
                if (command.from == command.to) return unchanged(snapshot)
                snapshot.mapAllTasks { task ->
                    if (task.priorityId != command.from) task
                    else task.copy(
                        priorityId = command.to,
                        updatedAt = now,
                        extra = task.extra - ORIG_PRIORITY,
                    )
                }.with(remapped)
            }

            is TaskCommand.RepositoriesChanged -> {
                val repos = command.repositories
                // Que el activo sobreviva es la invariante: perderlo dejaría el
                // árbol pintando un repositorio que ya no está en la lista.
                val active = when {
                    repos.isEmpty() -> snapshot.activeRepo
                    repos.any { it.key == snapshot.activeRepo } -> snapshot.activeRepo
                    else -> repos.first().key
                }
                // Pendientes de leer = los del catálogo que aún no tienen lista. Se
                // recalcula entero: un repositorio que sale del catálogo deja de
                // estar pendiente de nada.
                val loading = repos.map { it.key }.filterNot { it in snapshot.tasksByRepo }.toSet()
                // Los eventos de VCS llegan repetidos. Devolver el MISMO snapshot
                // cuando nada cambió es lo que evita que el servicio se ponga a
                // recorrer repositorios buscando qué reescribir.
                if (repos == snapshot.repositories && active == snapshot.activeRepo && loading == snapshot.loading) {
                    return unchanged(snapshot)
                }
                // Ninguna tarea cambia: cambia el catálogo. Nada que reescribir.
                unchanged(snapshot.copy(repositories = repos, activeRepo = active, loading = loading))
            }

            is TaskCommand.SelectRepo -> unchanged(
                if (command.repo == snapshot.activeRepo) snapshot
                else snapshot.copy(activeRepo = command.repo),
            )

            is TaskCommand.BackfillCompletedAt -> snapshot.mapAllTasks { task ->
                // `updatedAt` no se toca: la fecha que se rellena se DERIVA de ella,
                // así que pisarla destruiría justamente el dato que se está usando.
                if (task.stateId in command.states && task.completedAt == null) {
                    task.copy(completedAt = task.updatedAt)
                } else {
                    task
                }
            }.with(remapped)

            // ----------------------------------------------------- acotados

            is RepoScoped -> reduceScoped(snapshot, command, now, remapped)
        }
    }

    /**
     * Sólo el snapshot. Es la forma que consumen los tests y el banco, que no escriben
     * en disco y por tanto no tienen nada que hacer con el recibo.
     */
    fun reduce(snapshot: TasklaneSnapshot, command: TaskCommand): TasklaneSnapshot =
        plan(snapshot, command).snapshot

    /** Un snapshot sin nada que reescribir ni que anunciar. */
    private fun unchanged(snapshot: TasklaneSnapshot) = Reduction(snapshot, emptySet(), emptyList())

    private fun reduceScoped(
        snapshot: TasklaneSnapshot,
        command: RepoScoped,
        now: Instant,
        remapped: MutableList<Task>,
    ): Reduction {
        val repo = command.repo
        val current = snapshot.tasksOf(repo)
        val config = snapshot.config

        val next: List<Task> = when (command) {
            // Una carga NO ensucia nada: leer de disco no es editar, y marcarlo como
            // sucio haría que abrir el proyecto reescribiera todos sus ficheros. Sí
            // puede aparcar tareas, y eso sí se anuncia.
            is TaskCommand.Loaded -> {
                val tasks = command.tasks.map { withDerived(normalize(it, config, remapped)) }
                return Reduction(
                    snapshot.copy(
                        tasksByRepo = snapshot.tasksByRepo + (repo to tasks),
                        loading = snapshot.loading - repo,
                        // El techo del orden se calcula AQUÍ, que es el único momento
                        // en que ya se está recorriendo la lista entera de todos
                        // modos. A partir de aquí `nextOrder` es O(1). Ver
                        // [TasklaneSnapshot.maxOrder].
                        maxOrder = snapshot.maxOrder + (repo to (tasks.maxOfOrNull { it.order } ?: 0L)),
                    ),
                    dirty = emptySet(),
                    remapped = remapped,
                )
            }

            is TaskCommand.ForgetRepo -> {
                if (repo !in snapshot.tasksByRepo && snapshot.repositories.none { it.key == repo }) {
                    return unchanged(snapshot)
                }
                val remaining = snapshot.repositories.filterNot { it.key == repo }
                // Tampoco ensucia: el repositorio se va, no se reescribe.
                return unchanged(
                    snapshot.copy(
                        tasksByRepo = snapshot.tasksByRepo - repo,
                        repositories = remaining,
                        // Igual que en RepositoriesChanged: el activo tiene que seguir
                        // existiendo, o el árbol quedaría pintando una clave fantasma.
                        activeRepo = if (snapshot.activeRepo != repo) snapshot.activeRepo
                        else remaining.firstOrNull()?.key ?: snapshot.activeRepo,
                        loading = snapshot.loading - repo,
                        maxOrder = snapshot.maxOrder - repo,
                    ),
                )
            }

            is TaskCommand.Create -> {
                val body = command.body.trim()
                // Una tarea vacía no es una tarea: se ignora en vez de crear ruido.
                if (body.isEmpty()) return unchanged(snapshot)
                val state = command.stateId?.let { config.stateOrDefault(it) } ?: config.defaultState
                val priority = command.priorityId?.let { config.priorityOrDefault(it) } ?: config.defaultPriority
                current + Task(
                    id = TaskId.random(),
                    repo = repo,
                    body = body,
                    links = LinkExtractor.extract(body),
                    attachments = ImageRefParser.parse(body),
                    stateId = state.id,
                    priorityId = priority.id,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = if (state.terminal) now else null,
                    order = snapshot.nextOrder(repo),
                    tags = command.tags.map(String::trim).filter(String::isNotEmpty).distinct(),
                    dueDate = command.dueDate,
                    anchors = command.anchors.distinct(),
                )
            }

            is TaskCommand.UpdateBody -> current.mapTask(command.id) { task ->
                val body = command.body.trim()
                if (body.isEmpty() || body == task.body) task
                else withDerived(task.copy(body = body, updatedAt = now))
            }

            // Los seis cambios del diálogo sobre UNA copia de la lista, y produciendo
            // UN snapshot. Ver el KDoc de [TaskCommand.UpdateTask].
            is TaskCommand.UpdateTask -> current.mapTask(command.id) { task ->
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
                command.tags?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()
                    ?.takeIf { it != next.tags }
                    ?.let { next = next.copy(tags = it) }
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

            is TaskCommand.ChangeState -> current.mapTask(command.id) { task ->
                applyState(task, command.stateId, config, now)
            }

            is TaskCommand.ChangePriority -> current.mapTask(command.id) { task ->
                if (task.priorityId == command.priorityId) task
                // Mover a mano es una decisión: deja de ser una huérfana aparcada
                // y por eso pierde la marca que la haría volver sola.
                else task.copy(
                    priorityId = command.priorityId,
                    updatedAt = now,
                    extra = task.extra - ORIG_PRIORITY,
                )
            }

            is TaskCommand.SetAnchors -> current.mapTask(command.id) { task ->
                val anchors = command.anchors.distinct()
                if (task.anchors == anchors) task else task.copy(anchors = anchors, updatedAt = now)
            }

            is TaskCommand.SetDueDate -> current.mapTask(command.id) { task ->
                if (task.dueDate == command.dueDate) task
                else task.copy(dueDate = command.dueDate, updatedAt = now)
            }

            is TaskCommand.ToggleBookmark -> current.mapTask(command.id) { task ->
                // Marcar no es editar: no toca `updatedAt`. Si lo tocara, marcar una
                // tarea vieja para no perderla de vista la movería al grupo de hoy,
                // que es justo lo contrario de lo que el usuario pidió.
                task.copy(bookmarked = !task.bookmarked)
            }

            is TaskCommand.SetTags -> current.mapTask(command.id) { task ->
                val tags = command.tags.map(String::trim).filter(String::isNotEmpty).distinct()
                if (tags == task.tags) task else task.copy(tags = tags, updatedAt = now)
            }

            is TaskCommand.ToggleComplete -> current.mapTask(command.id) { task ->
                val isDone = config.stateOrDefault(task.stateId).terminal
                val target = if (isDone) {
                    config.defaultState.id
                } else {
                    (config.states.firstOrNull { it.terminal } ?: config.defaultState).id
                }
                applyState(task, target, config, now)
            }

            is TaskCommand.Delete -> {
                val ids = command.ids.toSet()
                val next = current.filterNot { it.id in ids }
                // La MISMA lista si no había nada que borrar. `filterNot` siempre
                // construye una nueva, y una lista nueva es un snapshot nuevo: un
                // repintado y un volcado a disco por un borrado que no borró nada.
                if (next.size == current.size) current else next
            }
        }

        // Identidad y no igualdad: el reducer devuelve la MISMA lista cuando no ha
        // tocado nada, así que decidir por referencia es exacto y cuesta un puntero.
        // Es lo que sustituye al `equals` de listas que hacía `TaskService.markDirty`.
        if (next === current) return unchanged(snapshot)
        return Reduction(
            snapshot.copy(
                tasksByRepo = snapshot.tasksByRepo + (repo to next),
                maxOrder = nextMaxOrder(snapshot, command, next),
            ),
            dirty = setOf(repo),
            remapped = remapped,
        )
    }

    /**
     * El techo del orden después del comando.
     *
     * Sólo [TaskCommand.Create] lo mueve, y lo mueve a un valor que se acaba de
     * calcular, así que se sabe sin mirar la lista. Los demás comandos no tocan
     * `order`; borrar tampoco lo baja, y no hace falta que lo baje: los huecos del
     * orden disperso son justamente lo que permite insertar sin renumerar.
     */
    private fun nextMaxOrder(
        snapshot: TasklaneSnapshot,
        command: RepoScoped,
        next: List<Task>,
    ): Map<RepoKey, Long> =
        if (command is TaskCommand.Create) {
            snapshot.maxOrder + (command.repo to (next.last().order))
        } else {
            snapshot.maxOrder
        }

    /**
     * Recalcula lo que se deriva del cuerpo: enlaces e imágenes.
     *
     * Se invoca **sólo donde el cuerpo entra o cambia** —crear, editar y cargar— y
     * no en los comandos que mueven estado o prioridad: ninguno de ellos puede
     * tocar el texto, y extraer ahí sería recorrer el cuerpo de todas las tareas
     * para llegar siempre a la misma lista. Ver `docs/architecture.html` §9.
     *
     * Devuelve la MISMA instancia si nada cambió: es lo que deja que el servicio
     * decida por identidad qué repositorios hay que reescribir.
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

    /**
     * Red de seguridad ante configuración desincronizada, en los dos sentidos.
     *
     * **Se fue:** una tarea que apunta a un estado o prioridad inexistente NO se
     * descarta; se remapea al valor por defecto y el ID original queda en
     * [Task.extra] —que el códec persiste, así que sobrevive a cerrar el proyecto—.
     *
     * **Volvió:** si ese ID reaparece en la configuración, la tarea regresa sola a
     * su sitio. La marca sólo sobrevive mientras la tarea siga aparcada en el
     * fallback: moverla a mano la borra, de modo que la vuelta nunca pisa una
     * decisión del usuario.
     */
    private fun normalize(task: Task, config: TasklaneConfig, remapped: MutableList<Task>): Task {
        // El caso abrumadoramente normal: la configuración está donde debe y no hay
        // nada que recolocar. Se sale antes de tocar un mapa mutable, porque esto
        // corre una vez por tarea en cada carga y en cada paso por los ajustes.
        if (task.extra.isEmpty() && config.state(task.stateId) != null && config.priority(task.priorityId) != null) {
            return task
        }

        val wasOrphan = ORIG_STATE in task.extra || ORIG_PRIORITY in task.extra
        var stateId = task.stateId
        var priorityId = task.priorityId
        val extra = task.extra.toMutableMap()

        extra[ORIG_STATE]?.let { orig ->
            config.state(StateId(orig))?.let { stateId = it.id; extra -= ORIG_STATE }
        }
        extra[ORIG_PRIORITY]?.let { orig ->
            config.priority(PriorityId(orig))?.let { priorityId = it.id; extra -= ORIG_PRIORITY }
        }

        if (config.state(stateId) == null) {
            // putIfAbsent y no put: si la tarea ya venía huérfana, el ID que vale la
            // pena recordar es el primero, no el fallback por el que pasó después.
            extra.putIfAbsent(ORIG_STATE, stateId.value)
            stateId = config.defaultState.id
        }
        if (config.priority(priorityId) == null) {
            extra.putIfAbsent(ORIG_PRIORITY, priorityId.value)
            priorityId = config.defaultPriority.id
        }

        if (stateId == task.stateId && priorityId == task.priorityId && extra == task.extra) return task
        val next = task.copy(stateId = stateId, priorityId = priorityId, extra = extra)
        // El recibo: sólo las que se aparcan AHORA. Una tarea que ya venía aparcada no
        // se vuelve a anunciar, que es lo que el servicio conseguía comparando el
        // conjunto de huérfanas de antes con el de después —dos `flatten()` del
        // snapshot entero por comando—.
        if (!wasOrphan && (ORIG_STATE in extra || ORIG_PRIORITY in extra)) remapped += next
        return next
    }

    /**
     * Aplica [transform] a todas las tareas de todos los repositorios conservando la
     * identidad de lo que no cambia: si un repositorio sale intacto se devuelve su
     * misma lista, y si ninguno cambia se devuelve el mismo snapshot.
     *
     * No es micro-optimización. `ConfigChanged` se emite en cada arranque y en cada
     * paso por los ajustes, y casi siempre no tiene nada que remapear; reconstruir
     * ahí todas las listas haría que el servicio marcara como sucios —y reescribiera
     * en disco— repositorios en los que no ha pasado nada.
     */
    private inline fun TasklaneSnapshot.mapAllTasks(transform: (Task) -> Task): Changed {
        val dirty = mutableSetOf<RepoKey>()
        val next = tasksByRepo.mapValues { (repo, tasks) ->
            var changed = false
            val mapped = tasks.map { task -> transform(task).also { if (it !== task) changed = true } }
            if (!changed) return@mapValues tasks
            dirty += repo
            mapped
        }
        return if (dirty.isEmpty()) Changed(this, emptySet()) else Changed(copy(tasksByRepo = next), dirty)
    }

    /** Un snapshot y los repositorios que hay que reescribir. Se completa con [with]. */
    private class Changed(val snapshot: TasklaneSnapshot, val dirty: Set<RepoKey>)

    private fun Changed.with(remapped: List<Task>) = Reduction(snapshot, dirty, remapped)

    /**
     * Sustituye **una** tarea conservando el resto tal cual.
     *
     * Antes era un `map` sobre la lista entera, y eso era el 69 % del coste de un
     * comando a 100.000 tareas (2,86 ms de los 4,14 medidos en la Fase 0): `map`
     * recorre elemento a elemento invocando una lambda y va rellenando un `ArrayList`
     * que crece. Aquí el recorrido caro se parte en dos pasos que el sistema hace bien:
     * una búsqueda del índice —comparar `TaskId`, que es un `value class` sobre
     * `String`— y una copia del array de una sola llamada, que acaba en
     * `System.arraycopy`.
     *
     * Sigue siendo O(n), y lo será hasta la Fase 3: el objetivo de la Fase 1 no es
     * quitar la linealidad sino la constante, que es de dónde sale el techo de ~100.000
     * tareas que el plan promete.
     *
     * Devolver la MISMA lista cuando nada cambió es lo que deja que el reducer decida
     * por identidad qué repositorios hay que reescribir.
     */
    private inline fun List<Task>.mapTask(id: TaskId, transform: (Task) -> Task): List<Task> {
        val index = indexOfFirst { it.id == id }
        if (index < 0) return this
        val task = this[index]
        val next = transform(task)
        if (next === task) return this
        val copy = ArrayList(this)
        copy[index] = next
        return copy
    }

    companion object {
        const val ORIG_STATE = "origStateId"
        const val ORIG_PRIORITY = "origPriorityId"

        /** Tareas aparcadas en el fallback por configuración ausente. */
        fun orphans(tasks: List<Task>): List<Task> =
            tasks.filter { ORIG_STATE in it.extra || ORIG_PRIORITY in it.extra }
    }
}
