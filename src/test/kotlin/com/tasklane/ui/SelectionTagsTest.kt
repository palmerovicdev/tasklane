package com.tasklane.ui

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.ui.actions.SelectionTags
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Lo que lista *Tags ▸ Remove ▸* para una selección (P31). */
class SelectionTagsTest {

    private val t0 = Instant.parse("2026-09-26T10:00:00Z")

    private fun task(id: String, vararg tags: String) = Task(
        id = TaskId(id),
        repo = RepoKey.ROOT,
        body = "Tarea $id",
        stateId = TasklaneConfig.TODO,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = t0,
        updatedAt = t0,
        tags = tags.toList(),
    )

    @Test
    fun `sin etiquetas no hay nada que quitar`() {
        assertEquals(emptyList<String>(), SelectionTags.of(listOf(task("a"), task("b"))))
    }

    @Test
    fun `primero las que llevan mas tareas, y a igualdad por orden alfabetico`() {
        val tasks = listOf(task("a", "ui", "api"), task("b", "api", "docs"), task("c", "Beta"))
        assertEquals(listOf("api", "Beta", "docs", "ui"), SelectionTags.of(tasks))
    }

    @Test
    fun `una vez cada una sin mirar mayusculas, con la forma de la primera`() {
        val tasks = listOf(task("a", "API"), task("b", "api"), task("c", "Api", "api"))
        assertEquals(listOf("API"), SelectionTags.of(tasks))
    }
}
