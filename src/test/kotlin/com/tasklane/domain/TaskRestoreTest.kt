package com.tasklane.domain

import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Deshacer un borrado (2.9.0): la tarea vuelve tal como estaba, no una copia nueva. */
class TaskRestoreTest {

    private val t0 = Instant.parse("2026-09-13T10:00:00Z")
    private val later = Instant.parse("2026-09-24T18:00:00Z")
    private val repo = RepoKey.ROOT

    @Test
    fun `restaurar devuelve la tarea con su id, sus fechas y su orden`() {
        val creada = TaskReducer(Clock.fixed(t0, ZoneOffset.UTC)).step(Model(), TaskCommand.Create(repo, "Arreglar login"))
        val task = creada.tasks.single()
        val reducer = TaskReducer(Clock.fixed(later, ZoneOffset.UTC))
        val borrada = reducer.step(creada, TaskCommand.Delete(repo, listOf(task.id)))
        assertEquals(0, borrada.tasks.size)

        val vuelta = reducer.step(borrada, TaskCommand.Restore(listOf(task)))

        assertEquals(listOf(task), vuelta.tasks)
    }

    @Test
    fun `restaurar dos veces no duplica`() {
        val reducer = TaskReducer(Clock.fixed(t0, ZoneOffset.UTC))
        val creada = reducer.step(Model(), TaskCommand.Create(repo, "Una"))
        val task = creada.tasks.single()

        assertSame(creada, reducer.step(creada, TaskCommand.Restore(listOf(task))))
    }

    @Test
    fun `una tarea cuyo estado ya no existe vuelve aparcada en el de por defecto`() {
        val reducer = TaskReducer(Clock.fixed(t0, ZoneOffset.UTC))
        val task = reducer.step(Model(), TaskCommand.Create(repo, "Huérfana")).tasks.single()
            .copy(stateId = StateId("gone"))

        val vuelta = reducer.step(Model(), TaskCommand.Restore(listOf(task))).tasks.single()

        assertEquals(TasklaneConfig.DEFAULT.normalized().defaultState.id, vuelta.stateId)
        assertEquals("gone", vuelta.extra[TaskReducer.ORIG_STATE])
        assertEquals(task.id, vuelta.id)
    }
}
