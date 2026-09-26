package com.tasklane.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Cómo se está mirando la lista: el filtro de vista, el archivo de lo terminado y qué
 * estados salen en la tool window y en el tablero.
 *
 * Es un servicio aparte y no un campo de [TaskService] porque no es modelo —nada de
 * lo que hay aquí se guarda en el fichero de tareas ni se comparte con el equipo— y
 * tampoco de [SearchService], que tiene consulta, índice y `debounce`: el filtro no
 * busca nada, sólo decide qué se deja pasar de lo que ya hay.
 *
 * Es **de la ventana**, no de la pestaña. Con un filtro por pestaña, el contador de
 * una diría «3» mientras otra enseña otras reglas, y elegir «vencidas» habría que
 * repetirlo estado por estado.
 */
@Service(Service.Level.PROJECT)
class ViewService(project: Project) {

    private val workspace = TasklaneWorkspaceService.getInstance(project)

    private val _filter = MutableStateFlow(workspace.viewFilter)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    fun setFilter(value: TaskFilter) {
        _filter.value = value
        workspace.viewFilter = value
    }

    /**
     * El archivo de lo terminado (2.10.0): cuántos días se enseñan y si se ha pedido ver
     * lo archivado **en esta sesión**. Lo segundo no se guarda a propósito: es un vistazo
     * —el enlace del pie de la lista, o ir a una tarea archivada—, y la próxima vez la
     * lista tiene que volver a ser la corta que se configuró.
     */
    data class Archive(val days: Int, val showingAll: Boolean = false) {
        /** Si esconde algo ahora mismo. */
        val active: Boolean get() = days > 0 && !showingAll

        /**
         * Desde cuándo se enseña lo terminado: el principio del día de hace [days] días.
         * Por días y no por horas para que lo que se ve no cambie a lo largo de la tarde.
         */
        fun cutoff(today: LocalDate, zone: ZoneId): Instant? =
            if (!active) null else today.minusDays(days.toLong()).atStartOfDay(zone).toInstant()
    }

    private val _archive = MutableStateFlow(Archive(workspace.archiveDays))
    val archive: StateFlow<Archive> = _archive.asStateFlow()

    /** Lo pide la página de ajustes al aplicar. */
    fun setArchiveDays(days: Int) {
        workspace.archiveDays = days
        _archive.value = Archive(workspace.archiveDays)
    }

    /** Enseña u oculta lo archivado hasta que se cierre el proyecto. */
    fun showArchived(show: Boolean) {
        _archive.value = _archive.value.copy(showingAll = show)
    }

    /** Los estados sin columna en el tablero (2.17.1). Ver `TasklaneWorkspaceService.boardHidden`. */
    private val _boardHidden = MutableStateFlow(workspace.boardHidden)
    val boardHidden: StateFlow<Set<StateId>> = _boardHidden.asStateFlow()

    /** Lo pide la página de ajustes al aplicar. */
    fun setBoardHidden(hidden: Set<StateId>) {
        workspace.boardHidden = hidden
        _boardHidden.value = workspace.boardHidden
    }

    /** Los estados sin pestaña en la tool window (2.17.1). Ver `TasklaneWorkspaceService.windowHidden`. */
    private val _windowHidden = MutableStateFlow(workspace.windowHidden)
    val windowHidden: StateFlow<Set<StateId>> = _windowHidden.asStateFlow()

    /** Lo pide la página de ajustes al aplicar. */
    fun setWindowHidden(hidden: Set<StateId>) {
        workspace.windowHidden = hidden
        _windowHidden.value = workspace.windowHidden
    }

    companion object {
        fun getInstance(project: Project): ViewService = project.service()
    }
}
