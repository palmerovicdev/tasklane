package com.tasklane.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.TasklaneConfigService
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.data.store.LoadAlert
import com.tasklane.data.store.StorageLayout
import com.tasklane.data.store.TaskFileStore
import com.tasklane.data.store.alert
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.repo.RepositoryRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds

/**
 * Punto único de mutación del modelo y única capa que habla con el almacén.
 *
 * La UI observa [snapshot] y envía comandos con [apply]; nunca toca [TaskFileStore].
 *
 * El [CoroutineScope] lo inyecta la plataforma y se cancela al cerrar el proyecto,
 * así que no hay que desregistrar nada a mano.
 */
@Service(Service.Level.PROJECT)
class TaskService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    private val layout = StorageLayout.forProject(project)
    private val store = layout?.let(::TaskFileStore)
    private val reducer = TaskReducer()
    private val configService = TasklaneConfigService.getInstance(project)
    private val registry = RepositoryRegistry.getInstance(project)
    private val workspace = TasklaneWorkspaceService.getInstance(project)

    private val _snapshot = MutableStateFlow(
        TasklaneSnapshot.EMPTY.copy(config = configService.config.value),
    )
    val snapshot: StateFlow<TasklaneSnapshot> = _snapshot.asStateFlow()

    /**
     * Petición de «enséñame estas tareas». La emiten la acción de una notificación y el
     * clic sobre una marca del editor, y la atiende la pestaña que las contenga. Va por
     * flow y no por llamada directa porque el servicio no conoce la UI ni debe conocerla.
     *
     * **`replay = 1` porque la ventana puede no existir todavía.** Sus pestañas se
     * construyen al abrirla por primera vez y se suscriben desde una corrutina, así que
     * una petición hecha justo al abrirla —que es lo que hace el clic de una marca— se
     * perdería entre la construcción y la suscripción. Con el replay, la pestaña recién
     * nacida la recibe igual. Lo que puede resucitar es la última petición cuando se crea
     * una pestaña nueva por haber añadido un estado en *Settings*; ahí la tarea no es de
     * ese estado y la pestaña la ignora, que es lo que ya hacía con las ajenas.
     */
    private val _reveal = MutableSharedFlow<Set<TaskId>>(replay = 1, extraBufferCapacity = 8)
    val reveal: SharedFlow<Set<TaskId>> = _reveal.asSharedFlow()

    /** Repos con cambios sin volcar. Se acumulan aquí y el debounce solo da la señal. */
    private val dirty: MutableSet<RepoKey> = Collections.synchronizedSet(mutableSetOf())
    private val saveSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    /** Repos abiertos en solo lectura por venir de una versión futura del esquema. */
    private val readOnly: MutableSet<RepoKey> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Repos cuya lectura ya se ha lanzado. Es lo que impide que dos emisiones
     * seguidas del registro —abrir el proyecto dispara varias— lean el mismo
     * fichero dos veces en paralelo.
     */
    private val requested: MutableSet<RepoKey> = Collections.synchronizedSet(mutableSetOf())

    @OptIn(FlowPreview::class)
    private fun startSaveLoop() {
        scope.launch(Dispatchers.IO) {
            saveSignal.debounce(SAVE_DEBOUNCE).collect { flushAll() }
        }
    }

    init {
        startSaveLoop()
        // La selección guardada se restaura ANTES de que llegue el catálogo: así el
        // primer `loadPending` ya sabe cuál es el repositorio que hay que leer
        // primero, y la tool window no llega a pintar el equivocado.
        workspace.selectedRepo?.let { apply(TaskCommand.SelectRepo(RepoKey(it))) }
        // La configuración entra en el modelo como un comando más. El primer valor
        // que emite el StateFlow es el que ya se sembró arriba, así que es inocuo.
        scope.launch { configService.config.collect { apply(TaskCommand.ConfigChanged(it)) } }
        scope.launch(Dispatchers.IO) {
            registry.repositories.collect { repositories ->
                apply(TaskCommand.RepositoriesChanged(repositories))
                loadPending()
            }
        }
    }

    // ------------------------------------------------------------------ API

    fun apply(command: TaskCommand) {
        var before: TasklaneSnapshot
        var after: TasklaneSnapshot
        // Bucle de compare-and-set: hay dos productores reales —el EDT y la carga en
        // Dispatchers.IO— y un leer-modificar-escribir suelto perdería comandos.
        do {
            before = _snapshot.value
            after = reducer.reduce(before, command)
            if (after == before) return
        } while (!_snapshot.compareAndSet(before, after))

        reportRemapped(before, after)

        // Una carga desde disco no es una edición: no marca nada como sucio.
        if (command is TaskCommand.Loaded) return
        markDirty(before, after)
    }

    fun isReadOnly(repo: RepoKey): Boolean = repo in readOnly

    /**
     * Cambia el repositorio activo y lo recuerda para la próxima apertura. Pasa por
     * aquí y no por el propio `apply` porque persistir la selección es un efecto que
     * sólo tiene sentido cuando la decisión es del usuario, no cuando el reducer
     * mueve el activo porque el suyo desapareció.
     */
    fun selectRepo(repo: RepoKey) {
        if (repo == _snapshot.value.activeRepo) return
        apply(TaskCommand.SelectRepo(repo))
        workspace.selectedRepo = repo.value
        scope.launch(Dispatchers.IO) { loadPending() }
    }

    fun requestReveal(ids: Set<TaskId>) {
        if (ids.isNotEmpty()) _reveal.tryEmit(ids)
    }

    /**
     * «Llévame a estas tareas», con el repositorio ya puesto.
     *
     * Es lo que pide una marca del editor al pulsarla: el fichero no sabe de
     * repositorios, así que la tarea que cuelga de él puede ser de una lista que ni
     * siquiera está abierta, y una petición de enseñar que cae en un repositorio
     * inactivo no la atiende nadie. Se cambia sólo si **ninguna** de las tareas está en
     * el activo: con una que lo esté, cambiar sería llevarse al usuario de su lista para
     * enseñarle algo que ya podía ver.
     */
    fun revealTasks(tasks: List<Task>) {
        if (tasks.isEmpty()) return
        val active = _snapshot.value.activeRepo
        if (tasks.none { it.repo == active }) selectRepo(tasks.first().repo)
        requestReveal(tasks.map { it.id }.toSet())
    }

    /**
     * Saca un repositorio del modelo y borra sus datos del disco. Es la mitad
     * destructiva de «Exportar y quitar», y quien llama ya ha confirmado con el
     * usuario y ha dejado el texto en el portapapeles.
     *
     * El orden importa: primero se limpia el estado que decide qué se escribe
     * —`dirty` sobre todo— y sólo después se borra. Al revés, un volcado pendiente
     * recrearía el `tasks.xml` justo detrás del borrado.
     */
    fun forgetRepo(repo: RepoKey) {
        dirty -= repo
        requested -= repo
        readOnly -= repo
        apply(TaskCommand.ForgetRepo(repo))
        if (workspace.selectedRepo == repo.value) {
            workspace.selectedRepo = _snapshot.value.activeRepo.value
        }
        scope.launch(Dispatchers.IO) {
            runCatching { store?.delete(repo) }.onFailure { e ->
                thisLogger().warn("Tasklane: no se pudo borrar el directorio de $repo", e)
                notify(
                    "Tasklane: el repositorio se quito de la lista",
                    "Sus tareas ya estan en el portapapeles, pero no se pudo borrar la carpeta de " +
                        "datos: ${e.message.orEmpty()}",
                    NotificationType.WARNING,
                )
            }
            // Sin tareas en disco, el catalogo deja de conservar la entrada huerfana.
            registry.refresh()
        }
    }

    /**
     * Qué repositorios hay que reescribir. Se deduce comparando el antes y el después
     * en vez de leerlo del comando: desde la Fase 2 hay comandos —cambio de
     * configuración, reasignación— que tocan varios repos a la vez, y un comando
     * puede no cambiar nada en alguno de ellos.
     */
    private fun markDirty(before: TasklaneSnapshot, after: TasklaneSnapshot) {
        val changed = after.tasksByRepo.keys.filter { repo ->
            repo !in readOnly && after.tasksOf(repo) != before.tasksOf(repo)
        }
        if (changed.isEmpty()) return
        dirty += changed
        saveSignal.tryEmit(Unit)
    }

    // ---------------------------------------------------------------- carga

    /**
     * Lee los repositorios que aún no están en memoria, **el activo primero**: es el
     * único que se está mirando y el que decide cuándo deja de verse vacía la
     * ventana.
     *
     * Los demás se leen a continuación en vez de esperar a que alguien los abra,
     * porque hay comandos de alcance de proyecto —reasignar un estado al borrarlo—
     * que actúan sobre el snapshot entero: un repositorio sin leer se los perdería y
     * sus tareas acabarían remapeadas al estado por defecto en lugar de al que el
     * usuario eligió.
     */
    private suspend fun loadPending() {
        val snapshot = _snapshot.value
        val order = snapshot.repositories
            .map { it.key }
            .sortedByDescending { it == snapshot.activeRepo }
        for (repo in order) {
            if (repo in snapshot.tasksByRepo) continue
            if (!requested.add(repo)) continue
            load(repo)
        }
    }

    private suspend fun load(repo: RepoKey) {
        val store = store ?: run {
            // Proyecto sin directorio base (default project, tests ligeros).
            apply(TaskCommand.Loaded(repo, emptyList()))
            return
        }

        val result = store.read(repo)
        val alert = result.alert()
        if (alert?.readOnly == true) readOnly += repo
        alert?.let(::report)

        // `tasks` esta definido en las cuatro ramas de ReadResult, asi que la carga
        // es uniforme: un fichero corrupto del que se recupero algo no pierde nada.
        apply(TaskCommand.Loaded(repo, result.tasks))
    }

    /**
     * El aviso de [LoadAlert] escrito y lanzado. La decision de **que** avisar vive en
     * [alert], que es pura y se prueba sin IDE; aqui solo queda ponerle palabras.
     */
    private fun report(alert: LoadAlert) = when (alert) {
        is LoadAlert.FutureFormat -> notify(
            TasklaneBundle.message("notification.readOnly.title"),
            TasklaneBundle.message("notification.readOnly.content", alert.version),
            NotificationType.WARNING,
        )

        is LoadAlert.Recovered -> notify(
            TasklaneBundle.message("notification.corrupt.title"),
            corruptContent(TasklaneBundle.message("notification.corrupt.recovered", alert.tasks), alert.quarantinedAt),
            NotificationType.ERROR,
        )

        is LoadAlert.Lost -> notify(
            TasklaneBundle.message("notification.corrupt.title"),
            corruptContent(TasklaneBundle.message("notification.corrupt.lost"), alert.quarantinedAt),
            NotificationType.ERROR,
        )
    }

    /** Donde quedo el fichero ilegible, si se pudo apartar: es por donde se empieza a mirar. */
    private fun corruptContent(head: String, quarantinedAt: java.nio.file.Path?): String =
        quarantinedAt?.let { "$head ${TasklaneBundle.message("notification.corrupt.quarantined", it)}" } ?: head

    // ----------------------------------------------- configuracion desincronizada

    /**
     * Avisa de las tareas que el reducer acaba de aparcar en el estado por defecto
     * por apuntar a configuración inexistente. No es un error recuperable en
     * silencio: el usuario ve sus tareas en un sitio que no eligió, y merece saber
     * cuántas y cuáles.
     */
    private fun reportRemapped(before: TasklaneSnapshot, after: TasklaneSnapshot) {
        val was = TaskReducer.orphans(before.tasksByRepo.values.flatten()).map { it.id }.toSet()
        val now = TaskReducer.orphans(after.tasksByRepo.values.flatten())
        val fresh = now.filterNot { it.id in was }
        if (fresh.isEmpty()) return

        val ids = fresh.map { it.id }.toSet()
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                TasklaneBundle.message("notification.remapped.title"),
                TasklaneBundle.message("notification.remapped.content", fresh.size),
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimple(TasklaneBundle.message("notification.remapped.action")) {
                    requestReveal(ids)
                },
            )
            .notify(project)
    }

    // ------------------------------------------------------------ escritura

    private suspend fun flushAll() {
        val store = store ?: return
        val pending = synchronized(dirty) { dirty.toList().also { dirty.clear() } }
        val current = _snapshot.value
        for (repo in pending) {
            if (repo in readOnly) continue
            runCatching { store.write(repo, current.tasksOf(repo)) }
                .onFailure { e ->
                    thisLogger().error("Tasklane: fallo al guardar $repo", e)
                    notify(
                        TasklaneBundle.message("notification.save.title"),
                        TasklaneBundle.message("notification.save.content", e.message.orEmpty()),
                        NotificationType.ERROR,
                    )
                }
        }
    }

    /**
     * Volcado final. Síncrono a propósito: en este punto el scope del servicio ya
     * está cancelado, así que una corrutina no llegaría a ejecutarse. La escritura
     * es de unos pocos KB.
     */
    override fun dispose() {
        if (dirty.isEmpty()) return
        runCatching { runBlocking { flushAll() } }
            .onFailure { thisLogger().warn("Tasklane: volcado final fallido", it) }
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "Tasklane"
        private val SAVE_DEBOUNCE = 500.milliseconds

        fun getInstance(project: Project): TaskService = project.service()
    }
}
