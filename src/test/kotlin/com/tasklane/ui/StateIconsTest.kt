package com.tasklane.ui

import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.ui.common.StateIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StateIconsTest {

    private val states = TasklaneConfig.DEFAULT.states

    @Test
    fun `de fabrica, vacio, medio y el visto`() {
        assertEquals(listOf(0f, 0.5f, null), states.map { StateIcons.progress(it, states) })
    }

    @Test
    fun `cuentan solo los abiertos, en el orden de las pestanas`() {
        val flow = listOf("backlog", "todo", "doing", "review").mapIndexed { i, id -> TaskState(StateId(id), id, i) } +
            TaskState(StateId("done"), "done", 4, terminal = true) +
            TaskState(StateId("dropped"), "dropped", 5, terminal = true)
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, null, null), flow.map { StateIcons.progress(it, flow) })
    }

    @Test
    fun `un estado que ya no esta cuenta como el primero`() {
        assertEquals(0f, StateIcons.progress(TaskState(StateId("gone"), "Gone", 9), states))
        assertNull(StateIcons.progress(TaskState(StateId("gone"), "Gone", 9, terminal = true), states))
    }

    @Test
    fun `el mismo avance es el mismo icono`() {
        assertSame(StateIcons.of(states[1], states), StateIcons.of(states[1].copy(name = "Otro"), states))
    }
}
