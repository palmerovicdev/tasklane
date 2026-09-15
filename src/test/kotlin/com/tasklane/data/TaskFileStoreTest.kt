package com.tasklane.data

import com.tasklane.data.store.LoadAlert
import com.tasklane.data.store.StorageLayout
import com.tasklane.data.store.TaskFileStore
import com.tasklane.data.store.TasksCodec
import com.tasklane.data.store.alert
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** El atributo `version` tal y como lo escribe el codec, y el de un plugin posterior. */
    private val current = """version="${TasksCodec.CURRENT_VERSION}""""
    private val future = """version="99""""

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

        // La otra mitad del criterio de la Fase 7: recuperar **y avisar**.
        val alert = result.alert()
        assertEquals(LoadAlert.Recovered(tasks = 1, quarantinedAt = result.quarantinedAt), alert)
        assertFalse("de un fichero recuperado si se puede seguir escribiendo", alert!!.readOnly)
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
        assertEquals(LoadAlert.Lost(quarantinedAt = result.quarantinedAt), result.alert())
    }

    @Test
    fun `una lectura normal no avisa de nada`() = runBlocking {
        val s = store()
        s.write(repo, listOf(task("a", "buena")))
        assertNull(s.read(repo).alert())
        assertNull(TaskFileStore.ReadResult.Empty.alert())
    }

    @Test
    fun `un fichero de una version futura se abre sin escritura`() = runBlocking {
        // Se escribe uno de verdad y se le sube el numero de version: es exactamente
        // lo que dejaria en disco una version posterior del plugin.
        val s = store()
        s.write(repo, listOf(task("a", "escrita por el futuro")))
        val file = layout().tasksFile(repo)
        Files.writeString(file, Files.readString(file).replaceFirst(current, future))

        val result = s.read(repo)
        assertEquals(listOf("escrita por el futuro"), result.tasks.map { it.body })

        val alert = result.alert()
        assertEquals(LoadAlert.FutureFormat(version = 99), alert)
        assertTrue("no se puede reescribir con el esquema viejo sin degradarlo", alert!!.readOnly)
    }

    @Test
    fun `no queda ningun fichero temporal tras escribir`() = runBlocking {
        store().write(repo, listOf(task("a", "x")))
        val leftovers = Files.list(layout().repoDir(repo)).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.count()
        }
        assertEquals(0L, leftovers)
    }

    /**
     * Quitar un repositorio borra su árbol entero —tareas, copias, adjuntos fragmentados en
     * `ab/cd/`— y cuenta lo que borra. Desde la Fase 5 lo recorre sin ordenar, que es lo que
     * hacía falta para no materializar diez millones de rutas.
     */
    @Test
    fun `borrar un repositorio se lleva el arbol entero y va contando`() = runBlocking {
        val layout = layout()
        store().write(repo, listOf(task("a", "x")))
        val attachments = layout.attachmentsDir(repo)
        for (i in 0 until 12) {
            val leaf = attachments.resolve("%02x".format(i % 3)).resolve("%02x".format(i))
            Files.createDirectories(leaf)
            Files.writeString(leaf.resolve("blob-$i.png"), "png")
        }
        val other = RepoKey("otro")
        store().write(other, listOf(task("b", "y")))

        val counted = mutableListOf<Long>()
        store().delete(repo) { counted += it }

        assertFalse(Files.exists(layout.repoDir(repo)))
        assertTrue("el de al lado no se toca", Files.exists(layout.tasksFile(other)))
        assertEquals("doce capturas y el tasks.xml", 13L, counted.last())
    }
}
