package com.tasklane.data

import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TasksCodecTest {

    private val repo = RepoKey.ROOT
    private val now = Instant.parse("2026-09-13T10:00:00Z")

    private fun task(body: String = "hola\nsegunda linea") = Task(
        id = TaskId("abc"),
        repo = repo,
        body = body,
        stateId = StateId("s-todo"),
        priorityId = PriorityId("p-high"),
        createdAt = now,
        updatedAt = now,
        order = 1000,
        tags = listOf("android", "auth"),
    )

    @Test
    fun `ida y vuelta conserva los campos`() {
        val original = task()
        val decoded = TasksCodec.decode(TasksCodec.encode(repo, listOf(original)), repo).tasks.single()

        assertEquals(original.id, decoded.id)
        assertEquals(original.body, decoded.body)
        assertEquals(original.stateId, decoded.stateId)
        assertEquals(original.priorityId, decoded.priorityId)
        assertEquals(original.createdAt, decoded.createdAt)
        assertEquals(original.order, decoded.order)
        assertEquals(original.tags, decoded.tags)
    }

    @Test
    fun `un cuerpo multilinea sobrevive al XML`() {
        val body = "linea 1\n\nlinea 3 con <angulos> & ampersand"
        val decoded = TasksCodec.decode(TasksCodec.encode(repo, listOf(task(body))), repo).tasks.single()
        assertEquals(body, decoded.body)
    }

    @Test
    fun `los atributos desconocidos sobreviven al ciclo completo`() {
        // Simula un fichero escrito por una version futura del plugin.
        val root = TasksCodec.encode(repo, listOf(task()))
        root.getChild("task").setAttribute("assignee", "victor")

        val decoded = TasksCodec.decode(root, repo).tasks.single()
        assertEquals("se preserva al leer", "victor", decoded.extra["assignee"])

        val reencoded = TasksCodec.encode(repo, listOf(decoded))
        assertEquals(
            "y se vuelve a escribir: abrir con una version vieja no debe borrar datos nuevos",
            "victor",
            reencoded.getChild("task").getAttributeValue("assignee"),
        )
    }

    @Test
    fun `una tarea sin id se salta sin tumbar el resto del fichero`() {
        val root = TasksCodec.encode(repo, listOf(task()))
        root.addContent(Element("task").setAttribute("state", "s-todo"))

        assertEquals(1, TasksCodec.decode(root, repo).tasks.size)
    }

    @Test(expected = TasksCodec.DecodeException::class)
    fun `sin atributo version se considera ilegible`() {
        TasksCodec.decode(Element("tasks"), repo)
    }
}
