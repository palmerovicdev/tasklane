package com.tasklane.data.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.tasklane.domain.model.TasklaneConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estados y prioridades del proyecto, persistidos por la plataforma en
 * `.idea/tasklane.xml` — fuera del directorio auto-ignorado, porque a diferencia de
 * los datos esta parte **sí** se comparte con el equipo.
 *
 * Expone un [StateFlow] y no un listener porque es exactamente lo que consume
 * `TaskService`: la configuración entra en el modelo como un comando más.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "Tasklane",
    storages = [Storage("tasklane.xml")],
    category = SettingsCategory.TOOLS,
)
class TasklaneConfigService : PersistentStateComponent<TasklaneConfigState> {

    private val _config = MutableStateFlow(TasklaneConfig.DEFAULT)
    val config: StateFlow<TasklaneConfig> = _config.asStateFlow()

    fun update(config: TasklaneConfig) {
        _config.value = config.normalized()
    }

    override fun getState(): TasklaneConfigState = _config.value.toState()

    override fun loadState(state: TasklaneConfigState) {
        // Un fichero ilegible o resuelto a medias en un merge no debe dejar el
        // proyecto sin un solo estado: se cae a la plantilla, como un proyecto nuevo.
        _config.value = state.toDomain() ?: seed()
    }

    /**
     * El proyecto no tiene `.idea/tasklane.xml`. La plataforma llama aquí en vez de
     * a [loadState], y es el único punto donde la siembra desde la plantilla tiene
     * sentido: hacerlo en el constructor sembraría también a quien sí tiene fichero.
     */
    override fun noStateLoaded() {
        _config.value = seed()
    }

    private fun seed(): TasklaneConfig = TasklaneDefaultsService.getInstance().template()

    companion object {
        fun getInstance(project: Project): TasklaneConfigService = project.service()
    }
}
