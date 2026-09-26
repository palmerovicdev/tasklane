package com.tasklane.data

import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import com.tasklane.data.config.TasklaneConfigState
import com.tasklane.data.config.toDomain
import com.tasklane.data.config.toState
import com.tasklane.domain.model.TagColor
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El color de las etiquetas en `tasklane.xml` (P30). */
class TagColorConfigTest {

    private val blue = TagColor(0x0000AA, 0x6666FF)
    private val red = TagColor(0xAA0000, 0xFF6666)

    @Test
    fun `se busca sin mirar mayusculas ni la almohadilla`() {
        val config = TasklaneConfig.DEFAULT.copy(tagColors = mapOf("#API " to blue)).normalized()
        assertEquals(mapOf("api" to blue), config.tagColors)
        assertEquals(blue, config.tagColor("api"))
        assertEquals(blue, config.tagColor("Api"))
        assertNull(config.tagColor("apis"))
    }

    @Test
    fun `ida y vuelta por el fichero, por nombre y en hexadecimal`() {
        val config = TasklaneConfig.DEFAULT.copy(tagColors = mapOf("ui" to red, "api" to blue)).normalized()
        val state = config.toState()
        assertEquals(listOf("api", "ui"), state.tags.map { it.name })
        assertEquals("0000AA", state.tags.first().colorLight)
        assertEquals("6666FF", state.tags.first().colorDark)
        assertEquals(config, state.toDomain())

        val xml = XmlSerializer.serialize(state)
        assertEquals(config, XmlSerializer.deserialize(xml, TasklaneConfigState::class.java).toDomain())
    }

    /** Con el filtro con el que la plataforma escribe un `PersistentStateComponent`. */
    @Test
    fun `un proyecto sin colores no escribe nada nuevo`() {
        val element = XmlSerializer.serialize(TasklaneConfig.DEFAULT.toState(), SkipDefaultsSerializationFilter())
        val xml = com.intellij.openapi.util.JDOMUtil.write(element)
        assertFalse(xml, xml.contains("tags"))
        assertTrue(TasklaneConfig.DEFAULT.toState().tags.isEmpty())
    }

    @Test
    fun `una fila sin nombre se tira`() {
        val state = TasklaneConfig.DEFAULT.toState()
        state.tags = mutableListOf(
            com.tasklane.data.config.TagBean().apply { name = " " },
            com.tasklane.data.config.TagBean().apply {
                name = "api"
                colorLight = "0000AA"
                colorDark = "6666FF"
            },
        )
        assertEquals(mapOf("api" to blue), state.toDomain()!!.tagColors)
    }
}
