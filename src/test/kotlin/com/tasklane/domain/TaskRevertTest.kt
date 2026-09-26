package com.tasklane.domain

import com.tasklane.domain.command.Change
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.command.RowChange
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Deshacer (2.16.0; el borrado, desde la 2.9.0): cada tarea vuelve a como estaba, campo a
 * campo y sin pisar lo que otro escribió después. Rehacer es deshacer lo que dejó el
 * deshacer, así que se prueba con lo mismo.
 */
class TaskRevertTest {

    private val t0 = Instant.parse("2026-09-13T10:00:00Z")
    private val later = Instant.parse("2026-09-24T18:00:00Z")
    private val latest = Instant.parse("2026-09-25T09:00:00Z")
    private val repo = RepoKey.ROOT

    private val atCreation = TaskReducer(Clock.fixed(t0, ZoneOffset.UTC))
    private val reducer = TaskReducer(Clock.fixed(later, ZoneOffset.UTC))
    private val agent = TaskReducer(Clock.fixed(latest, ZoneOffset.UTC))

    private fun created(vararg bodies: String): Model =
        bodies.fold(Model()) { model, body -> atCreation.step(model, TaskCommand.Create(repo, body)) }

    // ---------------------------------------------------------------- borrar (2.9.0)

    @Test
    fun `deshacer un borrado devuelve la tarea con su id, sus fechas y su orden`() {
        val creada = created("Arreglar login")
        val task = creada.tasks.single()
        val (borrada, change) = reducer.recorded(creada, TaskCommand.Delete(repo, listOf(task.id)))
        assertEquals(0, borrada.tasks.size)

        val vuelta = reducer.step(borrada, TaskCommand.Revert(change))

        assertEquals(listOf(task), vuelta.tasks)
    }

    @Test
    fun `deshacer dos veces un borrado no duplica`() {
        val creada = created("Una")
        val task = creada.tasks.single()
        val change = Change(listOf(RowChange(task, null)))

        assertSame(creada, reducer.step(creada, TaskCommand.Revert(change)))
    }

    @Test
    fun `una tarea cuyo estado ya no existe vuelve aparcada en el de por defecto`() {
        val task = created("Huérfana").tasks.single().copy(stateId = StateId("gone"))

        val vuelta = reducer.step(Model(), TaskCommand.Revert(Change(listOf(RowChange(task, null))))).tasks.single()

        assertEquals(TasklaneConfig.DEFAULT.normalized().defaultState.id, vuelta.stateId)
        assertEquals("gone", vuelta.extra[TaskReducer.ORIG_STATE])
        assertEquals(task.id, vuelta.id)
    }

    // ------------------------------------------------------------ crear y rehacer

    @Test
    fun `deshacer una tarea creada la borra, y rehacer la devuelve igual`() {
        val (creada, change) = atCreation.recorded(Model(), TaskCommand.Create(repo, "Nueva", id = TaskId("n")))
        val task = creada.tasks.single()
        assertNull(change.rows.single().before)

        val (deshecha, inverse) = reducer.recorded(creada, TaskCommand.Revert(change))
        assertTrue(deshecha.tasks.isEmpty())

        val rehecha = reducer.step(deshecha, TaskCommand.Revert(inverse))
        assertEquals(listOf(task), rehecha.tasks)
    }

    // --------------------------------------------------------------------- cambiar

    @Test
    fun `deshacer completar la deja exactamente como estaba, fechas incluidas`() {
        val creada = created("Cerrar el sprint")
        val task = creada.tasks.single()
        val (cerrada, change) = reducer.recorded(creada, TaskCommand.ToggleComplete(repo, task.id))
        assertEquals(later, cerrada.task(task.id)!!.completedAt)

        val vuelta = agent.step(cerrada, TaskCommand.Revert(change))

        // Sin `completedAt` y con el `updatedAt` de antes: vuelve a su grupo de fecha.
        assertEquals(task, vuelta.task(task.id))
    }

