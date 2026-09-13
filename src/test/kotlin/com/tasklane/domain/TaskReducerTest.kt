package com.tasklane.domain

import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TaskReducerTest {

    private val t0 = Instant.parse("2026-09-13T10:00:00Z")
    private val reducer = TaskReducer(Clock.fixed(t0, ZoneOffset.UTC))
    private val repo = RepoKey.ROOT
    private val empty = TasklaneSnapshot.EMPTY

    private fun create(body: String, snapshot: TasklaneSnapshot = empty) =
        reducer.reduce(snapshot, TaskCommand.Create(repo, body))

    @Test
    fun `crear usa estado y prioridad por defecto`() {
        val task = create("Arreglar login").activeTasks.single()
        assertEquals("Arreglar login", task.body)
        assertEquals(TasklaneConfig.TODO, task.stateId)
        assertEquals(TasklaneConfig.NORMAL, task.priorityId)
        assertEquals(t0, task.createdAt)
        assertNull("una tarea nueva no esta completada", task.completedAt)
    }

    @Test
    fun `un cuerpo vacio no crea tarea`() {
        assertSame(empty, create("   \n  "))
    }

    @Test
    fun `completar sella completedAt`() {
        val created = create("Algo")
        val id = created.activeTasks.single().id
        val done = reducer.reduce(created, TaskCommand.ToggleComplete(repo, id)).activeTasks.single()

        assertTrue(created.config.stateOrDefault(done.stateId).terminal)
        assertEquals(t0, done.completedAt)
    }

    @Test
    fun `reabrir conserva la fecha de finalizacion original`() {
        val created = create("Algo")
        val id = created.activeTasks.single().id
        val done = reducer.reduce(created, TaskCommand.ToggleComplete(repo, id))
        val reopened = reducer.reduce(done, TaskCommand.ToggleComplete(repo, id)).activeTasks.single()

        assertEquals(TasklaneConfig.TODO, reopened.stateId)
        assertNotNull(
            "salir de Done no debe destruir cuando se completo: la agrupacion historica depende de ello",
            reopened.completedAt,
        )
    }

    @Test
    fun `un estado desconocido se remapea sin perder la tarea`() {
        val huerfana = Task(
            id = TaskId("x"),
            repo = repo,
            body = "Tarea de otra configuracion",
            stateId = StateId("s-inexistente"),
            priorityId = PriorityId("p-inexistente"),
            createdAt = t0,
            updatedAt = t0,
        )

        val loaded = reducer.reduce(empty, TaskCommand.Loaded(repo, listOf(huerfana)))
        val task = loaded.activeTasks.single()

        assertEquals("la tarea sobrevive", 1, loaded.activeTasks.size)
        assertEquals(TasklaneConfig.TODO, task.stateId)
        assertEquals(TasklaneConfig.NORMAL, task.priorityId)
        assertEquals("s-inexistente", task.extra[TaskReducer.ORIG_STATE])
        assertEquals("p-inexistente", task.extra[TaskReducer.ORIG_PRIORITY])
    }

    @Test
    fun `borrar quita solo lo seleccionado`() {
        var s = create("uno")
        s = reducer.reduce(s, TaskCommand.Create(repo, "dos"))
        val victima = s.activeTasks.first { it.body == "uno" }.id

        val after = reducer.reduce(s, TaskCommand.Delete(repo, listOf(victima)))
        assertEquals(listOf("dos"), after.activeTasks.map { it.body })
    }

    @Test
    fun `los ordenes son dispersos para permitir insertar en medio`() {
        var s = create("uno")
        s = reducer.reduce(s, TaskCommand.Create(repo, "dos"))
        val orders = s.activeTasks.map { it.order }.sorted()
        assertEquals(listOf(1000L, 2000L), orders)
    }
}
