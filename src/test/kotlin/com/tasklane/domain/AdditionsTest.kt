package com.tasklane.domain

import com.tasklane.domain.command.Additions
import com.tasklane.domain.command.Change
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Lo que un gesto añadió (P35): de ahí salen los *Got It* de la primera ancla y de la
 * primera captura, así que lo que no es añadir —mover un ancla dentro de su fichero, editar
 * una tarea que ya tenía captura, borrar— no puede contar.
 */
class AdditionsTest {

    private val repo = RepoKey.ROOT
    private val reducer = TaskReducer(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))

    private val auth = CodeAnchor.of("src/Auth.kt", line = 41, text = "fun login() {")
    private val image = "![](tasklane:${"a".repeat(64)})"
    private val other = "![](tasklane:${"b".repeat(64)})"

    private fun created(body: String, anchors: List<CodeAnchor> = emptyList()): Pair<Model, TaskId> {
        val (model, _) = reducer.recorded(Model(), TaskCommand.Create(repo, body, anchors = anchors))
        return model to model.tasks.single().id
    }

    @Test
    fun `crear una tarea anclada añade su ancla`() {
        val (_, change) = reducer.recorded(Model(), TaskCommand.Create(repo, "Arreglar login", anchors = listOf(auth)))

        assertEquals(listOf("src/Auth.kt"), Additions.of(change).anchors.map { it.path })
    }

    @Test
    fun `crear con una captura en el cuerpo la cuenta`() {
        val (model, change) = reducer.recorded(Model(), TaskCommand.Create(repo, "Se ve mal\n$image"))

        assertEquals(setOf(model.tasks.single().id), Additions.of(change).withImages)
    }

    @Test
    fun `llevar el ancla a otra linea del mismo fichero no es crear una`() {
        val (model, id) = created("Arreglar login", listOf(auth))
        val moved = auth.copy(line = 80)

        val (_, change) = reducer.recorded(model, TaskCommand.UpdateTask(repo, id, anchors = listOf(moved)))

        assertTrue(Additions.of(change).anchors.isEmpty())
    }

    @Test
    fun `un ancla a otro fichero si cuenta, y sola`() {
        val (model, id) = created("Arreglar login", listOf(auth))
        val routes = CodeAnchor.of("src/Routes.kt", line = 3)

        val (_, change) = reducer.recorded(model, TaskCommand.UpdateTask(repo, id, anchors = listOf(auth, routes)))

        assertEquals(listOf("src/Routes.kt"), Additions.of(change).anchors.map { it.path })
    }

    @Test
    fun `editar una tarea que ya tenia su captura no la cuenta otra vez`() {
        val (model, id) = created("Se ve mal\n$image")

        val (_, change) = reducer.recorded(model, TaskCommand.UpdateBody(repo, id, "Se ve fatal\n$image"))

        assertTrue(Additions.of(change).withImages.isEmpty())
    }

    @Test
    fun `una captura nueva en una tarea que ya tenia otra si cuenta`() {
        val (model, id) = created("Se ve mal\n$image")

        val (_, change) = reducer.recorded(model, TaskCommand.UpdateBody(repo, id, "Se ve mal\n$image\n$other"))

        assertEquals(setOf(id), Additions.of(change).withImages)
    }

    @Test
    fun `borrar no añade nada`() {
        val (model, id) = created("Se ve mal\n$image", listOf(auth))

        val (_, change) = reducer.recorded(model, TaskCommand.Delete(repo, listOf(id)))

        assertSame(Additions.NONE, Additions.of(change))
    }

    @Test
    fun `un cambio sin filas no añade nada`() {
        assertTrue(Additions.of(Change.NONE).isEmpty)
    }
}
