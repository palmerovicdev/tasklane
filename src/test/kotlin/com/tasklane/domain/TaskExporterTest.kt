package com.tasklane.domain

import com.tasklane.domain.export.ExportFormat
import com.tasklane.domain.export.TaskExporter
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TaskExporterTest {

    private val config = TasklaneConfig.DEFAULT
    private val t0 = Instant.parse("2026-09-13T10:00:00Z")

    private fun task(body: String, state: StateId = TasklaneConfig.DONE, tags: List<String> = emptyList()) =
        Task(
            id = TaskId.random(),
            repo = RepoKey.ROOT,
            body = body,
            stateId = state,
            priorityId = TasklaneConfig.NORMAL,
            createdAt = t0,
            updatedAt = t0,
            tags = tags,
        )

    @Test
    fun `el criterio de la Fase 5 - Done a Today es una lista de guiones`() {
        val text = TaskExporter.export(
            heading = "Done · Today",
            tasks = listOf(task("Arreglar el login"), task("Revisar el PR de facturacion")),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals(
            """
            ## Done · Today

            - [x] Arreglar el login
            - [x] Revisar el PR de facturacion
            """.trimIndent() + "\n",
            text,
        )
    }

    @Test
    fun `la casilla distingue terminal de abierto`() {
        val text = TaskExporter.export(
            heading = null,
            tasks = listOf(task("Hecho"), task("Pendiente", state = TasklaneConfig.TODO)),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals("- [x] Hecho\n- [ ] Pendiente\n", text)
    }

    @Test
    fun `el formato plano no pinta casillas`() {
        val text = TaskExporter.export(
            heading = "Done",
            tasks = listOf(task("Hecho")),
            config = config,
            format = ExportFormat.PLAIN,
        )
        assertEquals("Done\n\n- Hecho\n", text)
    }

    @Test
    fun `el detalle se sangra bajo su tarea`() {
        val text = TaskExporter.export(
            heading = null,
            tasks = listOf(task("Migrar el indice\n\nHay que avisar a soporte\ny cerrar el ticket")),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals(
            "- [x] Migrar el indice\n  Hay que avisar a soporte\n  y cerrar el ticket\n",
            text,
        )
    }

    @Test
    fun `las etiquetas van detras del titulo`() {
        val text = TaskExporter.export(
            heading = null,
            tasks = listOf(task("Revisar", tags = listOf("api", "urgente"))),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals("- [x] Revisar #api #urgente\n", text)
    }

    @Test
    fun `varios grupos se separan por una linea en blanco`() {
        val text = TaskExporter.export(
            sections = listOf(
                TaskExporter.Section("Done · Today", listOf(task("Uno"))),
                TaskExporter.Section("Done · Yesterday", listOf(task("Dos"))),
            ),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals(
            "## Done · Today\n\n- [x] Uno\n\n## Done · Yesterday\n\n- [x] Dos\n",
            text,
        )
    }

    @Test
    fun `un grupo vacio no deja cabecera huerfana`() {
        val text = TaskExporter.export(
            sections = listOf(
                TaskExporter.Section("Done · Today", emptyList()),
                TaskExporter.Section("Done · Yesterday", listOf(task("Dos"))),
            ),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals("## Done · Yesterday\n\n- [x] Dos\n", text)
    }

    @Test
    fun `sin tareas no se exporta nada`() {
        assertEquals("", TaskExporter.export("Done", emptyList(), config, ExportFormat.MARKDOWN))
    }

    // --------------------------------------------------------- parte del dia

    private val day = LocalDate.of(2026, 9, 11)

    /**
     * Un grupo de fecha exportado a Markdown es el parte de ese dia: la fecha en ISO y
     * una lista numerada. «Done · Hoy» dejaria de ser verdad manana, y lo exportado se
     * guarda.
     */
    @Test
    fun `un grupo de fecha sale como el parte de su dia`() {
        val text = TaskExporter.export(
            sections = listOf(
                TaskExporter.Section("Done · Today", listOf(task("task 1"), task("task 2")), day),
            ),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals("## 2026-09-11\n\n1. task 1\n2. task 2\n", text)
    }

    /** La numeracion empieza de nuevo en cada dia: son partes distintos, no una lista. */
    @Test
    fun `cada dia numera desde uno`() {
        val text = TaskExporter.export(
            sections = listOf(
                TaskExporter.Section("Done · Today", listOf(task("Uno"), task("Dos")), day),
                TaskExporter.Section("Done · Yesterday", listOf(task("Tres")), day.minusDays(1)),
            ),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals(
            "## 2026-09-11\n\n1. Uno\n2. Dos\n\n## 2026-09-10\n\n1. Tres\n",
            text,
        )
    }

    /** Sangrado hasta donde empieza el texto de la linea, o Markdown rompe la numeracion. */
    @Test
    fun `el detalle se sangra bajo su numero`() {
        val text = TaskExporter.export(
            sections = listOf(
                TaskExporter.Section(null, listOf(task("Migrar el indice\nAvisar a soporte")), day),
            ),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals("## 2026-09-11\n\n1. Migrar el indice\n   Avisar a soporte\n", text)
    }

    /** El destino de la copia en texto plano es un correo, no un documento: no cambia. */
    @Test
    fun `en texto plano el grupo de fecha no cambia`() {
        val text = TaskExporter.export(
            sections = listOf(TaskExporter.Section("Done · Today", listOf(task("Uno")), day)),
            config = config,
            format = ExportFormat.PLAIN,
        )
        assertEquals("Done · Today\n\n- Uno\n", text)
    }

    /**
     * «Esta semana» y «sin fecha» no son un dia, asi que conservan la cabecera de la
     * pestaña y su casilla: poner ahi una fecha seria inventarsela.
     */
    @Test
    fun `un grupo sin dia concreto sigue siendo una lista de casillas`() {
        val text = TaskExporter.export(
            sections = listOf(TaskExporter.Section("Done · This week", listOf(task("Uno")))),
            config = config,
            format = ExportFormat.MARKDOWN,
        )
        assertEquals("## Done · This week\n\n- [x] Uno\n", text)
    }

    // ------------------------------------------------------------- imagenes

    private val sha = "a".repeat(64)

    /**
     * Lo exportado se pega en un ticket o en un correo, donde un `tasklane:<sha>` no
     * es una imagen ni un enlace: son 64 caracteres que nadie puede resolver.
     */
    @Test
    fun `las referencias a imagenes no salen en la exportacion`() {
        val text = TaskExporter.export(
            heading = null,
            tasks = listOf(task("Error al entrar\n![](tasklane:$sha)\nPasa con Safari")),
            config = config,
            format = ExportFormat.PLAIN,
        )
        assertEquals("- Error al entrar\n  Pasa con Safari\n", text)
    }

    @Test
    fun `una imagen entre parrafos no deja un hueco donde estaba`() {
        val text = TaskExporter.export(
            heading = null,
            tasks = listOf(task("Titulo\nMira la captura: ![](tasklane:$sha)")),
            config = config,
            format = ExportFormat.PLAIN,
        )
        assertEquals("- Titulo\n  Mira la captura:\n", text)
    }

    // ------------------------------------------------------------ en streaming

    /**
     * La exportación de la Fase 5 escribe tarea a tarea. Lo que la hace de fiar es que
     * **no hay un segundo formato**: la versión `String` está construida encima del
     * *stream*, así que todos los casos de arriba ya lo prueban. Éste añade lo que sólo
     * existe en *streaming*: que las tandas lleguen partidas por cualquier sitio.
     */
    @Test
    fun `escribir a tandas da los mismos bytes que escribir de golpe`() {
        val sections = listOf(
            TaskExporter.Section("Done · Today", listOf(task("Uno\n\nCon detalle"), task("Dos")), day),
            TaskExporter.Section("Done · Yesterday", emptyList()),
            TaskExporter.Section("Done · This week", listOf(task("Tres", tags = listOf("api")))),
            TaskExporter.Section(null, listOf(task("Cuatro\n![](tasklane:$sha)\nfin"))),
        )
        for (format in ExportFormat.entries) {
            val out = StringBuilder()
            val stream = TaskExporter.Stream(out, config, format)
            for (section in sections) {
                stream.section(section.heading, section.date)
                // Una tarea por tanda: el caso más partido posible.
                section.tasks.chunked(1).forEach { chunk -> chunk.forEach(stream::task) }
            }
            assertEquals("formato $format", TaskExporter.export(sections, config, format), out.toString())
            assertEquals(4, stream.written)
        }
    }

    /** Una sección que no llega a recibir tareas no escribe nada, ni su separador. */
    @Test
    fun `una seccion que nunca recibe tareas no deja rastro`() {
        val out = StringBuilder()
        val stream = TaskExporter.Stream(out, config, ExportFormat.MARKDOWN)
        stream.section("Vacía")
        stream.section("Llena")
        stream.task(task("Uno"))
        stream.section("Vacía al final")

        assertEquals("## Llena\n\n- [x] Uno\n", out.toString())
    }

    /**
     * La numeración del parte del día vuelve a uno en cada sección, aunque el *stream* sea
     * el mismo objeto de principio a fin.
     */
    @Test
    fun `la numeracion no se arrastra de una seccion a la siguiente`() {
        val out = StringBuilder()
        val stream = TaskExporter.Stream(out, config, ExportFormat.MARKDOWN)
        stream.section("Hoy", day)
        stream.task(task("Uno"))
        stream.task(task("Dos"))
        stream.section("Ayer", day.minusDays(1))
        stream.task(task("Tres"))

        assertEquals("## 2026-09-11\n\n1. Uno\n2. Dos\n\n## 2026-09-10\n\n1. Tres\n", out.toString())
    }
}
