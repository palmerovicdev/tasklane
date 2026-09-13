package com.tasklane.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.domain.model.TaskFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cómo se está mirando la lista. Hoy sólo el filtro de vista.
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

    companion object {
        fun getInstance(project: Project): ViewService = project.service()
    }
}
