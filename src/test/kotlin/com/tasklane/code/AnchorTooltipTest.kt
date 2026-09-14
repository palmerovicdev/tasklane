package com.tasklane.code

import com.tasklane.domain.model.AnchoredTask
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
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
 * Lo que dice la marca al pasar el ratón. Es la mitad de la función: sin tooltip la
 * marca sólo avisa de que hay algo, que es justo el viaje a la ventana que venía a
 * ahorrar.
 */
class AnchorTooltipTest {

    private val config = TasklaneConfig.DEFAULT
    private val now = Instant.parse("2026-09-14T10:00:00Z")

    private fun entry(
        body: String,
        priority: PriorityId = TasklaneConfig.NORMAL,
        tags: List<String> = emptyList(),
        dueDate: Instant? = null,
    ) = AnchoredTask(
        Task(
            id = TaskId(body),
            repo = RepoKey.ROOT,
            body = body,
            stateId = TasklaneConfig.DOING,
            priorityId = priority,
            createdAt = now,
            updatedAt = now,
            tags = tags,
            dueDate = dueDate,
        ),
        CodeAnchor.of("src/Auth.kt", 41),
    )

    private fun html(vararg entries: AnchoredTask) = AnchorTooltip.html(
        entries = entries.toList(),
        config = config,
        formatDate = { "manana" },
        hint = "Click to open it in Tasklane",
        more = { "$it more here" },
    )

    @Test
    fun `ensena titulo, estado y prioridad`() {
        val text = html(entry("Arreglar el refresco del token"))

        assertTrue(text, text.contains("<b>Arreglar el refresco del token</b>"))
        assertTrue(text, text.contains("Doing") && text.contains("Normal"))
        assertTrue(text, text.contains("Click to open it in Tasklane"))
    }

    @Test
    fun `el vencimiento y las etiquetas van con el estado`() {
        val text = html(entry("Revisar", tags = listOf("auth"), dueDate = now))

        assertTrue(text, text.contains("manana"))
        assertTrue(text, text.contains("#auth"))
    }

    /** Un titulo con `<` no puede abrir una etiqueta: esto es HTML de verdad. */
    @Test
    fun `escapa el titulo`() {
        val text = html(entry("Usar <b>negrita</b> aqui"))

        assertFalse(text, text.contains("<b>negrita</b>"))
        assertTrue(text, text.contains("&lt;b&gt;negrita"))
    }

    /** Un tooltip de veinte bloques tapa la pantalla; ahi es donde la ventana hace falta. */
    @Test
    fun `corta cuando son demasiadas y dice cuantas quedan`() {
        val muchas = (1..7).map { entry("tarea $it") }

        val text = AnchorTooltip.html(muchas, config, { "manana" }, "pulsa", { "$it more here" })

        assertEquals(4, Regex("<b>").findAll(text).count())
        assertTrue(text, text.contains("3 more here"))
    }

    @Test
    fun `sin anclas no hay tooltip`() {
        assertEquals("", html())
    }
}
