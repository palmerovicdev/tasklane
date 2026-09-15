package com.tasklane.domain

import com.tasklane.domain.model.AnchoredTasks
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * El índice que decide qué se pinta sobre el código. Lo que se prueba aquí es sobre todo
 * lo que **no** se marca: una marca de más en el margen es ruido permanente, y la que
 * sobra siempre es la de algo ya hecho.
 */
class AnchoredTasksTest {

    private val config = TasklaneConfig.DEFAULT
    private val otro = RepoKey("frontend")
    private val now = Instant.parse("2026-09-14T10:00:00Z")

    private fun task(
        id: String,
        path: String,
        line: Int = 10,
        state: com.tasklane.domain.model.StateId = TasklaneConfig.TODO,
        priority: com.tasklane.domain.model.PriorityId = TasklaneConfig.NORMAL,
        repo: RepoKey = RepoKey.ROOT,
        updatedAt: Instant = now,
    ) = Task(
        id = TaskId(id),
        repo = repo,
        body = "tarea $id",
        stateId = state,
        priorityId = priority,
        createdAt = now,
        updatedAt = updatedAt,
        anchors = listOf(CodeAnchor.of(path, line)),
    )

    /**
     * Las tareas sueltas, que es lo que [AnchoredTasks.byPath] recibe desde la Fase 3:
     * la vuelta al modelo la da ahora el índice `anchor_by_path`, y lo que queda aquí
     * es la decisión de qué se marca y cuál manda.
     */
    private fun snapshot(vararg tasks: Task) = tasks.toList()

    @Test
    fun `agrupa por ruta`() {
        val index = AnchoredTasks.byPath(config = config, tasks = snapshot(task("a", "src/Auth.kt"), task("b", "src/Main.kt")))

        assertEquals(setOf("src/Auth.kt", "src/Main.kt"), index.keys)
        assertEquals(TaskId("a"), index.getValue("src/Auth.kt").single().task.id)
    }

    @Test
    fun `lo terminal no se marca`() {
        val index = AnchoredTasks.byPath(config = config, tasks = snapshot(
                task("abierta", "src/Auth.kt"),
                task("hecha", "src/Auth.kt", state = TasklaneConfig.DONE),
            ),
        )

        assertEquals(listOf(TaskId("abierta")), index.getValue("src/Auth.kt").map { it.task.id })
    }

    /** Si el único que apuntaba al fichero está hecho, el fichero desaparece del índice. */
    @Test
    fun `un fichero solo con tareas hechas no entra`() {
        val index = AnchoredTasks.byPath(config = config, tasks = snapshot(task("hecha", "src/Auth.kt", state = TasklaneConfig.DONE)))

        assertTrue(index.isEmpty())
    }

    /** El fichero abierto no sabe de repositorios: una nota sobre él vale venga de donde venga. */
    @Test
    fun `entran las tareas de todos los repositorios`() {
        val index = AnchoredTasks.byPath(config = config, tasks = snapshot(task("a", "src/Auth.kt"), task("b", "src/Auth.kt", repo = otro)),
        )

        assertEquals(setOf(TaskId("a"), TaskId("b")), index.getValue("src/Auth.kt").map { it.task.id }.toSet())
    }

    /** Manda la prioridad: es la que da color a la marca cuando hay varias en la misma línea. */
    @Test
    fun `la de mas prioridad encabeza`() {
        val index = AnchoredTasks.byPath(config = config, tasks = snapshot(
                task("baja", "src/Auth.kt", priority = TasklaneConfig.LOW),
                task("alta", "src/Auth.kt", priority = TasklaneConfig.HIGH),
            ),
        )

        assertEquals(TaskId("alta"), index.getValue("src/Auth.kt").first().task.id)
    }

    @Test
    fun `a igual prioridad manda la mas reciente`() {
        val index = AnchoredTasks.byPath(config = config, tasks = snapshot(
                task("vieja", "src/Auth.kt", updatedAt = now.minusSeconds(3600)),
                task("nueva", "src/Auth.kt"),
            ),
        )

        assertEquals(TaskId("nueva"), index.getValue("src/Auth.kt").first().task.id)
    }

    /** Una tarea con dos anclas al mismo fichero sale dos veces: son dos sitios. */
    @Test
    fun `una tarea con dos anclas aparece en las dos`() {
        val dos = task("a", "src/Auth.kt").copy(
            anchors = listOf(CodeAnchor.of("src/Auth.kt", 10), CodeAnchor.of("src/Auth.kt", 40)),
        )

        val entries = AnchoredTasks.byPath(config = config, tasks = snapshot(dos)).getValue("src/Auth.kt")

        assertEquals(listOf(10, 40), entries.map { it.anchor.line }.sorted())
    }
}
