package com.tasklane.data.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.tasklane.domain.agent.Agent
import com.tasklane.domain.agent.AgentRequest

/**
 * Cómo se encarga una tarea a un agente (P26): la orden que se escribe en la terminal y la
 * petición que lleva.
 *
 * Es de la persona, como la marca del editor, pero **de la aplicación** y no de
 * `workspace.xml`: qué agente tiene uno instalado —`claude`, `codex`, `gemini`— es de su
 * máquina, no de cada proyecto, y obligar a escribirlo en todos sería el mismo coste que
 * `TasklaneDefaultsService` existe para evitar con los estados. Roamea con los ajustes del
 * IDE por lo mismo que la plantilla.
 *
 * `null` es «lo de fábrica», y no se guarda: si los agentes o la petición de fábrica mejoran,
 * los recibe quien no los haya tocado. La lista vacía es otra cosa —«no abras nada»— y sí se
 * guarda.
 *
 * Desde la 2.27.0 hay **varios agentes**, cada uno con su nombre y su orden; la petición sigue
 * siendo una para todos, porque habla de las herramientas de Tasklane y no del agente. La
 * lista va en `<agents>`, y `command` se queda como estaba hasta la 2.26: la orden del
 * **primero**, vacía si no hay ninguno. Así una versión anterior que reciba estos ajustes por
 * *Settings Sync* sigue encargando al primero, y una vacía dice lo mismo en las dos.
 *
 * Hasta la 2.25.0 había un `moveToWorking`, que pasaba la tarea a *en curso* al encargarla. Se
 * fue con el propio paso: quien lo tenga guardado lo arrastra en el XML y no se lee.
 */
@Service(Service.Level.APP)
@State(
    name = "TasklaneAgent",
    storages = [Storage("tasklane-agent.xml", roamingType = RoamingType.DEFAULT)],
    category = SettingsCategory.TOOLS,
)
class AgentSettings : PersistentStateComponent<AgentSettings.AgentState> {

    class AgentState {
        /** La orden del primer agente, o la única hasta la 2.26. Ver la cabecera. */
        @Attribute
        var command: String? = null

        @Attribute
        var prompt: String? = null

        /** Vacía, no se escribe: manda [command], que es lo que había antes de la lista. */
        @XCollection(propertyElementName = "agents", style = XCollection.Style.v2)
        var agents: MutableList<AgentBean> = mutableListOf()
    }

    @Tag("agent")
    class AgentBean {
        @Attribute var name: String = ""
        @Attribute var command: String = ""
    }

    private var state = AgentState()

    /**
     * Los agentes, en el orden del menú. Vacía: no hay *Hand Off to Agent*, sólo *Copy Agent
     * Prompt*. Un agente sin orden no es nada y no se guarda.
     */
    var agents: List<Agent>
        get() {
            val stored = state.agents.map { Agent(it.name, it.command) }.ifEmpty {
                // Un tasklane-agent.xml de antes de la lista: su orden es el único agente.
                listOf(Agent("", state.command ?: return AgentRequest.DEFAULT_AGENTS))
            }
            return stored.filter { it.command.isNotBlank() }
        }
        set(value) {
            val agents = value.map(Agent::trimmed).filter { it.command.isNotEmpty() }
            if (agents == AgentRequest.DEFAULT_AGENTS) {
                state.command = null
                state.agents = mutableListOf()
                return
            }
            state.command = agents.firstOrNull()?.command.orEmpty()
            state.agents = agents.mapTo(mutableListOf()) { agent ->
                AgentBean().apply {
                    name = agent.name
                    command = agent.command
                }
            }
        }

    /** La petición. En blanco vuelve a ser la de fábrica: una petición vacía no pide nada. */
    var prompt: String
        get() = state.prompt ?: AgentRequest.DEFAULT_PROMPT
        set(value) {
            state.prompt = value.trim().takeUnless { it.isEmpty() || it == AgentRequest.DEFAULT_PROMPT }
        }

    override fun getState(): AgentState = state

    override fun loadState(state: AgentState) {
        this.state = state
    }

    companion object {
        fun getInstance(): AgentSettings = service()
    }
}
