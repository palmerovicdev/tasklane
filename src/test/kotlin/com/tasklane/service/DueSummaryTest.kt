package com.tasklane.service

import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** La cuenta del aviso de vencimientos (2.9.0): vencidas frente a las de hoy. */
class DueSummaryTest {

    private val now = Instant.parse("2026-09-24T12:00:00Z")

    private fun task(due: Instant, done: Boolean = false) = Task(
        id = TaskId.random(),
        repo = RepoKey.ROOT,
        body = "t",
        stateId = StateId("todo"),
        priorityId = PriorityId("normal"),
        createdAt = now,
        updatedAt = now,
        completedAt = if (done) now else null,
        dueDate = due,
    )

    @Test
    fun `lo que ya paso esta vencido y lo que queda de hoy vence hoy`() {
        val summary = DueSummary.of(
            listOf(
                task(Instant.parse("2026-09-20T21:59:59Z")),
                task(Instant.parse("2026-09-24T11:00:00Z")),
                task(Instant.parse("2026-09-24T21:59:59Z")),
            ),
            now,
        )
        assertEquals(DueSummary(overdue = 2, today = 1), summary)
    }

    @Test
    fun `sin nada no hay aviso`() {
        assertTrue(DueSummary.of(emptyList(), now).isEmpty)
    }
}
