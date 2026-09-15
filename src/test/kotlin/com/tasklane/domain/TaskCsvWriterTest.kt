package com.tasklane.domain

import com.tasklane.domain.export.TaskCsvWriter
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.LinkExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TaskCsvWriterTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val t0 = Instant.parse("2026-09-13T10:00:00Z")

    private fun task(body: String, id: String = "t-1") = Task(
        id = TaskId(id),
        repo = RepoKey.ROOT,
        body = body,
        stateId = TasklaneConfig.DONE,
        priorityId = TasklaneConfig.HIGH,
        createdAt = t0,
        updatedAt = t0.plusSeconds(3600),
        completedAt = t0.plusSeconds(7200),
        links = LinkExtractor.extract(body),
        attachments = ImageRefParser.parse(body),
    )

    private fun csv(vararg tasks: Task): String = buildString {
        val writer = TaskCsvWriter(this, TasklaneConfig.DEFAULT, zone)
        writer.begin()
        tasks.forEach(writer::task)
    }

    @Test
    fun `sin tareas sale la cabecera con su BOM`() {
        assertEquals("﻿" + TaskCsvWriter.COLUMNS.joinToString(",") + "\r\n", csv())
    }

    @Test
    fun `una tarea es una fila con sus campos por nombre y las fechas en la zona`() {
        val task = task("Arreglar el login").copy(
            tags = listOf("api", "auth"),
            bookmarked = true,
            dueDate = Instant.parse("2026-09-20T21:59:59Z"),
            anchors = listOf(CodeAnchor.of("src/Auth.kt", 41)),
        )

        val rows = csv(task).removePrefix("﻿").split("\r\n")

        assertEquals(
            "Arreglar el login,,Done,High,\"api, auth\",2026-09-20,yes,src/Auth.kt:42,," +
                "2026-09-13 12:00,2026-09-13 13:00,2026-09-13 14:00,t-1",
            rows[1],
        )
    }

    /**
     * RFC 4180: lo que lleve coma, comilla o salto de línea va entre comillas, y las de
     * dentro se duplican. Una descripción de varias líneas es **un** campo, no varias
     * filas. Y sin las capturas, que fuera del IDE no son nada.
     */
    @Test
    fun `comas comillas y saltos de linea no rompen la fila`() {
        val task = task("Revisar \"el\" PR, hoy\nprimera linea\n![](tasklane:${"a".repeat(64)})\nver https://ejemplo.com/x")

        val text = csv(task)

        assertTrue(
            text.contains(
                "\"Revisar \"\"el\"\" PR, hoy\",\"primera linea\nver https://ejemplo.com/x\",Done,High,,,no,,https://ejemplo.com/x,",
            ),
        )
        assertEquals("cabecera y una fila", 3, text.split("\r\n").size)
    }

    @Test
    fun `cuenta lo que escribe`() {
        val writer = TaskCsvWriter(StringBuilder(), TasklaneConfig.DEFAULT, zone)
        writer.task(task("uno", "a"))
        writer.task(task("dos", "b"))
        assertEquals(2, writer.written)
    }
}
