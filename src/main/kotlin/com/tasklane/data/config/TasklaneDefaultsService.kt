package com.tasklane.data.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.tasklane.domain.model.TasklaneConfig

/**
 * Plantilla de estados y prioridades para proyectos nuevos.
 *
 * Es lo único de la Fase 2 que vive a nivel de aplicación y lo único que roamea.
 * Existe para neutralizar el coste de haber elegido configuración **por proyecto**:
 * un proyecto nuevo no arranca en blanco ni obliga a reconfigurar, se siembra desde
 * aquí; y quien afine sus estados en un proyecto puede subirlos con «guardar como
 * plantilla» y que los siguientes los hereden.
 *
 * Ver `docs/architecture.html` §10.
 */
@Service(Service.Level.APP)
@State(
    name = "TasklaneDefaults",
    storages = [Storage("tasklane-defaults.xml", roamingType = RoamingType.DEFAULT)],
    category = SettingsCategory.TOOLS,
)
class TasklaneDefaultsService : PersistentStateComponent<TasklaneConfigState> {

    /** Sin plantilla guardada todavía: la semilla de fábrica hace de plantilla. */
    private var template: TasklaneConfig = TasklaneConfig.DEFAULT

    fun template(): TasklaneConfig = template

    fun saveAsTemplate(config: TasklaneConfig) {
        template = config.normalized()
    }

    override fun getState(): TasklaneConfigState = template.toState()

    override fun loadState(state: TasklaneConfigState) {
        template = state.toDomain() ?: TasklaneConfig.DEFAULT
    }

    companion object {
        fun getInstance(): TasklaneDefaultsService = service()
    }
}
