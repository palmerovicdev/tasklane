package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.sqlite.StoreFixture.task
import com.tasklane.data.sqlite.StoreFixture.withStore
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.paging.MemoryPager
import com.tasklane.paging.PageQuery
import com.tasklane.service.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * El orden manual (2.11.0): colocar una tarea entre dos, sembrar el orden al pasar a mano
 * y que los dos paginadores sigan dando la misma lista.
 */
class ManualOrderTest {

    private val now = Instant.parse("2026-09-24T10:00:00Z")
    private val todo = TasklaneConfig.TODO

    private fun config(manual: Boolean, grouping: Grouping = Grouping.NONE) = CONFIG.copy(
        states = CONFIG.states.map { if (it.id == todo) it.copy(manualOrder = manual, grouping = grouping) else it },
    ).normalized()

    private fun list(sql: Sql, config: TasklaneConfig): List<String> {
        val pager = SqlitePager(sql, REPO, config, TaskFilter.ALL, now, ZoneId.of("UTC"))
        val outline = pager.outline(todo)
        val groups = if (outline.isEmpty()) listOf(null) else outline.map { it.key }
        return groups.flatMap { pager.page(PageQuery(todo, it, limit = 100)).items.map { t -> t.id.value } }
    }

    private fun seeded(): List<Task> = listOf(
        task("a", order = 5000),
        task("b", order = 4000),
        task("c", order = 3000),
        task("d", order = 2000),
        task("e", order = 1000),
    )

    private fun TaskStore.move(id: String, above: String?, below: String?) =
        apply(config(true), listOf(Mutation.Place(REPO, TaskId(id), above?.let(::TaskId), below?.let(::TaskId))))

    @Test
    fun `a mano la lista sigue a ord, de mayor a menor`() = withStore { store, db ->
        store.apply(config(true), listOf(Mutation.Upsert(seeded())))
        assertEquals(listOf("a", "b", "c", "d", "e"), list(db.reader, config(true)))
    }

    @Test
    fun `colocar entre dos, arriba del todo y abajo del todo`() = withStore { store, db ->
        store.apply(config(true), listOf(Mutation.Upsert(seeded())))

        store.move("e", "a", "b")
        assertEquals(listOf("a", "e", "b", "c", "d"), list(db.reader, config(true)))

        store.move("d", null, "a")
        assertEquals(listOf("d", "a", "e", "b", "c"), list(db.reader, config(true)))

        store.move("d", "c", null)
        assertEquals(listOf("a", "e", "b", "c", "d"), list(db.reader, config(true)))
    }

    @Test
    fun `sin hueco se vuelve a espaciar y la tarea cae donde se solto`() = withStore { store, db ->
        store.apply(
            config(true),
            listOf(Mutation.Upsert(listOf(task("a", order = 11), task("b", order = 10), task("c", order = 9)))),
        )
        store.move("c", "a", "b")
        assertEquals(listOf("a", "c", "b"), list(db.reader, config(true)))
    }

    @Test
    fun `lo marcado sigue arriba y no se cruza`() = withStore { store, db ->
        store.apply(
            config(true),
            listOf(Mutation.Upsert(listOf(task("m", order = 100, bookmarked = true)) + seeded())),
        )
        // Soltar «e» encima de la marcada es soltarla al principio de las suyas.
        store.move("e", null, "m")
        assertEquals(listOf("m", "a", "b", "c", "d", "e"), list(db.reader, config(true)))
        store.move("e", "m", "a")
        assertEquals(listOf("m", "e", "a", "b", "c", "d"), list(db.reader, config(true)))
    }

    /**
     * Deshacer un reordenar (2.16.0): se guarda entre qué dos estaba, no su `ord`, porque
     * colocarla puede haber reespaciado el estado entero. Éste es ese caso.
     */
    @Test
    fun `deshacer un reordenar la devuelve a su sitio aunque se haya reespaciado`() = withStore { store, db ->
        store.apply(
            config(true),
            listOf(Mutation.Upsert(listOf(task("a", order = 11), task("b", order = 10), task("c", order = 9)))),
        )
        val (above, below) = store.neighbours(TaskId("c"))!!
        assertEquals(TaskId("b") to null, above to below)

        store.move("c", "a", "b")
        assertEquals(listOf("a", "c", "b"), list(db.reader, config(true)))

        store.move("c", above?.value, below?.value)
        assertEquals(listOf("a", "b", "c"), list(db.reader, config(true)))
    }

    @Test
    fun `las vecinas son las de su lado de la marca`() = withStore { store, _ ->
        store.apply(
            config(true),
            listOf(Mutation.Upsert(listOf(task("m", order = 100, bookmarked = true)) + seeded())),
        )
        assertEquals(null to TaskId("b"), store.neighbours(TaskId("a")))
        assertEquals(null to null, store.neighbours(TaskId("m")))
        assertEquals(TaskId("d") to null, store.neighbours(TaskId("e")))
        assertEquals(null, store.neighbours(TaskId("nadie")))
    }

    @Test
    fun `sembrar deja la lista como se estaba viendo`() = withStore { store, db ->
        val tasks = listOf(
            task("baja", priority = TasklaneConfig.LOW, updatedAt = now.minusSeconds(10), order = 9000),
            task("alta", priority = TasklaneConfig.HIGH, updatedAt = now.minusSeconds(5000), order = 1000),
            task("normal-nueva", updatedAt = now.minusSeconds(1), order = 2000),
            task("normal-vieja", updatedAt = now.minusSeconds(9000), order = 8000),
        )
        store.apply(config(false), listOf(Mutation.Upsert(tasks)))
        val before = list(db.reader, config(false))

        store.apply(config(true), listOf(Mutation.SeedOrder(todo)))

        assertEquals(before, list(db.reader, config(true)))
    }

    @Test
    fun `SQL y memoria ordenan igual a mano, tambien agrupando`() = withStore { store, db ->
        for (grouping in Grouping.entries) {
            val config = config(true, grouping)
            val tasks = (0 until 40).map { i ->
                task(
                    "t-%02d".format((i * 7) % 40),
                    order = ((i * 13) % 17) * 1000L,
                    bookmarked = i % 9 == 0,
                    priority = listOf(TasklaneConfig.LOW, TasklaneConfig.NORMAL, TasklaneConfig.HIGH)[i % 3],
                    tags = if (i % 4 == 0) listOf("x") else emptyList(),
                    updatedAt = now.minusSeconds(i * 86_400L),
                )
            }
            store.apply(config, listOf(Mutation.Upsert(tasks)))
            val memory = MemoryPager(tasks, config, SearchResults.NONE, TaskFilter.ALL, now)
            val outline = memory.outline(todo)
            val groups = if (outline.isEmpty()) listOf(null) else outline.map { it.key }
            val fromMemory = groups.flatMap { memory.page(PageQuery(todo, it, limit = 100)).items.map { t -> t.id.value } }

            assertEquals("$grouping", fromMemory, list(db.reader, config))
        }
    }
}
