package com.tasklane.repo

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.tasklane.data.config.TasklaneConfigService
import com.tasklane.data.store.RepoLayoutStore
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.model.RepositoryRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Qué repositorios hay en la ventana. Publica un [StateFlow] por la misma razón que
 * `TaskService`: quien lo consume repinta desde la lista completa y no tiene que
 * reconstruir estado a base de eventos incrementales.
 *
 * Es la única clase que junta las tres piezas —proveedores, `layout.xml` y el filtro
 * de profundidad—, y no decide nada por su cuenta: la política vive en [RepoCatalog],
 * que es Kotlin puro. Aquí sólo queda el pegamento con la plataforma.
 */
@Service(Service.Level.PROJECT)
class RepositoryRegistry(
    private val project: Project,
    private val scope: CoroutineScope,
) {

    private val layout = StorageLayout.forProject(project)
    private val layoutStore = layout?.let(::RepoLayoutStore)

    private val _repositories = MutableStateFlow<List<RepositoryRef>>(emptyList())
    val repositories: StateFlow<List<RepositoryRef>> = _repositories.asStateFlow()

    /**
     * Los eventos de VCS llegan en ráfagas —abrir el proyecto dispara varios—, así
     * que se colapsan. La detección es barata, pero reescribir `layout.xml` cinco
     * veces seguidas no aporta nada.
     */
    private val refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    @OptIn(FlowPreview::class)
    private fun startRefreshLoop() {
        scope.launch(Dispatchers.IO) {
            refreshSignal.debounce(REFRESH_DEBOUNCE).collect { recompute() }
        }
    }

    init {
        startRefreshLoop()
        scope.launch(Dispatchers.IO) {
            seedFromLayout()
            recompute()
        }
        // La profundidad es configuración del proyecto: cambiarla en los ajustes
        // tiene que mover el selector sin reiniciar. `drop(1)` salta el valor que
        // el StateFlow ya tenía cuando se suscribió: ese ya lo cubre el recompute
        // inicial de arriba.
        scope.launch {
            TasklaneConfigService.getInstance(project).config
                .map { it.repoDepth }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
    }

    /** Recalcula el catálogo. Barato e idempotente: si nada cambió, no emite. */
    fun refresh() {
        refreshSignal.tryEmit(Unit)
    }

    /**
     * Primer pintado desde la última lista conocida. Al abrir el proyecto,
     * `GitRepositoryManager` puede devolver una lista vacía durante unos
     * milisegundos; sin esto, el selector aparecería vacío y se rellenaría de golpe.
     */
    private fun seedFromLayout() {
        val known = layoutStore?.read().orEmpty()
        if (known.isEmpty()) return
        _repositories.compareAndSet(emptyList(), known.map { it.copy(available = exists(it.rootPath)) })
    }

    private suspend fun recompute() {
        if (project.isDisposed) return
        val projectRoot = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return
        val storage = layout ?: return

        val detected = detect()
        val refs = RepoCatalog.build(
            projectRoot = projectRoot,
            detected = detected,
            known = layoutStore?.read().orEmpty(),
            maxDepth = TasklaneConfigService.getInstance(project).config.value.repoDepth,
            hasTasks = storage::hasTasks,
            exists = ::exists,
        )

        if (refs == _repositories.value) return
        _repositories.value = refs
        runCatching { layoutStore?.write(refs) }
            .onFailure { thisLogger().warn("Tasklane: no se pudo escribir layout.xml", it) }
    }

    /**
     * Proveedores en orden de preferencia; gana el primero que encuentre algo. No es
     * una unión a propósito: en un proyecto con repositorios Git, añadir además cada
     * content root llenaría el selector de entradas que el usuario no reconoce.
     */
    private suspend fun detect(): List<DetectedRepo> = try {
        val providers = RepositoryProvider.EP.extensionList.sortedBy { it.order }
        readAction {
            providers.firstNotNullOfOrNull { provider ->
                provider.detect(project).takeIf { it.isNotEmpty() }
            }.orEmpty()
        }
    } catch (e: CancellationException) {
        // Cerrar el proyecto cancela el scope; dejarla pasar es lo correcto.
        throw e
    } catch (e: Exception) {
        thisLogger().warn("Tasklane: fallo detectando repositorios", e)
        emptyList()
    }

    private fun exists(path: String): Boolean =
        runCatching { Files.isDirectory(Path.of(path)) }.getOrDefault(false)

    companion object {
        private val REFRESH_DEBOUNCE = 300.milliseconds

        fun getInstance(project: Project): RepositoryRegistry = project.service()
    }
}
