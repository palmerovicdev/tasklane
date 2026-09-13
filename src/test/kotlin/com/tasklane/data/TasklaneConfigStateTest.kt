package com.tasklane.data

import com.intellij.util.xmlb.XmlSerializer
import com.tasklane.data.config.TasklaneConfigState
import com.tasklane.data.config.toDomain
import com.tasklane.data.config.toState
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TasklaneConfigStateTest {

    @Test
    fun `ida y vuelta conserva la configuracion de fabrica`() {
        val decoded = TasklaneConfig.DEFAULT.toState().toDomain()
        assertEquals(TasklaneConfig.DEFAULT, decoded)
    }

    @Test
    fun `el orden sale de la posicion en la lista, no de un atributo`() {
        // `order` deliberadamente mentiroso: el fichero no lo guarda, asi que al
        // volver tiene que reconstruirse desde la posicion.
        val config = TasklaneConfig(
            states = listOf(
                TaskState(StateId("a"), "A", 99, isDefault = true),
                TaskState(StateId("b"), "B", 99),
            ),
            priorities = listOf(
                TaskPriority(PriorityId("p"), "P", 77, 0x101010, 0x202020, isDefault = true),
                TaskPriority(PriorityId("q"), "Q", 77, 0x303030, 0x404040),
            ),
        )

        val decoded = config.toState().toDomain()!!
        assertEquals(listOf(0, 1), decoded.states.map { it.order })
        assertEquals(listOf(0, 1), decoded.priorities.map { it.order })
    }

    @Test
    fun `los colores viajan en hexadecimal legible`() {
        val state = TasklaneConfig.DEFAULT.toState()
        assertEquals("6C8EBF", state.priorities.first().colorLight)
        assertEquals("7FA8CC", state.priorities.first().colorDark)
    }

    @Test
    fun `un color con ceros a la izquierda no pierde digitos`() {
        val config = TasklaneConfig(
            states = TasklaneConfig.DEFAULT.states,
            priorities = listOf(TaskPriority(PriorityId("p"), "P", 0, 0x000102, 0x0000FF, isDefault = true)),
        )
        val decoded = config.toState().toDomain()!!
        assertEquals(0x000102, decoded.priorities.single().colorLight)
        assertEquals(0x0000FF, decoded.priorities.single().colorDark)
    }

    @Test
    fun `un enum desconocido cae al valor seguro en vez de reventar`() {
        val state = TasklaneConfig.DEFAULT.toState()
        state.states.first().grouping = "BY_MOON_PHASE"
        state.states.first().anchor = "WHENEVER"

        val decoded = state.toDomain()!!
        assertEquals(Grouping.NONE, decoded.states.first().grouping)
        assertEquals(DateAnchor.UPDATED, decoded.states.first().anchor)
    }

    @Test
    fun `un fichero sin estados o sin prioridades no produce una config a medias`() {
        // Devolver null es lo que deja que el servicio siembre desde la plantilla.
        assertNull(TasklaneConfigState().toDomain())
        assertNull(TasklaneConfig.DEFAULT.toState().also { it.states.clear() }.toDomain())
        assertNull(TasklaneConfig.DEFAULT.toState().also { it.priorities.clear() }.toDomain())
    }

    @Test
    fun `una entrada sin id se descarta y las demas sobreviven`() {
        val state = TasklaneConfig.DEFAULT.toState()
        state.states.first().id = ""

        val decoded = state.toDomain()!!
        assertEquals(listOf("Doing", "Done"), decoded.states.map { it.name })
    }

    @Test
    fun `las anotaciones xmlb serializan y recuperan el bean entero`() {
        // Esto es lo que la plataforma hace al escribir .idea/tasklane.xml. Testearlo
        // aqui es lo unico que detecta una anotacion mal puesta antes de que el
        // fichero salga silenciosamente vacio.
        val element = XmlSerializer.serialize(TasklaneConfig.DEFAULT.toState())
        val roundTripped = XmlSerializer.deserialize(element, TasklaneConfigState::class.java)

        assertEquals(TasklaneConfig.DEFAULT, roundTripped.toDomain())
    }

    @Test
    fun `la profundidad de deteccion viaja con la configuracion del proyecto`() {
        val config = TasklaneConfig.DEFAULT.copy(repoDepth = 3)
        assertEquals(3, config.toState().toDomain()!!.repoDepth)
    }

    @Test
    fun `una profundidad absurda se recorta en vez de romper el selector`() {
        assertEquals(
            TasklaneConfig.MAX_REPO_DEPTH,
            TasklaneConfig.DEFAULT.copy(repoDepth = 99).normalized().repoDepth,
        )
        assertEquals(0, TasklaneConfig.DEFAULT.copy(repoDepth = -1).normalized().repoDepth)
    }
}
