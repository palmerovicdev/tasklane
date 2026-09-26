package com.tasklane.data

import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.model.CodeAnchor
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
    fun `las anclas de codigo sobreviven al ciclo`() {
        val anclas = listOf(
            CodeAnchor.of("src/main/kotlin/Auth.kt", 41, column = 17, text = "fun login() {"),
            CodeAnchor.of("README.md", 0),
        )
        val decoded = TasksCodec.decode(TasksCodec.encode(repo, listOf(task().copy(anchors = anclas))), repo)

        assertEquals(anclas, decoded.tasks.single().anchors)
    }

    /**
     * La columna cero es el principio de la linea, que es lo que se asume sin ella —y lo
     * que escribian las versiones anteriores a la marca del editor—. Escribirla seria un
     * atributo por ancla que no dice nada.
     */
    @Test
    fun `la columna cero no se escribe`() {
        val encoded = TasksCodec.encode(repo, listOf(task().copy(anchors = listOf(CodeAnchor.of("a.kt", 2)))))

        val anchor = encoded.getChild("task").getChildren("anchor").single()
        assertEquals(null, anchor.getAttributeValue("column"))
        assertEquals(0, TasksCodec.decode(encoded, repo).tasks.single().anchors.single().column)
    }

    /**
     * Un bloque (2.15.0) lleva su largo, y un ancla de una línea no lleva el atributo: así
     * una versión anterior lee lo de siempre y se queda con la primera línea del bloque.
     */
    @Test
    fun `un bloque escribe su largo y una linea no`() {
        val anclas = listOf(CodeAnchor.of("a.kt", 2, text = "fun a() {", span = 4), CodeAnchor.of("b.kt", 7))
        val encoded = TasksCodec.encode(repo, listOf(task().copy(anchors = anclas)))

        val written = encoded.getChild("task").getChildren("anchor")
        assertEquals("4", written[0].getAttributeValue("span"))
        assertEquals(null, written[1].getAttributeValue("span"))
        assertEquals(anclas, TasksCodec.decode(encoded, repo).tasks.single().anchors)
    }

    /** Una tarea sin anclas no paga ni un elemento: el fichero se reescribe en cada guardado. */
    @Test
    fun `sin anclas no se escribe ningun elemento`() {
        val encoded = TasksCodec.encode(repo, listOf(task()))

        assertEquals(0, encoded.getChild("task").getChildren("anchor").size)
    }

    /** Un ancla rota se descarta sola, como una tarea sin id: el resto se conserva. */
    @Test
    fun `un ancla sin ruta se salta sin llevarse las demas`() {
        val encoded = TasksCodec.encode(repo, listOf(task().copy(anchors = listOf(CodeAnchor.of("a.kt", 2)))))
        encoded.getChild("task").addContent(Element("anchor").setAttribute("line", "9"))

        val decoded = TasksCodec.decode(encoded, repo).tasks.single()

        assertEquals(listOf(CodeAnchor.of("a.kt", 2)), decoded.anchors)
    }

    @Test
    fun `el vencimiento y la marca sobreviven al ciclo`() {
        val vence = Instant.parse("2026-09-20T09:00:00Z")
        val original = task().copy(dueDate = vence, bookmarked = true)
        val decoded = TasksCodec.decode(TasksCodec.encode(repo, listOf(original)), repo).tasks.single()

        assertEquals(vence, decoded.dueDate)
        assertEquals(true, decoded.bookmarked)
    }

    @Test
    fun `sin marca no se escribe el atributo`() {
        // Un `bookmarked="false"` por tarea engorda el fichero y el diff de cada
        // guardado sin decir nada que la ausencia no diga ya.
        val encoded = TasksCodec.encode(repo, listOf(task()))
        val el = encoded.getChildren("task").single()

        assertEquals(null, el.getAttributeValue("bookmarked"))
        assertEquals(null, el.getAttributeValue("dueDate"))
        assertEquals(false, TasksCodec.decode(encoded, repo).tasks.single().bookmarked)
    }

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
