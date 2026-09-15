package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.sqlite.StoreFixture.task
import com.tasklane.data.sqlite.StoreFixture.withStore
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.paging.Cursor
import com.tasklane.paging.GroupOutline
import com.tasklane.paging.MemoryPager
import com.tasklane.paging.PageQuery
import com.tasklane.paging.TaskPager
import com.tasklane.service.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * **Dos implementaciones de la misma interfaz, sobre el mismo corpus, tienen que dar
 * exactamente la misma lista.**
 *
 * Es la mejor prueba que se puede escribir de la Fase 3, y la razón de que
 * `MemoryPager` no se borrara al entrar `SqlitePager`: el paginador nuevo no ordena
 * nada —se lo da hecho un índice— y el viejo ordena con un comparador que lleva dos
 * fases funcionando. Si los dos coinciden fila a fila en las cuatro agrupaciones y los
 * filtros, el índice **es** el comparador.
 *
 * El corpus está pensado para que el acuerdo cueste: fechas repetidas al milisegundo
 * —que es lo que obliga a un desempate—, ids que no van en el orden de inserción,
 * tareas marcadas repartidas por todas las prioridades, y tanto tareas con varias
 * etiquetas como sin ninguna.
 */
class SqlitePagerTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 15)
    private val now: Instant = today.atTime(10, 0).atZone(zone).toInstant()
    private val todo = TasklaneConfig.TODO

    private fun config(grouping: Grouping, anchor: DateAnchor = DateAnchor.UPDATED) =
        TasklaneConfig.DEFAULT.let { base ->
            base.copy(
                states = base.states.map {
                    if (it.id == todo) TaskState(todo, "ToDo", 0, grouping, anchor, isDefault = true) else it
                },
            )
        }.normalized()

    /**
     * Doscientas tareas repartidas a mala idea: seis días distintos con fechas que se
     * repiten, las tres prioridades, una de cada siete marcada y una de cada cinco con
     * dos etiquetas.
     */
    private fun corpus(): List<Task> = (0 until 200).map { i ->
        val daysBack = longArrayOf(0, 0, 1, 3, 40, 400)[i % 6]
        // A propósito al minuto y no al milisegundo: así hay empates de verdad.
        val updated = now.minusSeconds(daysBack * 86_400 + (i % 4) * 60L)
        task(
            id = "task-%03d".format((i * 37) % 200),
            body = "Tarea numero $i",
            state = if (i % 11 == 0) TasklaneConfig.DONE else todo,
            priority = listOf(TasklaneConfig.LOW, TasklaneConfig.NORMAL, TasklaneConfig.HIGH)[i % 3],
            createdAt = updated.minusSeconds(1000),
            updatedAt = updated,
            completedAt = if (i % 11 == 0) updated else null,
            bookmarked = i % 7 == 0,
            tags = if (i % 5 == 0) listOf("api", "lote-${i % 3}") else emptyList(),
        )
    }.distinctBy { it.id }

    private fun memory(tasks: List<Task>, grouping: Grouping, filter: TaskFilter, anchor: DateAnchor) =
        MemoryPager(
            tasks = tasks,
            config = config(grouping, anchor),
            found = SearchResults.NONE,
            filter = filter,
            now = now,
            zone = zone,
            today = today,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )

    private fun sqlite(sql: Sql, grouping: Grouping, filter: TaskFilter, anchor: DateAnchor) =
        SqlitePager(
            sql = sql,
            repo = REPO,
            config = config(grouping, anchor),
            filter = filter,
            now = now,
            zone = zone,
            today = today,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )

    /** La lista entera de un grupo, pedida a trozos como la pide la ventana. */
    private fun walk(pager: TaskPager, state: StateId, group: GroupKey?): List<String> {
        var size = 7
        var guard = 0
        while (guard++ < 200) {
            val page = pager.page(PageQuery(state, group, size), null)
            if (page.after == 0) return page.items.map { it.id.value }
            size += 7
        }
        error("la lista no termina nunca")
    }

    @Test
    fun `las dos implementaciones dan la misma lista en las cuatro agrupaciones`() = withStore { store, db ->
        val tasks = corpus()
        store.importBatch(tasks, config(Grouping.NONE))

        for (grouping in Grouping.entries) {
            for (filter in listOf(TaskFilter.ALL, TaskFilter.OPEN, TaskFilter.BOOKMARKED)) {
                val expected = memory(tasks, grouping, filter, DateAnchor.UPDATED)
                val actual = sqlite(db.reader, grouping, filter, DateAnchor.UPDATED)
                val label = "$grouping / $filter"

                assertEquals("contadores de $label", expected.counts(), actual.counts())
                assertEquals("cabeceras de $label", expected.outline(todo), actual.outline(todo))

                val groups: List<GroupKey?> =
                    expected.outline(todo).map { it.key }.ifEmpty { listOf(null) }
                for (group in groups) {
                    assertEquals(
                        "filas de $label, grupo $group",
                        walk(expected, todo, group),
                        walk(actual, todo, group),
                    )
                }
            }
        }
    }

    /**
     * **Exportar da la misma lista que la ventana.** `each` no pasa por la ventana del
     * paginador —ésa existe para crecer y retenerlo todo, que es lo que una exportación de
     * un millón no puede hacer— y por eso tiene su propia ruta por los cubos. Aquí se fija
     * que esa ruta llega a lo mismo, con tandas pequeñas para que los cortes caigan en
     * mitad de cubos y de empates de fecha.
     */
    @Test
    fun `exportar a tandas da la misma lista que la ventana`() = withStore { store, db ->
        val tasks = corpus()
        store.importBatch(tasks, config(Grouping.NONE))

        for (grouping in Grouping.entries) {
            for (filter in listOf(TaskFilter.ALL, TaskFilter.OPEN, TaskFilter.BOOKMARKED)) {
                val expected = memory(tasks, grouping, filter, DateAnchor.UPDATED)
                val actual = sqlite(db.reader, grouping, filter, DateAnchor.UPDATED)
                val groups: List<GroupKey?> = expected.outline(todo).map { it.key }.ifEmpty { listOf(null) }
                for (group in groups) {
                    val chunks = mutableListOf<Int>()
                    val exported = buildList {
                        actual.each(PageQuery(todo, group), chunk = 7) {
                            chunks += it.size
                            addAll(it.map { task -> task.id.value })
                        }
                    }
                    assertEquals("$grouping / $filter, grupo $group", walk(expected, todo, group), exported)
                    assertTrue("ninguna tanda pasa del tope", chunks.all { it <= 7 })
                }
            }
        }
    }

    /**
     * **Una exportación es una foto.** Lo que se escribe mientras se exporta no entra, y
     * la ventana —que no está congelada— sí lo ve. Es lo que impide que una tarea que se
     * marca a mitad de una exportación de minutos salte de cubo y salga dos veces.
     */
    @Test
    fun `la foto de la exportacion no ve lo que se escribe mientras dura`() = withStore { store, db ->
        store.importBatch(corpus(), config(Grouping.NONE))
        val live = SqlitePager(db.reader, REPO, config(Grouping.NONE), TaskFilter.ALL, now, zone, today, detach = db::openReader)

        val seen = live.snapshot { frozen ->
            val first = buildList { frozen.each(PageQuery(todo), chunk = 50) { addAll(it) } }.size
            // Una escritura en medio, por la conexión de siempre.
            store.importBatch(listOf(task("nueva", body = "Escrita durante la exportación")), config(Grouping.NONE))
            val second = buildList { frozen.each(PageQuery(todo), chunk = 50) { addAll(it) } }.size
            first to second
        }

        assertEquals("la foto no se mueve", seen.first, seen.second)
        val after = buildList { live.each(PageQuery(todo), chunk = 50) { addAll(it) } }.size
        assertEquals("fuera de la foto sí se ve", seen.first + 1, after)
    }

    @Test
    fun `agrupar por la fecha de completado coincide tambien`() = withStore { store, db ->
        val tasks = corpus()
        val config = config(Grouping.BY_DATE, DateAnchor.COMPLETED)
        store.importBatch(tasks, config)

        val expected = memory(tasks, Grouping.BY_DATE, TaskFilter.ALL, DateAnchor.COMPLETED)
        val actual = sqlite(db.reader, Grouping.BY_DATE, TaskFilter.ALL, DateAnchor.COMPLETED)

        assertEquals(expected.outline(todo), actual.outline(todo))
        for (group in expected.outline(todo).map { it.key }) {
            assertEquals("grupo $group", walk(expected, todo, group), walk(actual, todo, group))
        }
    }

    @Test
    fun `una pagina nunca trae mas de lo que se le pide`() = withStore { store, db ->
        store.importBatch(corpus(), config(Grouping.NONE))
        val pager = sqlite(db.reader, Grouping.NONE, TaskFilter.ALL, DateAnchor.UPDATED)

        val page = pager.page(PageQuery(todo, null, 10), null)

        assertEquals(10, page.items.size)
        assertTrue("y dice cuántas quedan", page.after > 0)
        assertNull("una lista que empieza por el principio no tiene nada encima", page.before)
    }

    @Test
    fun `ensenar una tarea del fondo abre la ventana a su altura`() = withStore { store, db ->
        val tasks = corpus()
        store.importBatch(tasks, config(Grouping.NONE))
        val pager = sqlite(db.reader, Grouping.NONE, TaskFilter.ALL, DateAnchor.UPDATED)
        val all = walk(pager, todo, null)
        val target = all[all.size - 20]

        val reveal = pager.reveal(todo, TaskId(target))
        assertNotNull(reveal)
        val page = pager.page(PageQuery(todo, reveal!!.group, reveal.size), reveal.from)

        assertTrue("la tarea tiene que verse", page.items.any { it.id.value == target })
        val skipped = page.before?.remaining ?: 0
        assertEquals(
            "la ventana es exactamente ese tramo de la lista",
            all.subList(skipped, skipped + page.items.size),
            page.items.map { it.id.value },
        )
        assertEquals("y lo que queda por debajo cuadra", all.size - skipped - page.items.size, page.after)
    }

    @Test
    fun `ensenar una tarea de arriba carga desde el principio`() = withStore { store, db ->
        store.importBatch(corpus(), config(Grouping.NONE))
        val pager = sqlite(db.reader, Grouping.NONE, TaskFilter.ALL, DateAnchor.UPDATED)
        val first = walk(pager, todo, null).first()

        val reveal = pager.reveal(todo, TaskId(first))!!

        assertNull("nada por encima que anunciar", reveal.from)
        assertEquals(null, reveal.group)
    }

    @Test
    fun `volver hacia arriba desde una ventana saltada no pierde ni repite`() = withStore { store, db ->
        val tasks = corpus()
        store.importBatch(tasks, config(Grouping.NONE))
        val pager = sqlite(db.reader, Grouping.NONE, TaskFilter.ALL, DateAnchor.UPDATED)
        val all = walk(pager, todo, null)
        val target = all[all.size - 20]

        val reveal = pager.reveal(todo, TaskId(target))!!
        var page = pager.page(PageQuery(todo, null, reveal.size), reveal.from)
        var cursor: Cursor? = reveal.from
        var size = reveal.size

        // Tres «cargar lo de arriba» seguidos, como los pulsaría quien sube la lista.
        repeat(3) {
            val before = page.before ?: return@repeat
            cursor = before.cursor
            size += TaskPager.PAGE
            page = pager.page(PageQuery(todo, null, size), cursor)
            val skipped = page.before?.remaining ?: 0
            assertEquals(
                "cada ampliación sigue siendo un tramo exacto de la lista",
                all.subList(skipped, skipped + page.items.size),
                page.items.map { it.id.value },
            )
        }
    }

    @Test
    fun `la pestana cuenta lo mismo que la lista ensena`() = withStore { store, db ->
        val tasks = corpus()
        store.importBatch(tasks, config(Grouping.NONE))

        for (filter in listOf(TaskFilter.ALL, TaskFilter.OPEN, TaskFilter.BOOKMARKED)) {
            val pager = sqlite(db.reader, Grouping.NONE, filter, DateAnchor.UPDATED)
            assertEquals(
                "con el filtro $filter",
                pager.counts()[todo] ?: 0,
                walk(pager, todo, null).size,
            )
        }
    }

    @Test
    fun `las cabeceras no mienten sobre lo que hay dentro`() = withStore { store, db ->
        store.importBatch(corpus(), config(Grouping.BY_TAG))
        val pager = sqlite(db.reader, Grouping.BY_TAG, TaskFilter.ALL, DateAnchor.UPDATED)

        for (outline: GroupOutline in pager.outline(todo)) {
            assertEquals("grupo ${outline.key}", outline.size, walk(pager, todo, outline.key).size)
        }
    }
}
