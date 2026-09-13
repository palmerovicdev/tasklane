package com.tasklane.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.tasklane.data.store.StorageLayout
import com.tasklane.data.store.TaskFileStore
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.TasklaneSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _snapshot = MutableStateFlow(TasklaneSnapshot.EMPTY.copy(loading = setOf(RepoKey.ROOT)))
    val snapshot: StateFlow<TasklaneSnapshot> = _snapshot.asStateFlow()

    /** Repos con cambios sin volcar. Se acumulan aquí y el debounce solo da la señal. */
    private val dirty: MutableSet<RepoKey> = Collections.synchronizedSet(mutableSetOf())
    private val saveSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    /** Repos abiertos en solo lectura por venir de una versión futura del esquema. */
    private val readOnly: MutableSet<RepoKey> = Collections.synchronizedSet(mutableSetOf())

    @OptIn(FlowPreview::class)
    private fun startSaveLoop() {
        scope.launch(Dispatchers.IO) {
            saveSignal.debounce(SAVE_DEBOUNCE).collect { flushAll() }
        }
    }

    init {
        startSaveLoop()
        scope.launch(Dispatchers.IO) { load(RepoKey.ROOT) }
    }

    // ------------------------------------------------------------------ API

    fun apply(command: TaskCommand) {
        val before = _snapshot.value
        val after = reducer.reduce(before, command)
        if (after == before) return

        _snapshot.value = after

        // Una carga desde disco no es una edición: no marca el repo como sucio.
        if (command !is TaskCommand.Loaded && command.repo !in readOnly) {
            dirty += command.repo
            saveSignal.tryEmit(Unit)
        }
    }

    fun isReadOnly(repo: RepoKey): Boolean = repo in readOnly

    // ---------------------------------------------------------------- carga

    private suspend fun load(repo: RepoKey) {
        val store = store ?: run {
            // Proyecto sin directorio base (default project, tests ligeros).
            apply(TaskCommand.Loaded(repo, emptyList()))
            return
        }

        val result = store.read(repo)

        when (result) {
            is TaskFileStore.ReadResult.Empty, is TaskFileStore.ReadResult.Ok -> Unit

            is TaskFileStore.ReadResult.FutureVersion -> {
                readOnly += repo
                notify(
                    "Tasklane: datos de solo lectura",
                    "El fichero de tareas usa el formato v${result.version}, mas nuevo que el que " +
                        "entiende esta version del plugin. Se muestran sin permitir cambios para no " +
                        "degradarlos. Actualiza el plugin para volver a editarlos.",
                    NotificationType.WARNING,
                )
            }

            is TaskFileStore.ReadResult.Corrupt -> notify(
                "Tasklane: no se pudo leer el fichero de tareas",
                buildString {
                    if (result.recoveredFromBackup) {
                        append("Se recuperaron ${result.recovered.size} tareas desde la copia de seguridad. ")
                    } else {
                        append("No habia copia de seguridad utilizable, se ha empezado en blanco. ")
                    }
                    result.quarantinedAt?.let { append("El fichero original se conservo en $it") }
                },
                NotificationType.ERROR,
            )
        }

        // `tasks` esta definido en las cuatro ramas de ReadResult, asi que la carga
        // es uniforme: un fichero corrupto del que se recupero algo no pierde nada.
        apply(TaskCommand.Loaded(repo, result.tasks))
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
                        "Tasklane: no se pudieron guardar las tareas",
                        "${e.message.orEmpty()} El fichero anterior no se ha modificado.",
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
    }
}
