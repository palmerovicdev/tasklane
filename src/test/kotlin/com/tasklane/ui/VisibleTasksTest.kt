package com.tasklane.ui

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.domain.query.TaskQuery
import com.tasklane.service.SearchResults
import com.tasklane.ui.toolwindow.VisibleTasks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * La invariante que justifica que [VisibleTasks] exista: el contador de la pestaña y
 * la lista tienen que salir de la misma cuenta. Cada caso comprueba las dos.
 */
class VisibleTasksTest {

    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val other = RepoKey("web-1234abcd")
    private val todo = TasklaneConfig.TODO
    private val done = TasklaneConfig.DONE

    private fun task(
        id: String,
        stateId: StateId = todo,
        repo: RepoKey = RepoKey.ROOT,
        dueDate: Instant? = null,
        completedAt: Instant? = null,
        bookmarked: Boolean = false,
    ) = Task(
        id = TaskId(id),
        repo = repo,
        body = id,
        stateId = stateId,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = now,
        updatedAt = now,
        completedAt = completedAt,
        dueDate = dueDate,
        bookmarked = bookmarked,
    )

    /** El universo de la ventana, que desde la Fase 3 llega como lista y no como modelo. */
    private fun snapshot(vararg tasks: Task) = tasks.toList()

    /** Con `matches` a null no hay consulta: pasa todo. Con un mapa, sólo lo que está. */
    private fun found(vararg ids: String) =
        SearchResults("q", TaskQuery.EMPTY, ids.associate { TaskId(it) to 1 }, highlighter = null)

    private fun counted(tasks: List<Task>, found: SearchResults, filter: TaskFilter, state: StateId) =
        VisibleTasks.countsByState(tasks, found, filter, now)[state] ?: 0

    private fun shown(tasks: List<Task>, found: SearchResults, filter: TaskFilter, state: StateId) =
        VisibleTasks.of(tasks, found, filter, state, now)

    private fun assertSameCount(
        expected: Int,
        tasks: List<Task>,
        found: SearchResults = SearchResults.NONE,
        filter: TaskFilter = TaskFilter.ALL,
        state: StateId = todo,
    ) {
        val visible = shown(tasks, found, filter, state)
        assertEquals(expected, visible.size)
        assertEquals(
            "el contador no dice lo mismo que la lista",
            visible.size,
            counted(tasks, found, filter, state),
        )
    }

    @Test
    fun `cuenta las del estado, no las de al lado`() {
        val snapshot = snapshot(task("a"), task("b"), task("c", stateId = done))

        assertSameCount(2, snapshot)
        assertSameCount(1, snapshot, state = done)
    }

    @Test
    fun `la busqueda activa acota el contador`() {
        val snapshot = snapshot(task("a"), task("b"), task("c"))

        assertSameCount(1, snapshot, found = found("b"))
    }

    @Test
    fun `sin consulta no se filtra nada`() {
        val snapshot = snapshot(task("a"), task("b"))

        assertSameCount(2, snapshot, found = SearchResults.NONE)
    }

    @Test
    /**
     * **Quién decide el universo cambió de sitio en la Fase 3.** Antes esto miraba el
     * snapshot y elegía: el repositorio activo, o todos si había búsqueda. Ahora el
     * universo llega ya acotado —`TaskService.pager` elige entre los aciertos de FTS5 y
     * la consulta del repositorio activo— y aquí sólo se filtra lo que llega.
     *
     * Lo que sigue siendo cierto, y es lo que este caso fija: **lo que llega se cuenta
     * entero**, venga del repositorio que venga. Un resultado de otro repositorio no se
     * descarta por serlo.
     */
    fun `cuenta lo que le dan, venga del repositorio que venga`() {
        val snapshot = snapshot(task("a"), task("b", repo = other))

        assertSameCount(2, snapshot)
        assertSameCount(2, snapshot, found = found("a", "b"))
        assertSameCount(1, snapshot, found = found("a"))
    }

    @Test
    fun `el filtro de abiertas deja fuera las completadas`() {
        val snapshot = snapshot(task("a"), task("b", completedAt = now))

        assertSameCount(1, snapshot, filter = TaskFilter.OPEN)
        assertSameCount(2, snapshot, filter = TaskFilter.ALL)
    }

    @Test
    fun `vencidas es tener fecha pasada y seguir abierta`() {
        val snapshot = snapshot(
            task("pasada", dueDate = now.minusSeconds(60)),
            task("futura", dueDate = now.plusSeconds(60)),
            task("sin fecha"),
            task("pasada pero hecha", dueDate = now.minusSeconds(60), completedAt = now),
        )

        assertSameCount(1, snapshot, filter = TaskFilter.OVERDUE)
    }

    @Test
    fun `marcadas`() {
        val snapshot = snapshot(task("a", bookmarked = true), task("b"))

        assertSameCount(1, snapshot, filter = TaskFilter.BOOKMARKED)
    }

    @Test
    fun `busqueda y filtro se aplican los dos`() {
        val snapshot = snapshot(
            task("a", bookmarked = true),
            task("b", bookmarked = true),
            task("c"),
        )

        assertSameCount(1, snapshot, found = found("a", "c"), filter = TaskFilter.BOOKMARKED)
    }
}
