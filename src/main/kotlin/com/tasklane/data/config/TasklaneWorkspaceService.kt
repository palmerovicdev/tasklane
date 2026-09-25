package com.tasklane.data.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.tasklane.domain.export.ExportFormat
import com.tasklane.domain.model.AnchorMarkerStyle
import com.tasklane.domain.model.TaskFilter

/**
 * Estado de UI del proyecto: qué repositorio y qué estado estaban seleccionados, si
 * la búsqueda miraba a todos los repositorios, qué filtro de vista estaba puesto, en
 * qué formato se exporta y cómo se marcan las anclas en el editor.
 *
 * Va a `workspace.xml` y no a `tasklane.xml` porque no es una decisión que se
 * comparta con el equipo, sino dónde estaba mirando **esta** persona en **esta**
 * máquina. Y `workspace.xml` ya está fuera de VCS, así que no hay que ignorar nada.
 *
 * La clave se guarda como `String` y no como `RepoKey`: si el repositorio ya no
 * existe al reabrir, el reducer cae al primero del catálogo sin que aquí haya que
 * validar nada.
 */
@Service(Service.Level.PROJECT)
@State(name = "TasklaneWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TasklaneWorkspaceService : PersistentStateComponent<TasklaneWorkspaceService.WorkspaceState> {

    class WorkspaceState {
        @Attribute
        var selectedRepo: String? = null

        @Attribute
        var searchAllRepos: Boolean = false

        /**
         * Se guarda el nombre y no el `enum`: un valor escrito por una versión más
         * nueva se lee aquí como desconocido y cae al por defecto, en vez de tumbar
         * la deserialización del componente entero. Mismo motivo en [viewFilter].
         */
        @Attribute
        var exportFormat: String? = null

        @Attribute
        var viewFilter: String? = null

        @Attribute
        var selectedState: String? = null

        @Attribute
        var anchorMarker: String? = null

        @Attribute
        var dueReminders: Boolean = true

        @Attribute
        var archiveDays: Int = 0
    }

    private var state = WorkspaceState()

    var selectedRepo: String?
        get() = state.selectedRepo?.takeIf { it.isNotBlank() }
        set(value) {
            state.selectedRepo = value
        }

    /** Alcance de la búsqueda. Por defecto, sólo el repositorio activo. */
    var searchAllRepos: Boolean
        get() = state.searchAllRepos
        set(value) {
            state.searchAllRepos = value
        }

    /**
     * El estado que estaba abierto. Se guarda como `String` por lo mismo que
     * [selectedRepo]: si ya no existe al reabrir, la ventana cae al primero sin que
     * aquí haya que validar nada.
     */
    var selectedState: String?
        get() = state.selectedState?.takeIf { it.isNotBlank() }
        set(value) {
            state.selectedState = value
        }

    /**
     * Filtro de vista de la ventana. Se recuerda porque es una forma de mirar la
     * lista —«enséñame sólo lo vencido»— y no una acción: perderla al reabrir
     * obligaría a volver a elegirla cada mañana.
     */
    var viewFilter: TaskFilter
        get() = TaskFilter.entries.firstOrNull { it.name == state.viewFilter } ?: TaskFilter.ALL
        set(value) {
            state.viewFilter = value.name
        }

    /**
     * Cómo se marcan en el editor las líneas con tareas. Ver [AnchorMarkerStyle].
     *
     * Aquí y no en `tasklane.xml` porque no es una decisión del proyecto: no cambia
     * nada de lo que se guarda ni de lo que ve el equipo, sólo qué dibuja **esta**
     * instalación encima del código. Compartirla obligaría a que todo el equipo mirara
     * el margen igual.
     */
    var anchorMarker: AnchorMarkerStyle
        get() = AnchorMarkerStyle.entries.firstOrNull { it.name == state.anchorMarker } ?: AnchorMarkerStyle.GUTTER
        set(value) {
            state.anchorMarker = value.name
        }

    /**
     * Si se avisa de lo que vence hoy o ya venció (2.9.0). Encendido de fábrica: es para
     * lo que existe la fecha. De la persona y no del proyecto, como [anchorMarker]: que
     * a uno le moleste el aviso no dice nada de cómo trabaja el resto del equipo.
     */
    var dueReminders: Boolean
        get() = state.dueReminders
        set(value) {
            state.dueReminders = value
        }

    /**
     * Cuántos días hacia atrás se enseña lo terminado (2.10.0); `0` es «todo», que es como
     * venía y como sigue de fábrica: esconder tareas sin que nadie lo pida sería
     * indistinguible de perderlas. De la persona, como el filtro de vista: es una forma
     * de mirar la lista, no de guardarla.
     */
    var archiveDays: Int
        get() = state.archiveDays.coerceAtLeast(0)
        set(value) {
            state.archiveDays = value.coerceAtLeast(0)
        }

    /** Formato del portapapeles. Markdown por defecto: es lo que entiende el destino habitual. */
    var exportFormat: ExportFormat
        get() = ExportFormat.entries.firstOrNull { it.name == state.exportFormat } ?: ExportFormat.MARKDOWN
        set(value) {
            state.exportFormat = value.name
        }

    override fun getState(): WorkspaceState = state

    override fun loadState(state: WorkspaceState) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): TasklaneWorkspaceService = project.service()
    }
}
