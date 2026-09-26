package com.tasklane.service

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.StatusBarChoice
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** El widget de la barra de estado (2.14.0): qué cuenta, cuándo vuelve a contar y cómo se guarda. */
class StatusCountsTest {

    private val config = TasklaneConfig.DEFAULT.normalized()

    @Test
    fun `de fabrica cuenta lo que queda por hacer, en el orden de las pestanas`() {
        assertEquals(
            listOf(TasklaneConfig.TODO, TasklaneConfig.DOING),
            StatusBarChoice().statesIn(config).map { it.id },
        )
    }

    @Test
    fun `lo de fabrica sigue a la configuracion`() {
        val review = TaskState(StateId("review"), "Review", 2)
        val grown = config.copy(states = config.states + review)
        assertEquals(
            listOf(TasklaneConfig.TODO, TasklaneConfig.DOING, review.id),
            StatusBarChoice().statesIn(grown).map { it.id },
        )
    }

    @Test
    fun `lo elegido a mano sale en el orden de las pestanas y sin lo que ya no existe`() {
        val choice = StatusBarChoice(states = setOf(TasklaneConfig.DONE, StateId("borrado"), TasklaneConfig.TODO))
        assertEquals(listOf(TasklaneConfig.TODO, TasklaneConfig.DONE), choice.statesIn(config).map { it.id })
    }

    @Test
    fun `ningun estado elegido no es lo de fabrica`() {
        assertEquals(emptyList<TaskState>(), StatusBarChoice(states = emptySet()).statesIn(config))
    }

    private val now = Instant.parse("2026-09-25T12:00:00Z")

    @Test
    fun `espera justo hasta que vence la siguiente`() {
        assertEquals(
            Duration.ofSeconds(90).plusMillis(1),
            StatusCounts.waitUntil(now, now.plusSeconds(90), now.plusSeconds(200)),
        )
    }

    @Test
    fun `sin nada por vencer, y con fechas lejanas, espera el techo`() {
        assertEquals(StatusCounts.MAX_WAIT, StatusCounts.waitUntil(now))
        assertEquals(StatusCounts.MAX_WAIT, StatusCounts.waitUntil(now, null, null))
        assertEquals(StatusCounts.MAX_WAIT, StatusCounts.waitUntil(now, now.plusSeconds(86_400)))
    }

    @Test
    fun `lo que ya paso no hace girar en vacio`() {
        assertEquals(StatusCounts.MIN_WAIT, StatusCounts.waitUntil(now, now.minusSeconds(60)))
        assertEquals(StatusCounts.MIN_WAIT, StatusCounts.waitUntil(now, now))
    }

    @Test
    fun `la eleccion vuelve de workspace xml tal como se guardo`() {
        for (choice in listOf(
            StatusBarChoice(),
            StatusBarChoice(states = emptySet(), overdue = false),
            StatusBarChoice(states = linkedSetOf(TasklaneConfig.DOING, StateId("s-1234"))),
        )) {
            val written = TasklaneWorkspaceService().apply { statusBar = choice }.state
            val xml = JDOMUtil.write(XmlSerializer.serialize(written))
            val read = TasklaneWorkspaceService().apply {
                loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), TasklaneWorkspaceService.WorkspaceState::class.java))
            }
            assertEquals(xml, choice, read.statusBar)
        }
    }

    @Test
    fun `un workspace xml de antes cuenta lo de fabrica`() {
        val read = TasklaneWorkspaceService().apply {
            loadState(XmlSerializer.deserialize(JDOMUtil.load("<TasklaneWorkspace/>"), TasklaneWorkspaceService.WorkspaceState::class.java))
        }
        assertNull(read.statusBar.states)
        assertEquals(true, read.statusBar.overdue)
    }
}
