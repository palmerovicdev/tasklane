package com.tasklane.data.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Attribute
import com.tasklane.domain.agent.AgentRequest

/**
 * Cómo se encarga una tarea a un agente (P26): la orden que se escribe en la terminal, la
 * petición que lleva y si la tarea pasa a *en curso*.
 *
 * Es de la persona, como la marca del editor, pero **de la aplicación** y no de
 * `workspace.xml`: qué agente tiene uno instalado —`claude`, `codex`, `gemini`— es de su
 * máquina, no de cada proyecto, y obligar a escribirlo en todos sería el mismo coste que
 * `TasklaneDefaultsService` existe para evitar con los estados. Roamea con los ajustes del
 * IDE por lo mismo que la plantilla.
 *
 * `null` es «lo de fábrica», y no se guarda: si la orden o la petición de fábrica mejoran, las
 * recibe quien no las haya tocado. La orden vacía es otra cosa —«no abras nada»— y sí se
 * guarda.
 */
@Service(Service.Level.APP)
@State(
    name = "TasklaneAgent",
    storages = [Storage("tasklane-agent.xml", roamingType = RoamingType.DEFAULT)],
    category = SettingsCategory.TOOLS,
)
class AgentSettings : PersistentStateComponent<AgentSettings.AgentState> {

    class AgentState {
        @Attribute
        var command: String? = null

        @Attribute
        var prompt: String? = null

        @Attribute
        var moveToWorking: Boolean = true
    }

    private var state = AgentState()

    /** La orden. Vacía: no hay *Hand Off to Agent*, sólo *Copy Agent Prompt*. */
    var command: String
        get() = state.command ?: AgentRequest.DEFAULT_COMMAND
        set(value) {
            state.command = value.trim().takeUnless { it == AgentRequest.DEFAULT_COMMAND }
        }

    /** La petición. En blanco vuelve a ser la de fábrica: una petición vacía no pide nada. */
    var prompt: String
        get() = state.prompt ?: AgentRequest.DEFAULT_PROMPT
        set(value) {
            state.prompt = value.trim().takeUnless { it.isEmpty() || it == AgentRequest.DEFAULT_PROMPT }
        }

    /** Si al encargarla pasa a *en curso*. Ver [AgentRequest.workingState]. */
    var moveToWorking: Boolean
        get() = state.moveToWorking
        set(value) {
            state.moveToWorking = value
        }

    override fun getState(): AgentState = state

    override fun loadState(state: AgentState) {
        this.state = state
    }

    companion object {
        fun getInstance(): AgentSettings = service()
    }
}
