package com.tasklane.domain

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Lo que la tarjeta deriva del cuerpo: descripción y lista de comprobación.
 *
 * Ninguna de las dos es un campo guardado, y eso es el punto: se escriben en el
 * editor como Markdown normal y la fila las entiende, sin que haya dos fuentes de
 * verdad que puedan discrepar.
 */
class TaskDerivedTest {

    private fun task(body: String) = Task(
        id = TaskId.random(),
        repo = RepoKey.ROOT,
        body = body,
        stateId = TasklaneConfig.TODO,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `la descripcion es la primera linea util bajo el titulo`() {
        val task = task("Arreglar login\n\nFalla al refrescar el token")
        assertEquals("Arreglar login", task.title)
        assertEquals("Falla al refrescar el token", task.description)
    }

    @Test
    fun `una tarea de una sola linea no tiene descripcion`() {
        val task = task("Comprar pan")
        assertEquals("", task.description)
        assertFalse(task.hasDetail)
    }

    @Test
    fun `una linea que solo es una imagen no es descripcion`() {
        val sha = "a".repeat(64)
        val task = task("Bug del boton\n![](tasklane:$sha)\nPasa en dark mode")
        assertEquals("Pasa en dark mode", task.description)
    }

    @Test
    fun `vencida solo si paso la fecha y sigue abierta`() {
        val ayer = Instant.parse("2026-09-12T10:00:00Z")
        val ahora = Instant.parse("2026-09-13T10:00:00Z")
        assertTrue(task("x").copy(dueDate = ayer).isOverdue(ahora))
        assertFalse("una tarea cerrada ya no vence", task("x").copy(dueDate = ayer, completedAt = ahora).isOverdue(ahora))
        assertFalse("sin fecha no hay vencimiento", task("x").isOverdue(ahora))
    }
}
