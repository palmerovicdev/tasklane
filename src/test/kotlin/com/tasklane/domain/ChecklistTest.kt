package com.tasklane.domain

import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.DetailBlock
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.text.Checklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Las casillas `- [ ]` del cuerpo (2.11.0): se reconocen, se pintan sin la marca y se marcan. */
class ChecklistTest {

    private val reducer = TaskReducer(Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC))
    private val repo = RepoKey.ROOT

    @Test
    fun `reconoce las casillas de GitHub y nada mas`() {
        assertEquals(Checklist.Item(false, 3, 6), Checklist.parse("- [ ] algo"))
        assertEquals(Checklist.Item(true, 3, 6), Checklist.parse("* [x] algo"))
        assertEquals(true, Checklist.parse("  + [X] algo")?.checked)
        assertEquals(Checklist.Item(false, 4, 7), Checklist.parse("1. [ ] algo"))
        assertNotNull("sola, sin texto", Checklist.parse("- [ ]"))

        assertNull(Checklist.parse("- algo"))
        assertNull(Checklist.parse("-[ ] algo"))
        assertNull(Checklist.parse("- [ ]algo"))
        assertNull(Checklist.parse("- [y] algo"))
        assertNull(Checklist.parse("[ ] algo"))
        assertNull("diez cifras ya no son una lista", Checklist.parse("1234567890. [ ] algo"))
    }

    @Test
    fun `marcar escribe la x y desmarcar la quita`() {
        val body = "Compra\n- [ ] pan\n- [x] leche"
        val pan = body.indexOf("[ ]") + 1
        val marcado = Checklist.toggle(body, pan)
        assertEquals("Compra\n- [x] pan\n- [x] leche", marcado)
        assertEquals(body, Checklist.toggle(marcado!!, pan))
    }

    @Test
    fun `no escribe donde ya no hay una casilla`() {
        assertNull(Checklist.toggle("Compra\n- pan", 9))
        assertNull(Checklist.toggle("corto", 99))
    }

    @Test
    fun `el detalle lleva la casilla sin la marca y apunta a su x`() {
        val task = reducer.step(Model(), TaskCommand.Create(repo, "Compra\n- [ ] pan\n  - [x] leche")).tasks.single()
        val lines = task.detailBlocks.filterIsInstance<DetailBlock.Text>()

        assertEquals(listOf("pan", "leche"), lines.map { it.text })
        assertEquals(false, lines[0].check?.checked)
        assertEquals(true, lines[1].check?.checked)
        assertEquals('x', task.body[lines[1].check!!.offset])
        assertEquals(1 to 2, task.checklist)
    }

    @Test
    fun `un titulo que es una casilla se reconoce`() {
        val task = reducer.step(Model(), TaskCommand.Create(repo, "- [ ] pan\n- [ ] leche")).tasks.single()
        val check = assertNotNull(task.titleCheck).let { task.titleCheck!! }
        assertFalse(check.checked)
        assertEquals("pan", task.title.substring(check.text))
        assertEquals(' ', task.body[check.offset])
    }

    @Test
    fun `pulsar la casilla cambia el cuerpo y lo derivado`() {
        val creada = reducer.step(Model(), TaskCommand.Create(repo, "Compra\n- [ ] pan"))
        val task = creada.tasks.single()
        val offset = task.detailBlocks.filterIsInstance<DetailBlock.Text>().single().check!!.offset

        val marcada = reducer.step(creada, TaskCommand.ToggleCheck(repo, task.id, offset)).tasks.single()

        assertEquals("Compra\n- [x] pan", marcada.body)
        assertTrue(marcada.detailBlocks.filterIsInstance<DetailBlock.Text>().single().check!!.checked)
        assertSame(creada, reducer.step(creada, TaskCommand.ToggleCheck(repo, task.id, 0)))
    }

    @Test
    fun `lo que va entre vallas no cuenta`() {
        assertEquals(0 to 1, Checklist.progress("- [ ] fuera\n```\n- [x] dentro\n```"))
    }
}
