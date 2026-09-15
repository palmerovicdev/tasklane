package com.tasklane.paging

import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.domain.query.TaskQuery
import com.tasklane.service.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * El contrato que hace posible la lista acotada: **nada de lo que se pide aquí
 * devuelve la lista entera**.
 *
 * Los casos están escritos contra [TaskPager] y no contra [InMemoryPager], que es
 * deliberado: en la Fase 3 entra otra implementación sobre SQLite y estos mismos casos
 * tienen que seguir describiéndola. Lo único que mira dentro es el escenario del
 * cursor, y ni siquiera ése lo abre: lo pide por un lado y lo devuelve por el otro.
 */
class InMemoryPagerTest {

    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 15)
    private val todo = TasklaneConfig.TODO
    private val done = TasklaneConfig.DONE

    private fun task(
        id: String,
        stateId: StateId = todo,
        tags: List<String> = emptyList(),
        bookmarked: Boolean = false,
        updatedAt: Instant = now,
    ) = Task(
        id = TaskId(id),
        repo = RepoKey.ROOT,
        body = id,
        stateId = stateId,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = now,
        updatedAt = updatedAt,
        tags = tags,
        bookmarked = bookmarked,
    )

    /** La pestaña *ToDo* agrupando como se le pida; el resto, la configuración de fábrica. */
    private fun config(grouping: Grouping) = TasklaneConfig.DEFAULT.let { base ->
        base.copy(
            states = base.states.map {
                if (it.id == todo) TaskState(todo, "ToDo", 0, grouping, DateAnchor.UPDATED, isDefault = true) else it
            },
        )
    }

    private fun pager(
        tasks: List<Task>,
        grouping: Grouping = Grouping.NONE,
        found: SearchResults = SearchResults.NONE,
        filter: TaskFilter = TaskFilter.ALL,
    ) = InMemoryPager(
        snapshot = TasklaneSnapshot(
            config = config(grouping),
            tasksByRepo = mapOf(RepoKey.ROOT to tasks),
            activeRepo = RepoKey.ROOT,
        ),
        found = found,
        filter = filter,
        now = now,
        zone = zone,
        today = today,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    /** Tareas numeradas, con el orden natural ya decidido: la 0 arriba, la última abajo. */
    private fun many(count: Int, stateId: StateId = todo): List<Task> =
        List(count) { i -> task("t%05d".format(i), stateId, updatedAt = now.minusSeconds(i.toLong())) }

    private fun ids(page: TaskPage) = page.items.map { it.id.value }

    // ------------------------------------------------------------------ páginas

    @Test
    fun `una pagina nunca trae mas de lo que se le pide`() {
        val pager = pager(many(1_000))

        val page = pager.page(PageQuery(todo))

        assertEquals(TaskPager.PAGE, page.items.size)
        assertEquals("t00000", page.items.first().id.value)
        assertEquals("lo que queda se anuncia, no se trae", 1_000 - TaskPager.PAGE, page.after)
        assertNull("una lista que empieza por el principio no tiene nada encima", page.before)
    }

    /**
     * Ampliar la ventana es pedir **más límite desde el mismo sitio**, y no «la
     * siguiente página»: el árbol se vuelve a sincronizar en cada repintado, así que
     * lo que tiene que poder repetirse es la ventana entera.
     */
    @Test
    fun `ampliar la ventana es pedir mas limite`() {
        val pager = pager(many(1_000))

        val page = pager.page(PageQuery(todo, limit = 2 * TaskPager.PAGE))

        assertEquals(2 * TaskPager.PAGE, page.items.size)
        assertEquals("t00000", page.items.first().id.value)
        assertEquals(1_000 - 2 * TaskPager.PAGE, page.after)
    }

    @Test
    fun `una ventana que empieza mas abajo anuncia lo que deja encima`() {
        val pager = pager(many(1_000))
        val jump = pager.reveal(todo, TaskId("t00500"))!!

        val page = pager.page(PageQuery(todo, jump.group, jump.size), jump.from)

        assertNotNull("la ventana empieza por debajo del principio", page.before)
        assertEquals("y dice cuántas deja arriba", 500 - TaskPager.PAGE / 2, page.before!!.remaining)
        assertTrue("la tarea que se quería enseñar está dentro", ids(page).contains("t00500"))
        assertEquals(TaskPager.PAGE, page.items.size)
    }

    /** El cursor del centinela de arriba abre la ventana **una página** más arriba. */
    @Test
    fun `el cursor de arriba sube una pagina`() {
        val pager = pager(many(1_000))
        val jump = pager.reveal(todo, TaskId("t00500"))!!
        val first = pager.page(PageQuery(todo, jump.group, jump.size), jump.from)

        val second = pager.page(
            PageQuery(todo, jump.group, jump.size + TaskPager.PAGE),
            first.before!!.cursor,
        )

        val top = 500 - TaskPager.PAGE / 2 - TaskPager.PAGE
        assertEquals("t%05d".format(top), second.items.first().id.value)
        assertEquals(2 * TaskPager.PAGE, second.items.size)
        assertEquals(top, second.before!!.remaining)
        // Ni se repite ni se pierde nada por el camino: la ventana nueva contiene la vieja.
        assertTrue(ids(second).containsAll(ids(first)))
    }

    @Test
    fun `pedir mas alla del final no rompe ni inventa`() {
        val pager = pager(many(5))

        val page = pager.page(PageQuery(todo, limit = TaskPager.PAGE))

        assertEquals(5, page.items.size)
        assertEquals(0, page.after)
    }

    // ------------------------------------------------------------------ agregados

    @Test
    fun `el contorno da cabeceras y cuentas, no contenido`() {
        val pager = pager(many(250) + many(3, done).map { it.copy(tags = listOf("x")) }, Grouping.BY_TAG)

        val outline = pager.outline(todo)

        assertEquals(1, outline.size)
        assertEquals("sin etiqueta van a su propio cajón", GroupKey.OfTag(null), outline.single().key)
        assertEquals("y la cabecera sabe cuántas hay sin tenerlas", 250, outline.single().size)
    }

    @Test
    fun `sin agrupacion no hay contorno y la raiz se pagina igual`() {
        val pager = pager(many(3 * TaskPager.PAGE), Grouping.NONE)

        assertTrue(pager.outline(todo).isEmpty())
        assertEquals(TaskPager.PAGE, pager.page(PageQuery(todo)).items.size)
        assertEquals(2 * TaskPager.PAGE, pager.page(PageQuery(todo)).after)
    }

    @Test
    fun `los contadores cuentan tareas y salen de la misma pasada que la lista`() {
        val pager = pager(many(7) + many(4, done))

        assertEquals(mapOf(todo to 7, done to 4), pager.counts())
        assertEquals(7, pager.all(PageQuery(todo)).size)
    }

    /**
     * Una tarea con dos etiquetas sale en dos filas y el contador sigue diciendo una.
     * Es la discrepancia que `VisibleTasks` documenta: cuenta tareas, no filas.
     */
    @Test
    fun `agrupando por etiqueta una tarea sale bajo todas las suyas`() {
        val pager = pager(listOf(task("a", tags = listOf("api", "ui"))), Grouping.BY_TAG)

        val outline = pager.outline(todo)

        assertEquals(listOf(GroupKey.OfTag("api"), GroupKey.OfTag("ui")), outline.map { it.key })
        assertEquals(1, pager.counts()[todo])
    }

    // ------------------------------------------------------------------ enseñar

    @Test
    fun `enseñar algo de arriba carga desde el principio`() {
        val pager = pager(many(1_000))

        val reveal = pager.reveal(todo, TaskId("t00007"))!!

        assertNull("no hay por qué saltar: se ve el contexto de arriba", reveal.from)
        assertEquals(TaskPager.PAGE, reveal.size)
    }

    /**
     * Y ésta es la que justifica que [TaskPager.reveal] exista: sin ella, enseñar la
     * fila 900.000 era llegar hasta ella cargando las 899.999 de antes.
     */
    @Test
    fun `enseñar algo de muy abajo abre la ventana a su altura`() {
        val pager = pager(many(5_000))

        val reveal = pager.reveal(todo, TaskId("t04321"))!!

        assertNotNull(reveal.from)
        assertEquals(TaskPager.PAGE, reveal.size)
        val page = pager.page(PageQuery(todo, reveal.group, reveal.size), reveal.from)
        assertTrue(ids(page).contains("t04321"))
    }

    @Test
    fun `enseñar algo escondido por la busqueda no encuentra nada`() {
        val visible = SearchResults("q", TaskQuery.EMPTY, mapOf(TaskId("t00000") to 1), null)
        val pager = pager(many(10), found = visible)

        assertNotNull(pager.reveal(todo, TaskId("t00000")))
        assertNull("filtrada fuera no se puede enseñar; la pestaña tendrá que quitar el filtro", pager.reveal(todo, TaskId("t00005")))
    }

    @Test
    fun `enseñar dice en que grupo cae`() {
        val pager = pager(listOf(task("a", tags = listOf("api"))), Grouping.BY_TAG)

        assertEquals(GroupKey.OfTag("api"), pager.reveal(todo, TaskId("a"))!!.group)
    }

    // ------------------------------------------------------------------ exportar

    /**
     * La exportación es la única que puede pedir la lista entera —exportar un millón
     * de tareas es leer un millón de tareas— y por eso tiene su propia puerta.
     */
    @Test
    fun `exportar pide la lista sin tope y en el mismo orden`() {
        val pager = pager(many(1_000))

        val all = pager.all(PageQuery(todo))

        assertEquals(1_000, all.size)
        assertEquals(ids(pager.page(PageQuery(todo))), all.take(TaskPager.PAGE).map { it.id.value })
    }

    /** El orden de la lista no cambia porque se pida a trozos: es el mismo comparador. */
    @Test
    fun `lo marcado sigue yendo primero aunque se pagine`() {
        val pager = pager(many(300) + listOf(task("z-marcada", bookmarked = true)))

        assertSame(
            pager.all(PageQuery(todo)).first(),
            pager.page(PageQuery(todo)).items.first(),
        )
        assertEquals("z-marcada", pager.page(PageQuery(todo)).items.first().id.value)
    }
}
