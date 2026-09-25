package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.sqlite.StoreFixture.task
import com.tasklane.data.sqlite.StoreFixture.withStore
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.paging.PageQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * El archivo de lo terminado (2.10.0): en un estado terminal no se enseña lo cerrado antes
 * del corte, y la lista, las cabeceras y el contador de la pestaña dicen lo mismo.
 */
class ArchivePagerTest {

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-09-24T10:00:00Z")
    private val cutoff = now.minus(Duration.ofDays(30))
    private val done = TasklaneConfig.DONE

    private fun days(n: Long) = now.minus(Duration.ofDays(n))

    private fun corpus(): List<Task> = listOf(
        task("reciente", state = done, completedAt = days(2), updatedAt = days(2)),
        task("mes", state = done, completedAt = days(29), updatedAt = days(29), tags = listOf("api")),
        task("viejo", state = done, completedAt = days(45), updatedAt = days(45), tags = listOf("api")),
        task("viejisimo", state = done, completedAt = days(400), updatedAt = days(400)),
        // Terminal sin fecha de cierre: no se puede dar por vieja, se enseña.
        task("sin-fecha", state = done, updatedAt = days(500)),
        // Abierta y vieja: el archivo no toca lo que no está terminado.
        task("abierta", updatedAt = days(300)),
    )

    private fun config(grouping: Grouping) = CONFIG.copy(
        states = CONFIG.states.map { if (it.id == done) it.copy(grouping = grouping, anchor = DateAnchor.COMPLETED) else it },
    ).normalized()

    private fun pager(sql: Sql, config: TasklaneConfig, archivedBefore: Instant? = cutoff) =
        SqlitePager(sql, REPO, config, TaskFilter.ALL, now, zone, archivedBefore = archivedBefore)

    private fun ids(pager: SqlitePager, config: TasklaneConfig): List<String> {
        val outline = pager.outline(done)
        val groups = if (outline.isEmpty()) listOf(null) else outline.map { it.key }
        return groups.flatMap { pager.page(PageQuery(done, it, limit = 100)).items.map { t -> t.id.value } }.distinct()
    }

    @Test
    fun `sin agrupar esconde lo cerrado antes del corte y lo cuenta`() = withStore { store, db ->
        val config = config(Grouping.NONE)
        store.apply(config, listOf(Mutation.Upsert(corpus())))
        val pager = pager(db.reader, config)

        assertEquals(setOf("reciente", "mes", "sin-fecha"), ids(pager, config).toSet())
        assertEquals(3, pager.counts()[done])
        assertEquals(2, pager.archived(done))
        assertEquals(1, pager.counts()[TasklaneConfig.TODO])
    }

    @Test
    fun `por fecha y por etiqueta las cabeceras no cuentan lo archivado`() = withStore { store, db ->
        for (grouping in listOf(Grouping.BY_DATE, Grouping.BY_TAG, Grouping.BY_PRIORITY)) {
            val config = config(grouping)
            store.apply(config, listOf(Mutation.Upsert(corpus())))
            val pager = pager(db.reader, config)
            val listed = ids(pager, config)
            assertEquals("$grouping", setOf("reciente", "mes", "sin-fecha"), listed.toSet())
            assertEquals("$grouping", 3, pager.outline(done).sumOf { it.size })
        }
    }

    @Test
    fun `sin corte se ve todo`() = withStore { store, db ->
        val config = config(Grouping.NONE)
        store.apply(config, listOf(Mutation.Upsert(corpus())))
        val pager = pager(db.reader, config, archivedBefore = null)

        assertEquals(5, ids(pager, config).size)
        assertEquals(0, pager.archived(done))
    }

    @Test
    fun `una tarea archivada no se puede ensenar hasta que se enseña lo archivado`() = withStore { store, db ->
        val config = config(Grouping.NONE)
        store.apply(config, listOf(Mutation.Upsert(corpus())))

        assertNull(pager(db.reader, config).reveal(done, TaskId("viejo")))
        assertNotNull(pager(db.reader, config, archivedBefore = null).reveal(done, TaskId("viejo")))
    }
}
