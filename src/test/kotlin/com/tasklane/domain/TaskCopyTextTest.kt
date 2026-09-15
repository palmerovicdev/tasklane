package com.tasklane.domain

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.LinkExtractor
import com.tasklane.domain.text.TaskCopyText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TaskCopyTextTest {

    private val sha = "a".repeat(64)

    private fun task(body: String) = Task(
        id = TaskId.random(),
        repo = RepoKey.ROOT,
        body = body,
        stateId = TasklaneConfig.TODO,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        links = LinkExtractor.extract(body),
        attachments = ImageRefParser.parse(body),
    )

    @Test
    fun `copia todo el cuerpo sin casilla ni markdown`() {
        val body = "**Resolver** el `fallo`\n\nMira [el ticket](https://ejemplo.com/ABC-1)\n![](tasklane:$sha)\n~~Y cerrar~~"

        assertEquals(
            "Resolver el fallo\n\nMira el ticket\nY cerrar",
            TaskCopyText.plain(task(body)),
        )
    }

    @Test
    fun `conserva las urls normales y quita las lineas de imagen`() {
        val body = "Visita https://ejemplo.com/a\n![](tasklane:$sha)\n\nListo"

        assertEquals("Visita https://ejemplo.com/a\n\nListo", TaskCopyText.plain(task(body)))
    }
}