    @Test
    fun `deshacer y rehacer un cambio de estado van y vuelven`() {
        val creada = created("Ir y volver")
        val id = creada.tasks.single().id
        val (movida, change) = reducer.recorded(creada, TaskCommand.ChangeState(repo, id, TasklaneConfig.DOING))

        val (deshecha, inverse) = agent.recorded(movida, TaskCommand.Revert(change))
        assertEquals(creada.tasks, deshecha.tasks)

        val rehecha = agent.step(deshecha, TaskCommand.Revert(inverse))
        assertEquals(movida.tasks, rehecha.tasks)
    }

    @Test
    fun `deshacer respeta lo que otro escribio despues`() {
        val creada = created("Revisar el login")
        val id = creada.tasks.single().id
        val (cerrada, change) = reducer.recorded(creada, TaskCommand.ToggleComplete(repo, id))
        // Un agente, por MCP, le cambia el cuerpo: eso no está en la pila del usuario.
        val editada = agent.step(cerrada, TaskCommand.UpdateBody(repo, id, "Revisar el login y el logout"))

        val vuelta = reducer.step(editada, TaskCommand.Revert(change)).task(id)!!

        assertEquals("se reabre", TasklaneConfig.TODO, vuelta.stateId)
        assertNull(vuelta.completedAt)
        assertEquals("el cuerpo del agente se queda", "Revisar el login y el logout", vuelta.body)
        assertEquals("y su fecha de edición también", latest, vuelta.updatedAt)
    }

    @Test
    fun `lo que ya no vale lo de despues no se toca`() {
        val creada = created("Prioridad")
        val id = creada.tasks.single().id
        val (alta, change) = reducer.recorded(creada, TaskCommand.ChangePriority(repo, id, TasklaneConfig.HIGH))
        val baja = agent.step(alta, TaskCommand.ChangePriority(repo, id, TasklaneConfig.LOW))

        val vuelta = reducer.step(baja, TaskCommand.Revert(change))

        assertEquals(TasklaneConfig.LOW, vuelta.task(id)!!.priorityId)
    }

    @Test
    fun `deshacer lo que ya se deshizo no hace nada`() {
        val creada = created("Una vez")
        val id = creada.tasks.single().id
        val (marcada, change) = reducer.recorded(creada, TaskCommand.ToggleBookmark(repo, id))
        val deshecha = reducer.step(marcada, TaskCommand.Revert(change))

        assertSame(deshecha, reducer.step(deshecha, TaskCommand.Revert(change)))
    }

    @Test
    fun `una seleccion se deshace entera`() {
        val creadas = created("Una", "Dos", "Tres")
        val (marcadas, change) = reducer.recorded(
            creadas,
            TaskCommand.Batch(creadas.tasks.map { TaskCommand.ToggleBookmark(repo, it.id) }),
        )
        assertTrue(marcadas.tasks.all { it.bookmarked })
        assertEquals(3, change.rows.size)

        assertEquals(creadas.tasks, reducer.step(marcadas, TaskCommand.Revert(change)).tasks)
    }

    @Test
    fun `deshacer una casilla devuelve el cuerpo y lo que sale de el`() {
        val creada = atCreation.step(Model(), TaskCommand.Create(repo, "Pasos\n- [ ] uno https://example.com\n- [ ] dos"))
        val task = creada.tasks.single()
        val offset = task.body.indexOf("[ ]") + 1
        val (marcada, change) = reducer.recorded(creada, TaskCommand.ToggleCheck(repo, task.id, offset))
        assertEquals(1 to 2, marcada.task(task.id)!!.checklist)

        assertEquals(task, reducer.step(marcada, TaskCommand.Revert(change)).task(task.id))
    }

