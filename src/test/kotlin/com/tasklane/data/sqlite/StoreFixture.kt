package com.tasklane.data.sqlite

import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.LinkExtractor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Una base de tareas de usar y tirar.
 *
 * El almacén de la Fase 3 **no necesita un IDE**: `org.jetbrains.sqlite` es una
 * biblioteca y `TaskStore` no conoce `Project`. Por eso sus tests son JUnit normales,
 * como los del dominio, y por eso se pueden ejecutar en los tres sistemas de CI.
 */
internal object StoreFixture {

    val CONFIG: TasklaneConfig = TasklaneConfig.DEFAULT.normalized()
    val REPO = RepoKey("root")

    fun <T> withStore(block: (TaskStore, TaskDb) -> T): T {
        val dir: Path = Files.createTempDirectory("tasklane-store")
        val db = TaskDb.open(dir) ?: error("no se pudo abrir la base en $dir")
        return try {
            block(TaskStore(db), db)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    /** Una tarea con lo derivado ya puesto, como la deja el reducer. */
    fun task(
        id: String,
        body: String = "Tarea $id",
        state: StateId = TasklaneConfig.TODO,
        priority: PriorityId = TasklaneConfig.NORMAL,
        repo: RepoKey = REPO,
        createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt: Instant = createdAt,
        completedAt: Instant? = null,
        dueDate: Instant? = null,
        bookmarked: Boolean = false,
        tags: List<String> = emptyList(),
        anchors: List<CodeAnchor> = emptyList(),
        order: Long = 0,
        extra: Map<String, String> = emptyMap(),
    ): Task = Task(
        id = TaskId(id),
        repo = repo,
        body = body,
        stateId = state,
        priorityId = priority,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        order = order,
        links = LinkExtractor.extract(body),
        attachments = ImageRefParser.parse(body),
        tags = tags,
        anchors = anchors,
        dueDate = dueDate,
        bookmarked = bookmarked,
        extra = extra,
    )
}
