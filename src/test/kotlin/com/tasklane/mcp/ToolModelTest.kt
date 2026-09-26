package com.tasklane.mcp

import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ToolModelTest {

    private val config = TasklaneConfig.DEFAULT
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2026-09-25T10:00:00Z")
    private val today = LocalDate.parse("2026-09-25")

    private fun task(body: String, vararg anchors: CodeAnchor, due: Instant? = null) = Task(
        id = TaskId("t1"),
        repo = RepoKey.ROOT,
        body = body,
        stateId = TasklaneConfig.TODO,
        priorityId = TasklaneConfig.HIGH,
        createdAt = now,
        updatedAt = now,
        anchors = anchors.toList(),
        dueDate = due,
    )

    private fun error(block: () -> Unit): String {
        try {
            block()
        } catch (e: ToolError) {
            return e.message.orEmpty()
        }
        fail("se esperaba ToolError")
        error("unreachable")
    }

    // ------------------------------------------------------------------ nombres

    @Test
    fun `los estados se encuentran sin mayusculas, por id y por prefijo unico`() {
        assertEquals(TasklaneConfig.DOING, ToolNames.state(config, "doing").id)
        assertEquals(TasklaneConfig.DONE, ToolNames.state(config, "s-done").id)
        assertEquals(TasklaneConfig.TODO, ToolNames.state(config, "to").id)
    }

    @Test
    fun `un nombre que no existe falla diciendo cuales hay`() {
        val message = error { ToolNames.state(config, "Review") }
        assertTrue(message, message.contains("ToDo, Doing, Done"))
    }

    @Test
    fun `un prefijo que encaja con dos no elige ninguno`() {
        // «Do» es el principio de Doing y de Done.
        error { ToolNames.state(config, "do") }
    }

    @Test
    fun `las prioridades igual`() {
        assertEquals(TasklaneConfig.HIGH, ToolNames.priority(config, "HIGH").id)
        error { ToolNames.priority(config, "urgent") }
    }

    @Test
    fun `el repositorio se encuentra por nombre, clave o ruta, y sin nombre es el activo`() {
        val api = RepositoryRef(RepoKey("api-1"), "api", "/p/backend/api", RepositoryRef.Kind.GIT)
        val web = RepositoryRef(RepoKey("web-2"), "web", "/p/web", RepositoryRef.Kind.GIT)
        val repos = listOf(api, web)
        assertEquals(web, ToolNames.repository(repos, web.key, null))
        assertEquals(api, ToolNames.repository(repos, web.key, "API"))
        assertEquals(api, ToolNames.repository(repos, web.key, "api-1"))
        assertEquals(api, ToolNames.repository(repos, web.key, "/p/backend/api/"))
        assertEquals(api, ToolNames.repository(repos, web.key, "backend/api"))
        error { ToolNames.repository(repos, web.key, "mobile") }
    }

    @Test
    fun `el vencimiento es el final del dia, y none lo quita`() {
        assertEquals(Instant.parse("2026-09-30T23:59:59.999999999Z"), ToolNames.due("2026-09-30", today, zone))
        assertEquals(Instant.parse("2026-09-26T23:59:59.999999999Z"), ToolNames.due("tomorrow", today, zone))
        assertNull(ToolNames.due("none", today, zone))
        error { ToolNames.due("next friday", today, zone) }
    }

    // ------------------------------------------------------------------ casillas

    @Test
    fun `las casillas se numeran en orden de lectura, titulo incluido, y fuera del codigo`() {
        val body = "- [ ] Arreglar el login\n\n- [x] Reproducir\n```\n- [ ] no cuenta\n```\n- [ ] Test"
        val items = checkItems(task(body))
        assertEquals(listOf(1, 2, 3), items.map { it.index })
        assertEquals(listOf("Arreglar el login", "Reproducir", "Test"), items.map { it.text })
        assertEquals(listOf(false, true, false), items.map { it.checked })
        // El offset es el de la marca: es lo que escribe ToggleCheck.
        items.forEach { assertTrue(body[it.offset] == ' ' || body[it.offset] == 'x') }
    }

    // ------------------------------------------------------------------ vistas

    @Test
    fun `el resumen lleva lo de la tarjeta y el detalle el cuerpo y el ancla en su linea de hoy`() {
        val anchor = CodeAnchor.of("src/Auth.kt", 41, text = "fun login()")
        val t = task("Login roto\n- [ ] paso", anchor, due = Instant.parse("2026-09-20T23:59:59Z"))
        val views = TaskViews(config, { "root" }, now, zone)

        val summary = views.summary(t)
        assertEquals("Login roto", summary["title"])
        assertEquals("High", summary["priority"])
        assertEquals("2026-09-20", summary["due"])
        assertEquals(true, summary["overdue"])
        assertEquals("0/1", summary["checklist"])
        assertEquals(listOf("src/Auth.kt:42"), summary["code"])

        val detail = views.detail(t) { 56 }
        assertEquals(t.body, detail["body"])
        val code = (detail["code"] as List<*>).single() as Map<*, *>
        assertEquals(57, code["line"])
        assertEquals(42, code["anchoredAtLine"])

        val gone = views.detail(t) { null }
        assertEquals(true, ((gone["code"] as List<*>).single() as Map<*, *>)["missing"])
    }

    /**
     * Un bloque (2.15.0): el resumen lo nombra como se escribe, y el detalle da su última
     * línea contada desde donde está hoy la primera.
     */
    @Test
    fun `un bloque anclado dice hasta que linea llega`() {
        val anchor = CodeAnchor.of("src/Auth.kt", 41, text = "fun login() {", span = 5)
        val t = task("Login roto", anchor)
        val views = TaskViews(config, { "root" }, now, zone)

        assertEquals(listOf("src/Auth.kt:42-47"), views.summary(t)["code"])
        val code = (views.detail(t) { 56 }["code"] as List<*>).single() as Map<*, *>
        assertEquals(57, code["line"])
        assertEquals(62, code["endLine"])

        val single = (views.detail(task("Otra", CodeAnchor.of("a.kt", 3))) { 3 }["code"] as List<*>).single() as Map<*, *>
        assertFalse("endLine" in single)
    }

    @Test
    fun `el json escapa lo que tiene que escapar`() {
        assertEquals(
            """{"a":"x\"y\\z\n","b":[1,true,null],"c":{}}""",
            ToolJson.write(mapOf("a" to "x\"y\\z\n", "b" to listOf(1, true, null), "c" to emptyMap<String, Any>())),
        )
        assertEquals("\"\\u0001\"", ToolJson.write("\u0001"))
    }
}
