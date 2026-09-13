package com.tasklane.domain

import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.TriggerParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriggerParserTest {

    private val config = TasklaneConfig.DEFAULT
    private val low = TasklaneConfig.LOW
    private val normal = TasklaneConfig.NORMAL
    private val high = TasklaneConfig.HIGH

    @Test
    fun `el trigger selecciona su prioridad y se consume`() {
        val text = "!!! Resolver el fallo"
        assertEquals(high, TriggerParser.match(text, config)?.priorityId)
        assertEquals("Resolver el fallo", TriggerParser.strip(text, config))
    }

    @Test
    fun `gana la coincidencia mas larga`() {
        assertEquals(high, TriggerParser.match("!!! algo", config)?.priorityId)
        assertEquals(normal, TriggerParser.match("!! algo", config)?.priorityId)
        assertEquals(low, TriggerParser.match("! algo", config)?.priorityId)
    }

    @Test
    fun `sin separador detras no dispara`() {
        // El caso que justifica la regla: `!importante` es una palabra, no un trigger.
        assertNull(TriggerParser.match("!importante revisar", config))
        assertEquals("!importante revisar", TriggerParser.strip("!importante revisar", config))
    }

    @Test
    fun `un texto que es solo el trigger no dispara todavia`() {
        // Mientras se teclea `!!!` la prioridad no debe saltar: aun no hay tarea.
        assertNull(TriggerParser.match("!!!", config))
    }

    @Test
    fun `un salto de linea no cuenta como separador`() {
        assertNull(TriggerParser.match("!!!\nResolver", config))
    }

    @Test
    fun `el interruptor general apaga los triggers`() {
        assertNull(TriggerParser.match("!!! algo", config.copy(triggersEnabled = false)))
        assertEquals("!!! algo", TriggerParser.strip("!!! algo", config.copy(triggersEnabled = false)))
    }

    @Test
    fun `sin triggers configurados no hay nada que reconocer`() {
        val plain = config.copy(priorities = config.priorities.map { it.copy(trigger = null) })
        assertNull(TriggerParser.match("!!! algo", plain))
    }

    @Test
    fun `solo se comen los espacios que separan, no la estructura del cuerpo`() {
        assertEquals("Titulo\n\nDetalle", TriggerParser.strip("!!   Titulo\n\nDetalle", config))
    }

    @Test
    fun `un texto sin trigger sale intacto`() {
        assertEquals("Revisar el PR", TriggerParser.strip("Revisar el PR", config))
    }
}
