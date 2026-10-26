package com.tasklane.domain

import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.TriggerParser
import com.tasklane.domain.text.TriggerParser.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriggerParserTest {

    private val config = TasklaneConfig.DEFAULT
    private val low = Target.Priority(TasklaneConfig.LOW)
    private val normal = Target.Priority(TasklaneConfig.NORMAL)
    private val high = Target.Priority(TasklaneConfig.HIGH)
    private val doing = Target.State(TasklaneConfig.DOING)

    /** La palabra que cierra el separador tecleado al final de [typed]. */
    private fun typed(typed: String) = TriggerParser.endingAt(typed, typed.length - 1, config)

    @Test
    fun `al principio, como antes`() {
        val match = typed("!!! ")!!
        assertEquals(high, match.target)
        assertEquals(0 to 4, TriggerParser.cut("!!! ", match))
    }

    @Test
    fun `en cualquier sitio del texto`() {
        assertEquals(high, typed("Resolver el fallo !!! ")?.target)
        assertEquals(doing, typed("Revisar @doing ")?.target)
        // Y la tarea queda sin el trigger ni un hueco doble.
        val text = "Revisar !!! el login"
        val match = TriggerParser.endingAt(text, 11, config)!!
        assertEquals("Revisar el login", TriggerParser.remove(text, match))
    }

    @Test
    fun `la palabra entera es el trigger`() {
        assertEquals(high, typed("!!! ")?.target)
        assertEquals(normal, typed("!! ")?.target)
        assertEquals(low, typed("! ")?.target)
        // El caso que justifica la regla: `!importante` es una palabra, no un trigger.
        assertNull(typed("!importante "))
        assertNull(typed("Ya!!! "))
        assertNull(typed("@doing. "))
        assertNull(typed("![captura](a.png) "))
    }

    @Test
    fun `sin mirar mayusculas`() {
        assertEquals(doing, typed("@Doing ")?.target)
        assertEquals("@Doing", typed("@Doing ")?.trigger)
    }

    @Test
    fun `un salto de linea tambien cierra la palabra y se queda`() {
        val text = "Revisar !!!\nDetalle"
        val match = TriggerParser.endingAt(text, 11, config)!!
        assertEquals(high, match.target)
        assertEquals("Revisar\nDetalle", TriggerParser.remove(text, match))
    }

    @Test
    fun `el que se quedo al final sin separador`() {
        val match = TriggerParser.trailing("Revisar el login @doing", config)!!
        assertEquals(doing, match.target)
        assertEquals("Revisar el login", TriggerParser.remove("Revisar el login @doing", match))
        assertNull(TriggerParser.trailing("Revisar el login", config))
    }

    @Test
    fun `nada dentro de codigo`() {
        assertNull(typed("Mirar `a !! ")?.target)
        assertNull(typed("```\nif (!!x) !! ")?.target)
        assertEquals(high, typed("Mirar `a` !!! ")?.target)
    }

    @Test
    fun `el interruptor general apaga los triggers`() {
        val off = config.copy(triggersEnabled = false)
        assertNull(TriggerParser.endingAt("!!! ", 3, off))
        assertNull(TriggerParser.endingAt("@doing ", 6, off))
    }

    @Test
    fun `sin triggers configurados no hay nada que reconocer`() {
        val plain = config.copy(
            priorities = config.priorities.map { it.copy(trigger = null) },
            states = config.states.map { it.copy(trigger = null) },
        )
        assertNull(TriggerParser.endingAt("!!! ", 3, plain))
        assertNull(TriggerParser.endingAt("@doing ", 6, plain))
    }

    @Test
    fun `fuera de rango no revienta`() {
        assertNull(TriggerParser.endingAt("", 0, config))
        assertNull(TriggerParser.endingAt("!!!", 9, config))
        assertNull(TriggerParser.endingAt("a  ", 2, config))
    }
}
