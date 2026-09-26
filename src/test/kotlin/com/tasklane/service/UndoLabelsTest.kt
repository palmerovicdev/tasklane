package com.tasklane.service

import com.tasklane.domain.Model
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.recorded
import com.tasklane.domain.step
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lo que dice la barra de estado al deshacer (2.16.0). */
class UndoLabelsTest {

    private val reducer = TaskReducer()
    private val repo = RepoKey.ROOT
    private val config = TasklaneConfig.DEFAULT.normalized()

    private val three = listOf("a", "b", "c").fold(Model()) { m, body -> reducer.step(m, TaskCommand.Create(repo, body)) }

    private fun label(model: Model, command: TaskCommand): UndoLabels.Label =
        UndoLabels.of(command, reducer.recorded(model, command).second, config)

    private fun all(make: (com.tasklane.domain.model.Task) -> com.tasklane.domain.command.RepoScoped) =
        TaskCommand.Batch(three.tasks.map(make))

    @Test
    fun `mover dice a donde y cuantas`() {
        val done = config.state(TasklaneConfig.DONE)!!.name
        assertEquals(
            UndoLabels.Label("undo.what.state", listOf(3, done)),
            label(three, all { TaskCommand.ChangeState(repo, it.id, TasklaneConfig.DONE) }),
        )
    }

    /**
     * Soltar una tarjeta en una columna a mano del tablero (2.17.0) guarda sus vecinas, como
     * reordenar, pero lo que se hizo es moverla de columna, y eso es lo que se dice.
     */
    @Test
    fun `soltar en otra columna dice a donde, no que se reordeno`() {
        val (first, second) = three.tasks
        val drop = TaskCommand.Batch(
            listOf(
                TaskCommand.ChangeState(repo, first.id, TasklaneConfig.DOING),
                TaskCommand.Move(repo, first.id, second.id, null),
            ),
        )
        val change = reducer.recorded(three, drop).second.copy(moves = listOf(TaskCommand.Move(repo, first.id, null, second.id)))
        val doing = config.state(TasklaneConfig.DOING)!!.name

        assertEquals(UndoLabels.Label("undo.what.state", listOf(1, doing)), UndoLabels.of(drop, change, config))

        // Y colocar a secas sigue siendo reordenar.
        val move = TaskCommand.Move(repo, first.id, second.id, null)
        assertEquals(
            UndoLabels.Label("undo.what.reordered"),
            UndoLabels.of(move, com.tasklane.domain.command.Change(moves = listOf(move)), config),
        )
    }

    @Test
    fun `solo cuentan las que cambiaron`() {
        val one = reducer.step(three, TaskCommand.ChangeState(repo, three.tasks.first().id, TasklaneConfig.DOING))
        val doing = config.state(TasklaneConfig.DOING)!!.name

        assertEquals(
            UndoLabels.Label("undo.what.state", listOf(2, doing)),
            label(one, all { TaskCommand.ChangeState(repo, it.id, TasklaneConfig.DOING) }),
        )
    }

    @Test
    fun `completar y reabrir se distinguen`() {
        val done = reducer.step(three, all { TaskCommand.ToggleComplete(repo, it.id) })

        assertEquals(UndoLabels.Label("undo.what.completed", listOf(3)), label(three, all { TaskCommand.ToggleComplete(repo, it.id) }))
        assertEquals(UndoLabels.Label("undo.what.reopened", listOf(3)), label(done, all { TaskCommand.ToggleComplete(repo, it.id) }))
    }

    @Test
    fun `crear, borrar y editar`() {
        val id = three.tasks.first().id
        assertEquals(UndoLabels.Label("undo.what.created", listOf(1)), label(Model(), TaskCommand.Create(repo, "x")))
        assertEquals(UndoLabels.Label("undo.what.deleted", listOf(3)), label(three, all { TaskCommand.Delete(repo, listOf(it.id)) }))
        assertEquals("undo.what.edited", label(three, TaskCommand.UpdateBody(repo, id, "otra")).key)
    }

    @Test
    fun `marcar y la prioridad`() {
        val high = config.priority(TasklaneConfig.HIGH)!!.name
        assertEquals(UndoLabels.Label("undo.what.bookmarked", listOf(3)), label(three, all { TaskCommand.ToggleBookmark(repo, it.id) }))
        assertEquals(
            UndoLabels.Label("undo.what.priority", listOf(3, high)),
            label(three, all { TaskCommand.ChangePriority(repo, it.id, TasklaneConfig.HIGH) }),
        )
    }
}
