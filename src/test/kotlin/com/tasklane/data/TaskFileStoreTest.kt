package com.tasklane.data

import com.tasklane.data.store.StorageLayout
import com.tasklane.data.store.TaskFileStore
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Instant

class TaskFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val repo = RepoKey.ROOT

    private fun store() = TaskFileStore(StorageLayout(tmp.root.toPath().resolve("tasklane")))
    private fun layout() = StorageLayout(tmp.root.toPath().resolve("tasklane"))

    private fun task(id: String, body: String) = Task(
        id = TaskId(id),
        repo = repo,
        body = body,
        stateId = StateId("s-todo"),
        priorityId = PriorityId("p-normal"),
        createdAt = Instant.parse("2026-09-13T10:00:00Z"),
        updatedAt = Instant.parse("2026-09-13T10:00:00Z"),
        order = 1000,
    )

    @Test
    fun `un repositorio sin fichero no es un error`() {
        assertTrue(store().read(repo) is TaskFileStore.ReadResult.Empty)
    }

    @Test
    fun `escribir y volver a leer conserva las tareas`() = runBlocking {
        val s = store()
        s.write(repo, listOf(task("a", "primera"), task("b", "segunda")))

        val result = s.read(repo)
        assertTrue(result is TaskFileStore.ReadResult.Ok)
        assertEquals(listOf("primera", "segunda"), result.tasks.map { it.body })
    }

    @Test
    fun `el directorio se auto-ignora para no ensuciar el repo del usuario`() = runBlocking {
        store().write(repo, listOf(task("a", "x")))

        val ignore = layout().root.resolve(".gitignore")
        assertTrue("debe crearse un .gitignore propio", Files.exists(ignore))
        assertTrue(Files.readString(ignore).contains("*"))
    }

    @Test
    fun `un fichero corrupto se recupera desde el backup`() = runBlocking {
        val s = store()
        s.write(repo, listOf(task("a", "buena")))   // crea tasks.xml
        s.write(repo, listOf(task("a", "buena")))   // la segunda escritura crea el .bak

        Files.writeString(layout().tasksFile(repo), "<<<esto no es XML")

        val result = s.read(repo)
        assertTrue(result is TaskFileStore.ReadResult.Corrupt)
        result as TaskFileStore.ReadResult.Corrupt

        assertTrue("debe venir del backup", result.recoveredFromBackup)
        assertEquals(listOf("buena"), result.tasks.map { it.body })
        assertNotNull("el fichero malo se conserva para poder inspeccionarlo", result.quarantinedAt)
        assertTrue(Files.exists(result.quarantinedAt!!))
    }

    @Test
    fun `un fichero corrupto sin backup no inventa datos`() = runBlocking {
        val s = store()
        Files.createDirectories(layout().repoDir(repo))
        Files.writeString(layout().tasksFile(repo), "basura")

        val result = s.read(repo)
        assertTrue(result is TaskFileStore.ReadResult.Corrupt)
        assertFalse((result as TaskFileStore.ReadResult.Corrupt).recoveredFromBackup)
        assertTrue(result.tasks.isEmpty())
    }

    @Test
    fun `no queda ningun fichero temporal tras escribir`() = runBlocking {
        store().write(repo, listOf(task("a", "x")))
        val leftovers = Files.list(layout().repoDir(repo)).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.count()
        }
        assertEquals(0L, leftovers)
    }
}
