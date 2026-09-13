package com.tasklane.search

import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.AttachmentRef
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskLink
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.domain.query.QueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LinearScanIndexTest {

    private val config = TasklaneConfig.DEFAULT
    private val other = RepoKey("web-1234abcd")
    private val t0 = Instant.parse("2026-09-13T10:00:00Z")

    private fun task(
        id: String,
        body: String,
        repo: RepoKey = RepoKey.ROOT,
        stateId: StateId = TasklaneConfig.TODO,
        priorityId: PriorityId = TasklaneConfig.NORMAL,
        tags: List<String> = emptyList(),
        links: List<TaskLink> = emptyList(),
        attachments: List<AttachmentRef> = emptyList(),
    ) = Task(
        id = TaskId(id),
        repo = repo,
        body = body,
        stateId = stateId,
        priorityId = priorityId,
        createdAt = t0,
        updatedAt = t0,
        tags = tags,
        links = links,
        attachments = attachments,
    )

    private fun snapshot(vararg tasks: Task) = TasklaneSnapshot(
        config = config,
        tasksByRepo = tasks.groupBy { it.repo },
        activeRepo = RepoKey.ROOT,
        repositories = listOf(
            RepositoryRef(RepoKey.ROOT, "proyecto", "/p", RepositoryRef.Kind.PROJECT_ROOT),
            RepositoryRef(other, "web", "/p/web", RepositoryRef.Kind.GIT, depth = 1),
        ),
    )

    private fun index(vararg tasks: Task) = LinearScanIndex().apply { setCorpus(snapshot(*tasks)) }

    private fun LinearScanIndex.find(query: String, scope: SearchScope = SearchScope.Repo(RepoKey.ROOT)) =
        search(QueryParser.parse(query), scope).map { it.task.id.value }

    // ---------------------------------------------------------------- texto

    @Test
    fun `el texto libre busca en todo el cuerpo, no solo en el titulo`() {
        val index = index(
            task("a", "Arreglar login\nfalla el token de refresco"),
            task("b", "Revisar el PR"),
        )
        assertEquals(listOf("a"), index.find("token"))
    }

    @Test
    fun `los diacriticos no cuentan, en los dos sentidos`() {
        val index = index(task("a", "Resolver autenticación"))
        assertEquals(listOf("a"), index.find("autenticacion"))

        val plain = index(task("a", "Resolver autenticacion"))
        assertEquals(listOf("a"), plain.find("autenticación"))
    }

    @Test
    fun `varios terminos se piden todos`() {
        val index = index(
            task("a", "Arreglar el login movil"),
            task("b", "Arreglar el login web"),
        )
        assertEquals(listOf("a"), index.find("arreglar movil"))
    }

    @Test
    fun `un acierto en el titulo gana a uno enterrado en el detalle`() {
        val index = index(
            task("cuerpo", "Revisar el PR\nhabla del token"),
            task("titulo", "Rotar el token"),
        )
        assertEquals(listOf("titulo", "cuerpo"), index.find("token"))
    }

    // ------------------------------------------------------------ operadores

    @Test
    fun `state y p filtran por nombre, con prefijo`() {
        val index = index(
            task("a", "Uno", stateId = TasklaneConfig.DOING),
            task("b", "Dos", stateId = TasklaneConfig.TODO, priorityId = TasklaneConfig.HIGH),
        )
        assertEquals(listOf("a"), index.find("state:doi"))
        assertEquals(listOf("b"), index.find("p:hi"))
    }

    @Test
    fun `is done mira el estado terminal, no el nombre`() {
        val index = index(
            task("abierta", "Uno"),
            task("cerrada", "Dos", stateId = TasklaneConfig.DONE),
        )
        assertEquals(listOf("cerrada"), index.find("is:done"))
        assertEquals(listOf("abierta"), index.find("is:open"))
    }

    @Test
    fun `has filtra por enlaces e imagenes`() {
        val index = index(
            task("link", "Uno", links = listOf(TaskLink("https://x.test", "x.test", 0..14))),
            task("img", "Dos", attachments = listOf(AttachmentRef(AttachmentId("ab"), 0..0))),
            task("nada", "Tres"),
        )
        assertEquals(listOf("link"), index.find("has:link"))
        assertEquals(listOf("img"), index.find("has:image"))
    }

    @Test
    fun `las etiquetas se piden todas y tambien valen como texto libre`() {
        val index = index(
            task("ambas", "Uno", tags = listOf("api", "urgente")),
            task("una", "Dos", tags = listOf("api")),
        )
        assertEquals(listOf("ambas"), index.find("#api #urgente"))
        assertEquals(setOf("ambas", "una"), index.find("api").toSet())
    }

    @Test
    fun `operadores distintos se acumulan`() {
        val index = index(
            task("a", "Arreglar login", stateId = TasklaneConfig.DOING),
            task("b", "Arreglar login", stateId = TasklaneConfig.TODO),
        )
        assertEquals(listOf("a"), index.find("state:doing login"))
    }

    // ---------------------------------------------------------------- alcance

    @Test
    fun `por defecto solo se mira el repositorio pedido`() {
        val index = index(
            task("aqui", "Arreglar login"),
            task("alla", "Arreglar login", repo = other),
        )
        assertEquals(listOf("aqui"), index.find("login"))
        assertEquals(setOf("aqui", "alla"), index.find("login", SearchScope.All).toSet())
    }

    @Test
    fun `repo filtra por nombre visible`() {
        val index = index(
            task("aqui", "Arreglar login"),
            task("alla", "Arreglar login", repo = other),
        )
        assertEquals(listOf("alla"), index.find("repo:web", SearchScope.All))
    }

    // ------------------------------------------------------------ degenerados

    @Test
    fun `una consulta vacia devuelve el corpus del alcance, sin filtrar`() {
        val index = index(task("a", "Uno"), task("b", "Dos", repo = other))
        assertEquals(listOf("a"), index.find(""))
        assertEquals(2, index.find("", SearchScope.All).size)
    }

    @Test
    fun `editar el texto de una tarea invalida su documento cacheado`() {
        val index = LinearScanIndex()
        val before = task("a", "Arreglar login")
        index.setCorpus(snapshot(before))
        assertEquals(listOf("a"), index.find("login"))

        index.setCorpus(snapshot(before.copy(body = "Arreglar el despliegue")))
        assertTrue(index.find("login").isEmpty())
        assertEquals(listOf("a"), index.find("despliegue"))
    }

    @Test
    fun `una tarea borrada no deja su documento detras`() {
        val index = LinearScanIndex()
        index.setCorpus(snapshot(task("a", "Uno"), task("b", "Dos")))
        index.setCorpus(snapshot(task("a", "Uno")))
        assertTrue(index.find("dos").isEmpty())
    }

    /**
     * El criterio de la Fase 4: 5.000 tareas sin lag perceptible. No se mide tiempo
     * —un test que cronometra falla en la máquina equivocada—, se comprueba que el
     * escaneo es correcto a esa escala y que el cacheo de documentos no se rompe al
     * repetir la consulta.
     */
    @Test
    fun `escala a cinco mil tareas`() {
        val tasks = (0 until 5_000).map { i ->
            task("t$i", "Tarea numero $i\ncuerpo de relleno con algo de texto para normalizar")
        }
        val index = LinearScanIndex().apply { setCorpus(snapshot(*tasks.toTypedArray())) }

        assertEquals(listOf("t4999"), index.find("numero 4999"))
        assertEquals(5_000, index.find("relleno").size)
        // Segunda pasada: ahora sobre documentos cacheados, mismo resultado.
        assertEquals(5_000, index.find("relleno").size)
    }
}
