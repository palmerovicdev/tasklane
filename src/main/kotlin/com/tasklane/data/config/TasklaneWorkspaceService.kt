package com.tasklane.data.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute

/**
 * Estado de UI del proyecto: hoy, qué repositorio estaba seleccionado.
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
    }

    private var state = WorkspaceState()

    var selectedRepo: String?
        get() = state.selectedRepo?.takeIf { it.isNotBlank() }
        set(value) {
            state.selectedRepo = value
        }

    override fun getState(): WorkspaceState = state

    override fun loadState(state: WorkspaceState) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): TasklaneWorkspaceService = project.service()
    }
}
