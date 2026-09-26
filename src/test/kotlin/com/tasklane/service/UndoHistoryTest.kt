package com.tasklane.service

import com.tasklane.domain.command.Change
import com.tasklane.domain.command.RowChange
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** La pila de `⌘Z` (2.16.0): cada repositorio con lo suyo, y con tope. */
class UndoHistoryTest {

    private val back = RepoKey("backend")
    private val front = RepoKey("frontend")

    private fun task(id: String, repo: RepoKey) = Task(
        id = TaskId(id),
        repo = repo,
        body = id,
        stateId = StateId("s-todo"),
        priorityId = PriorityId("p-normal"),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /** Un paso que toca [rows] tareas de [repo], hecho con [repo] abierto. */
    private fun step(repo: RepoKey, rows: Int = 1, label: String = "x"): UndoHistory.Step {
        val change = Change((1..rows).map { RowChange(null, task("$label-$it", repo)) })
        return UndoHistory.Step(change, label, change.repos + repo)
    }

    @Test
    fun `deshacer saca lo ultimo del repositorio abierto`() {
        val history = UndoHistory()
        val first = step(back, label = "primero")
        val second = step(front, label = "segundo")
        history.record(first)
        history.record(second)

        assertSame("lo de frontend no es de backend", first, history.popUndo(back))
        assertNull(history.popUndo(back))
        assertTrue(history.canUndo(front))
    }

    @Test
    fun `un paso hecho buscando en todos es de los dos repositorios`() {
        val history = UndoHistory()
        // Con backend abierto se tocó una tarea de frontend.
        val change = Change(listOf(RowChange(null, task("f", front))))
        val step = UndoHistory.Step(change, "cruzado", change.repos + back)
        history.record(step)

        assertTrue(history.canUndo(back))
        assertTrue(history.canUndo(front))
    }

    @Test
    fun `un gesto nuevo borra lo que habia por rehacer, pero solo en su repositorio`() {
        val history = UndoHistory()
        history.undone(step(back, label = "rehacer-back"))
        history.undone(step(front, label = "rehacer-front"))

        history.record(step(back))

        assertFalse(history.canRedo(back))
        assertTrue(history.canRedo(front))
    }

    @Test
    fun `rehacer no borra lo que queda por rehacer`() {
        val history = UndoHistory()
        history.undone(step(back, label = "uno"))
        history.undone(step(back, label = "dos"))

        val redo = history.popRedo(back)!!
        history.redone(step(back, label = redo.label))

        assertEquals("uno", history.popRedo(back)?.label)
        assertEquals("dos", history.popUndo(back)?.label)
    }

    @Test
    fun `pasado el tope de pasos se olvida lo mas viejo`() {
        val history = UndoHistory(maxSteps = 3, maxRows = 1_000)
        (1..5).forEach { history.record(step(back, label = "p$it")) }

        assertEquals(listOf("p5", "p4", "p3"), generateSequence { history.popUndo(back)?.label }.toList())
    }

    @Test
    fun `pasado el tope de filas se olvida lo mas viejo`() {
        val history = UndoHistory(maxSteps = 100, maxRows = 10)
        history.record(step(back, rows = 6, label = "a"))
        history.record(step(back, rows = 3, label = "b"))
        history.record(step(back, rows = 4, label = "c"))

        assertEquals(7, history.undoRows())
        assertEquals(listOf("c", "b"), generateSequence { history.popUndo(back)?.label }.toList())
    }

    @Test
    fun `un paso que no cabe no se guarda y se lleva lo anterior de su repositorio`() {
        val history = UndoHistory(maxSteps = 100, maxRows = 10)
        history.record(step(back, label = "viejo"))
        history.record(step(front, label = "otro"))

        assertFalse(history.record(step(back, rows = 11)))

        assertFalse("después de algo sin vuelta, ⌘Z no deshace lo de detrás", history.canUndo(back))
        assertTrue(history.canUndo(front))
    }

    @Test
    fun `el aviso de borrar deshace su paso aunque haya otros encima`() {
        val history = UndoHistory()
        val deletion = step(back, label = "borrar")
        history.record(deletion)
        history.record(step(back, label = "mover"))

        assertTrue(history.take(deletion))
        assertFalse("ya no está", history.take(deletion))
        assertEquals("mover", history.popUndo(back)?.label)
    }

    @Test
    fun `olvidar un repositorio vacia sus dos pilas`() {
        val history = UndoHistory()
        history.record(step(back))
        history.undone(step(back))
        history.record(step(front))

        history.forget(setOf(back))

        assertFalse(history.canUndo(back))
        assertFalse(history.canRedo(back))
        assertTrue(history.canUndo(front))
    }
}
