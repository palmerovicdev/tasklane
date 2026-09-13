package com.tasklane.domain

import com.tasklane.domain.model.ConfigProblem
import com.tasklane.domain.model.ConfigValidator
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {

    private fun priority(id: String, name: String = id, trigger: String? = null) =
        TaskPriority(PriorityId(id), name, 0, 0x000000, 0xFFFFFF, trigger)

    private fun config(
        states: List<TaskState> = TasklaneConfig.DEFAULT.states,
        priorities: List<TaskPriority> = TasklaneConfig.DEFAULT.priorities,
    ) = TasklaneConfig(states, priorities)

    @Test
    fun `la configuracion de fabrica es valida`() {
        assertEquals(emptyList<ConfigProblem>(), ConfigValidator.validate(TasklaneConfig.DEFAULT))
    }

    @Test
    fun `quedarse sin estados o sin prioridades es un problema`() {
        assertTrue(ConfigProblem.NoStates in ConfigValidator.validate(config(states = emptyList())))
        assertTrue(ConfigProblem.NoPriorities in ConfigValidator.validate(config(priorities = emptyList())))
    }

    @Test
    fun `un nombre en blanco se marca en su fila`() {
        val states = listOf(
            TaskState(StateId("a"), "ToDo", 0, isDefault = true),
            TaskState(StateId("b"), "   ", 1),
        )
        assertEquals(
            listOf(ConfigProblem.BlankStateName(1)),
            ConfigValidator.validate(config(states = states)),
        )
    }

    @Test
    fun `dos triggers iguales marcan el segundo, que es el recien escrito`() {
        val priorities = listOf(
            priority("a", trigger = "!"),
            priority("b", trigger = "!!"),
            priority("c", trigger = "!"),
        )
        assertEquals(
            listOf(ConfigProblem.DuplicateTrigger(2, "!")),
            ConfigValidator.validate(config(priorities = priorities)),
        )
    }

    @Test
    fun `un trigger prefijo de otro es legitimo`() {
        // La resolucion es por coincidencia mas larga, asi que ! y !!! conviven.
        val priorities = listOf(
            priority("a", trigger = "!"),
            priority("b", trigger = "!!"),
            priority("c", trigger = "!!!"),
        )
        assertEquals(emptyList<ConfigProblem>(), ConfigValidator.validate(config(priorities = priorities)))
    }

    @Test
    fun `un trigger con espacio no podria dispararse nunca`() {
        val priorities = listOf(priority("a", trigger = "muy urgente"))
        assertEquals(
            listOf(ConfigProblem.TriggerWithSpace(0, "muy urgente")),
            ConfigValidator.validate(config(priorities = priorities)),
        )
    }

    @Test
    fun `varias prioridades sin trigger no son un duplicado`() {
        val priorities = listOf(priority("a"), priority("b"), priority("c"))
        assertEquals(emptyList<ConfigProblem>(), ConfigValidator.validate(config(priorities = priorities)))
    }

    @Test
    fun `normalized deja exactamente un valor por defecto y el orden de la lista`() {
        val states = listOf(
            TaskState(StateId("a"), "A", 99),
            TaskState(StateId("b"), "B", 99, isDefault = true),
            TaskState(StateId("c"), "C", 99, isDefault = true),
        )
        val normalized = config(states = states).normalized()

        assertEquals(listOf(0, 1, 2), normalized.states.map { it.order })
        assertEquals(listOf(false, true, false), normalized.states.map { it.isDefault })
    }

    @Test
    fun `normalized sin ningun valor por defecto elige el primero`() {
        val states = listOf(TaskState(StateId("a"), "A", 0), TaskState(StateId("b"), "B", 1))
        assertEquals(StateId("a"), config(states = states).normalized().defaultState.id)
    }
}
