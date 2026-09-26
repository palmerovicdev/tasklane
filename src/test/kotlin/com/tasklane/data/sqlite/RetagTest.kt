package com.tasklane.data.sqlite

import com.tasklane.domain.command.Mutation
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.TagCount
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.query.QueryParser
import com.tasklane.search.SearchScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Renombrar, fusionar y borrar etiquetas (P30): las consultas del almacén que usa la tabla
 * de los ajustes y [TaskCommand.Retag] escrito de verdad, con la tabla `tag` y el índice de
 * texto que sostienen la agrupación y la búsqueda por etiqueta.
 */
class RetagTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val t0 = Instant.parse("2026-09-20T10:00:00Z")
    private val later = Instant.parse("2026-09-26T10:00:00Z")
    private val reducer = TaskReducer(Clock.fixed(later, ZoneOffset.UTC))
    private val config = TasklaneConfig.DEFAULT.normalized()
    private val repo = RepoKey.ROOT
    private val other = RepoKey("web-1234abcd")

    private lateinit var db: TaskDb
    private lateinit var store: TaskStore

    @Before
    fun open() {
        db = TaskDb.open(folder.newFolder("tasklane").toPath())!!
        store = TaskStore(db)
    }

    @After
    fun close() = db.close()

    private fun task(id: String, vararg tags: String, repo: RepoKey = this.repo) = Task(
        id = TaskId(id),
        repo = repo,
        body = "Tarea $id",
        stateId = TasklaneConfig.TODO,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = t0,
        updatedAt = t0,
        order = 1000L,
        tags = tags.toList(),
    )

    private fun seed(vararg tasks: Task) {
        store.write { store.apply(config, listOf(Mutation.Upsert(tasks.toList()))) }
    }

    /** Lo que hace `TaskService.retag`, sin el servicio: leer, planificar y escribir. */
    private fun retag(repo: RepoKey, renames: Map<String, String?>) {
        val ids = store.taskIdsTagged(repo, renames.keys)
        val command = TaskCommand.Batch(ids.map { TaskCommand.Retag(repo, it, renames) })
        store.write {
            val plan = reducer.plan(TaskReducer.Subject(config, store.tasks(reducer.targetsOf(command))), command)
            store.apply(plan.config, plan.mutations)
        }
    }

    private fun tagsOf(id: String): List<String> = store.task(TaskId(id))!!.tags

    @Test
    fun `las etiquetas del repositorio con su cuenta, las mas usadas primero y tal cual`() {
        seed(task("a", "api", "ui"), task("b", "api"), task("c", "API"), task("d", "api", repo = other))
        assertEquals(
            listOf(TagCount("api", 2), TagCount("API", 1), TagCount("ui", 1)),
            store.tagCounts(repo),
        )
        assertEquals(listOf(TagCount("api", 1)), store.tagCounts(other))
        assertEquals(setOf("a", "b"), store.taskIdsTagged(repo, listOf("api")).map { it.value }.toSet())
        assertEquals(setOf("api"), store.tagsOutside(repo))
    }

    @Test
    fun `renombrar cambia la etiqueta en sus tareas y solo en las del repositorio`() {
        seed(task("a", "api", "ui"), task("b", "api"), task("d", "api", repo = other))
        retag(repo, mapOf("api" to "backend"))
        assertEquals(listOf("backend", "ui"), tagsOf("a"))
        assertEquals(listOf("backend"), tagsOf("b"))
        assertEquals(listOf("api"), tagsOf("d"))
        assertEquals(listOf(TagCount("backend", 2), TagCount("ui", 1)), store.tagCounts(repo))
    }

    @Test
    fun `fusionar no deja la etiqueta repetida en la tarea que llevaba las dos`() {
        seed(task("a", "apis", "api"), task("b", "apis"))
        retag(repo, mapOf("apis" to "api"))
        assertEquals(listOf("api"), tagsOf("a"))
        assertEquals(listOf("api"), tagsOf("b"))
        assertEquals(listOf(TagCount("api", 2)), store.tagCounts(repo))
    }

    @Test
    fun `borrar la quita, y una tarea sin etiquetas sigue siendo una tarea`() {
        seed(task("a", "wip"), task("b", "wip", "ui"))
        retag(repo, mapOf("wip" to null))
        assertEquals(emptyList<String>(), tagsOf("a"))
        assertEquals(listOf("ui"), tagsOf("b"))
        assertEquals(listOf(TagCount("ui", 1)), store.tagCounts(repo))
    }

    @Test
    fun `intercambiar dos nombres no los junta en la tarea que lleva los dos`() {
        seed(task("a", "front", "back"))
        retag(repo, mapOf("front" to "back", "back" to "front"))
        assertEquals(listOf("back", "front"), tagsOf("a"))
    }

    @Test
    fun `renombrar no es editar la tarea, asi que no cambia de grupo de fecha`() {
        seed(task("a", "api"))
        retag(repo, mapOf("api" to "backend"))
        val a = store.task(TaskId("a"))!!
        assertEquals(t0, a.updatedAt)
        assertEquals(listOf("backend"), a.tags)
    }

    @Test
    fun `la busqueda por etiqueta sigue al nombre nuevo`() {
        seed(task("a", "api"))
        retag(repo, mapOf("api" to "backend"))
        val index = Fts5Index(db.reader)
        fun found(raw: String) = index.search(QueryParser.parse(raw), SearchScope.Repo(repo)).map { it.task.id.value }
        assertEquals(listOf("a"), found("#backend"))
        assertEquals(emptyList<String>(), found("#api"))
    }

    @Test
    fun `una tarea que ya no lleva la etiqueta no se toca`() {
        seed(task("a", "ui"))
        val plan = reducer.plan(
            TaskReducer.Subject(config, listOf(store.task(TaskId("a"))!!)),
            TaskCommand.Retag(repo, TaskId("a"), mapOf("api" to "backend")),
        )
        assertTrue(plan.isEmpty)
    }
}