    @Test
    fun `deshacer una edicion del dialogo devuelve todos sus campos`() {
        val creada = created("Antes")
        val task = creada.tasks.single()
        val (editada, change) = reducer.recorded(
            creada,
            TaskCommand.UpdateTask(
                repo,
                task.id,
                body = "Después",
                stateId = TasklaneConfig.DOING,
                priorityId = TasklaneConfig.HIGH,
                tags = listOf("api"),
                dueDate = java.util.Optional.of(latest),
            ),
        )
        assertEquals("Después", editada.task(task.id)!!.body)

        assertEquals(task, reducer.step(editada, TaskCommand.Revert(change)).task(task.id))
    }

    @Test
    fun `lo que vuelve a un estado que ya no existe vuelve aparcado`() {
        val gone = StateId("gone")
        val creada = created("Aparcable")
        val task = creada.tasks.single()
        val antes = task.copy(stateId = gone)
        val change = Change(listOf(RowChange(antes, task)))

        val vuelta = reducer.step(creada, TaskCommand.Revert(change)).task(task.id)!!

        assertEquals(TasklaneConfig.DEFAULT.normalized().defaultState.id, vuelta.stateId)
        assertEquals("gone", vuelta.extra[TaskReducer.ORIG_STATE])
    }

    @Test
    fun `la marca de aparcada vuelve con el resto`() {
        val creada = created("Estaba aparcada")
        val aparcada = creada.tasks.single().copy(
            priorityId = TasklaneConfig.NORMAL,
            extra = mapOf(TaskReducer.ORIG_PRIORITY to "p-gone"),
        )
        val model = Model(tasks = listOf(aparcada))
        // Elegir a mano quita la marca; deshacerlo la devuelve.
        val (elegida, change) = reducer.recorded(model, TaskCommand.ChangePriority(repo, aparcada.id, PriorityId("p-high")))
        assertTrue(elegida.task(aparcada.id)!!.extra.isEmpty())

        assertEquals(aparcada, reducer.step(elegida, TaskCommand.Revert(change)).task(aparcada.id))
    }

    /**
     * *Tags ▸ Add…* sobre una selección (P31) se deshace tarea a tarea, y respeta la que
     * un agente cambió después: ésa se queda como la dejó él.
     */
    @Test
    fun `deshacer sumar una etiqueta respeta la que otro toco despues`() {
        val creadas = created("a", "b")
        val (a, b) = creadas.tasks.map { it.id }
        val (etiquetadas, change) = reducer.recorded(
            creadas,
            TaskCommand.Batch(listOf(TaskCommand.AddTags(repo, a, listOf("release")), TaskCommand.AddTags(repo, b, listOf("release")))),
        )
        val tocada = agent.step(etiquetadas, TaskCommand.AddTags(repo, b, listOf("ui")))

        val vuelta = reducer.step(tocada, TaskCommand.Revert(change))

        assertEquals(emptyList<String>(), vuelta.task(a)!!.tags)
        assertEquals(listOf("release", "ui"), vuelta.task(b)!!.tags)
    }

    // -------------------------------------------------------------------- reordenar

    @Test
    fun `deshacer un reordenar la coloca entre sus vecinas de antes`() {
        val creadas = created("a", "b", "c")
        val (a, b, c) = creadas.tasks.map { it.id }
        val change = Change(moves = listOf(TaskCommand.Move(repo, c, a, b)))

        val plan = reducer.plan(TaskReducer.Subject(creadas.config, creadas.tasks), TaskCommand.Revert(change))

        assertEquals(listOf(Mutation.Place(repo, c, a, b)), plan.mutations)
    }

    @Test
    fun `no se coloca una tarea que ya no esta`() {
        val change = Change(moves = listOf(TaskCommand.Move(repo, TaskId("gone"), TaskId("a"), null)))

        val plan = reducer.plan(TaskReducer.Subject(Model().config, emptyList()), TaskCommand.Revert(change))

        assertTrue(plan.isEmpty)
    }
}
