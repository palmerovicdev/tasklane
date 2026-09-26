package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.sqlite.StoreFixture.task
import com.tasklane.data.sqlite.StoreFixture.withStore
import com.tasklane.domain.command.Change
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Deshacer y rehacer contra una base de verdad (2.16.0): lo mismo que hace
 * `TaskService.applyLocked` con un gesto —leer, planificar, apuntar lo que cambia y
 * escribir, en una transacción—, sin el IDE. Lo que se prueba aquí es que lo que vuelve
 * del almacén es **la misma tarea**, con sus etiquetas y sus anclas, y que el orden
 * manual vuelve a su sitio.
 */
class UndoRoundTripTest {

    private val reducer = TaskReducer(Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC))
    private val todo = TasklaneConfig.TODO
    private val manual = CONFIG.copy(
        states = CONFIG.states.map { if (it.id == todo) it.copy(manualOrder = true) else it },
    ).normalized()

    private fun TaskStore.recorded(command: TaskCommand, config: TasklaneConfig = CONFIG): Change = write {
        val subject = TaskReducer.Subject(config, tasks(reducer.targetsOf(command))) { nextOrder(REPO) }
        val plan = reducer.plan(subject, command)
        val moves = plan.mutations.filterIsInstance<Mutation.Place>().mapNotNull { place ->
            neighbours(place.id)?.let { (above, below) -> TaskCommand.Move(place.repo, place.id, above, below) }
        }
        Change.of(subject.tasks, plan.mutations, moves).also { apply(config, plan.mutations) }
    }

    private fun order(db: TaskDb, state: StateId = todo): List<String> =
        db.reader.rows("SELECT id FROM task WHERE repo = ? AND state = ? ORDER BY bookmarked DESC, ord DESC, id DESC", REPO.value, state.value) {
            it.getString(0).orEmpty()
        }

    @Test
    fun `borrar, deshacer y rehacer, con etiquetas y anclas`() = withStore { store, _ ->
        val original = task(
            "t1",
            body = "Arreglar el login\n![](tasklane:${"a".repeat(64)})",
            tags = listOf("api", "urgente"),
            anchors = listOf(CodeAnchor("src/Login.kt", 42, 0, "fun login()", span = 3)),
            order = 7000,
        )
        store.apply(CONFIG, listOf(Mutation.Upsert(listOf(original))))

        val deleted = store.recorded(TaskCommand.Delete(REPO, listOf(original.id)))
        assertNull(store.task(original.id))

        val redo = store.recorded(TaskCommand.Revert(deleted))
        assertEquals(original, store.task(original.id))

        store.recorded(TaskCommand.Revert(redo))
        assertNull(store.task(original.id))
    }

    @Test
    fun `mover una seleccion a Done y deshacerlo devuelve las fechas`() = withStore { store, _ ->
        val tasks = (1..3).map { task("t$it", updatedAt = Instant.parse("2026-09-0${it}T10:00:00Z")) }
        store.apply(CONFIG, listOf(Mutation.Upsert(tasks)))

        val moved = store.recorded(TaskCommand.Batch(tasks.map { TaskCommand.ChangeState(REPO, it.id, TasklaneConfig.DONE) }))
        assertEquals(3, moved.rows.size)
        assertEquals(0, store.countOpen())

        val redo = store.recorded(TaskCommand.Revert(moved))
        assertEquals(tasks, store.tasks(tasks.map { it.id }).sortedBy { it.id.value })
        assertEquals(3, store.countOpen())

        store.recorded(TaskCommand.Revert(redo))
        assertEquals(0, store.countOpen())
    }

    @Test
    fun `reordenar, deshacer y rehacer, aunque haya que reespaciar`() = withStore { store, db ->
        store.apply(manual, listOf(Mutation.Upsert(listOf(task("a", order = 11), task("b", order = 10), task("c", order = 9)))))

        val moved = store.recorded(TaskCommand.Move(REPO, TaskId("c"), TaskId("a"), TaskId("b")), manual)
        assertEquals(listOf("a", "c", "b"), order(db))

        val redo = store.recorded(TaskCommand.Revert(moved), manual)
        assertEquals(listOf("a", "b", "c"), order(db))

        store.recorded(TaskCommand.Revert(redo), manual)
        assertEquals(listOf("a", "c", "b"), order(db))
    }

    /**
     * Soltar dos tarjetas en otra columna del tablero, a mano, entre dos de allí (2.17.0): lo
     * que manda `TasklanePanel.receive`. Un `⌘Z` las devuelve a su columna **y a su sitio**,
     * porque las vecinas que se guardan son las de antes de cambiar de estado.
     */
    @Test
    fun `soltar en otra columna a mano, deshacer y rehacer`() = withStore { store, db ->
        val doing = TasklaneConfig.DOING
        val board = CONFIG.copy(
            states = CONFIG.states.map { if (it.id == todo || it.id == doing) it.copy(manualOrder = true) else it },
        ).normalized()
        store.apply(
            board,
            listOf(
                Mutation.Upsert(
                    listOf(
                        task("a", order = 40),
                        task("b", order = 30),
                        task("c", order = 20),
                        task("d", order = 10),
                        task("x", state = doing, order = 20),
                        task("y", state = doing, order = 10),
                    ),
                ),
            ),
        )
        val b = TaskId("b")
        val c = TaskId("c")
        val drop = TaskCommand.Batch(
            listOf(
                TaskCommand.ChangeState(REPO, b, doing),
                TaskCommand.ChangeState(REPO, c, doing),
                TaskCommand.Move(REPO, b, TaskId("x"), TaskId("y")),
                TaskCommand.Move(REPO, c, b, TaskId("y")),
            ),
        )

        val dropped = store.recorded(drop, board)
        assertEquals(listOf("x", "b", "c", "y"), order(db, doing))
        assertEquals(listOf("a", "d"), order(db))

        val redo = store.recorded(TaskCommand.Revert(dropped), board)
        assertEquals(listOf("a", "b", "c", "d"), order(db))
        assertEquals(listOf("x", "y"), order(db, doing))

        store.recorded(TaskCommand.Revert(redo), board)
        assertEquals(listOf("x", "b", "c", "y"), order(db, doing))
        assertEquals(listOf("a", "d"), order(db))
    }

    @Test
    fun `crear y deshacer no deja rastro, y rehacer la devuelve igual`() = withStore { store, _ ->
        val created = store.recorded(TaskCommand.Create(REPO, "Nueva", tags = listOf("x"), id = TaskId("n")))
        val task = store.task(TaskId("n"))!!

        val redo = store.recorded(TaskCommand.Revert(created))
        assertNull(store.task(TaskId("n")))
        assertEquals(0, store.countOf(REPO))

        store.recorded(TaskCommand.Revert(redo))
        assertEquals(task, store.task(TaskId("n")))
    }

    private fun TaskStore.countOpen(): Int = tasks(listOf("t1", "t2", "t3").map(::TaskId)).count { it.completedAt == null }
}
