package com.tasklane.data

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import com.tasklane.data.config.AgentSettings
import com.tasklane.domain.agent.Agent
import com.tasklane.domain.agent.AgentRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los agentes de *Hand Off to Agent* (2.27.0) tal como se guardan en `tasklane-agent.xml`, y lo
 * que se lee de uno de antes de la lista, cuando había una sola orden.
 */
class AgentSettingsTest {

    private fun read(xml: String) = AgentSettings().apply {
        loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), AgentSettings.AgentState::class.java))
    }

    private fun roundTrip(write: AgentSettings.() -> Unit): Pair<String, AgentSettings> {
        // El filtro con el que la plataforma guarda un PersistentStateComponent.
        val xml = JDOMUtil.write(XmlSerializer.serialize(AgentSettings().apply(write).state, SkipDefaultsSerializationFilter()))
        return xml to read(xml)
    }

    @Test
    fun `sin tocar nada hay un agente, Claude Code, y no se escribe`() {
        val (xml, read) = roundTrip { agents = AgentRequest.DEFAULT_AGENTS }

        assertEquals(AgentRequest.DEFAULT_AGENTS, read.agents)
        assertFalse(xml, "command" in xml || "agent " in xml)
    }

    @Test
    fun `varios agentes vuelven en su orden y con su nombre`() {
        val list = listOf(Agent("Codex", "codex {prompt}"), Agent("", "gemini -i {prompt}"), Agent("Claude Code", "claude {prompt}"))
        val (xml, read) = roundTrip { agents = list }

        assertEquals(xml, list, read.agents)
    }

    @Test
    fun `la orden de antes es la del primero, para las versiones sin lista`() {
        val (xml, _) = roundTrip { agents = listOf(Agent("Codex", "codex {prompt}"), Agent("Gemini", "gemini")) }

        assertTrue(xml, "command=\"codex {prompt}\"" in xml)
    }

    @Test
    fun `sin agentes se guarda la orden vacia y no hay ninguno`() {
        val (xml, read) = roundTrip { agents = emptyList() }

        assertTrue(xml, "command=\"\"" in xml)
        assertEquals(emptyList<Agent>(), read.agents)
    }

    @Test
    fun `una fila sin orden no se guarda y los espacios se van`() {
        val (_, read) = roundTrip { agents = listOf(Agent(" Codex ", "  codex  "), Agent("Nada", "   ")) }

        assertEquals(listOf(Agent("Codex", "codex")), read.agents)
    }

    @Test
    fun `un fichero de antes con su orden es un agente sin nombre`() {
        val read = read("""<TasklaneAgent command="codex --full-auto {prompt}" prompt="Haz {id}"/>""")

        assertEquals(listOf(Agent("", "codex --full-auto {prompt}")), read.agents)
        assertEquals("codex", read.agents.single().label)
        assertEquals("Haz {id}", read.prompt)
    }

    @Test
    fun `un fichero de antes con la orden vacia no tiene agentes`() {
        assertEquals(emptyList<Agent>(), read("""<TasklaneAgent command=""/>""").agents)
    }

    @Test
    fun `un fichero de antes sin orden tiene los de fabrica`() {
        assertEquals(AgentRequest.DEFAULT_AGENTS, read("<TasklaneAgent/>").agents)
    }
}
