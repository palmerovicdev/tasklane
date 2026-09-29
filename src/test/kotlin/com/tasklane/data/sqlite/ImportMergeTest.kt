package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.sqlite.StoreFixture.task
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** La regla de meter tareas de fuera sin pisar lo de aquí: una rama por caso. */
class ImportMergeTest {

    private val t0 = Instant.parse("2026-09-01T00:00:00Z")
    private val t1 = Instant.parse("2026-09-10T00:00:00Z")

    @Test
    fun `anade lo nuevo, actualiza lo mas reciente y deja lo demas`() = StoreFixture.withStore { store, _ ->
        store.importBatch(
            listOf(
                task("igual", "aqui", updatedAt = t0),
                task("aqui-mas-nueva", "aqui nueva", updatedAt = t1),
                task("fuera-mas-nueva", "aqui vieja", updatedAt = t0),
                task("en-otro-repo", "de otro", repo = RepoKey("otro"), updatedAt = t0),
            ),
            CONFIG,
        )
        val incoming = listOf(
            task("nueva", "nueva", updatedAt = t0),
            task("igual", "aqui", updatedAt = t0),
            task("aqui-mas-nueva", "de fuera vieja", updatedAt = t0),
            task("fuera-mas-nueva", "de fuera nueva", updatedAt = t1),
            task("en-otro-repo", "intento", updatedAt = t1),
        )

        val plan = ImportMerge.plan(store, REPO, CONFIG, incoming)
        assertEquals(ImportMerge.Counts(added = 1, updated = 1, same = 1, kept = 1, elsewhere = 1), plan.counts)
        assertEquals(setOf("nueva", "fuera-mas-nueva"), plan.write.map { it.id.value }.toSet())

        ImportMerge.apply(store, CONFIG, plan)
        assertEquals("nueva", store.task(TaskId("nueva"))!!.body)
        assertEquals("de fuera nueva", store.task(TaskId("fuera-mas-nueva"))!!.body)
        assertEquals("aqui nueva", store.task(TaskId("aqui-mas-nueva"))!!.body)
        assertEquals("de otro", store.task(TaskId("en-otro-repo"))!!.body)
    }

    @Test
    fun `repetir la importacion no cambia nada`() = StoreFixture.withStore { store, _ ->
        val incoming = (1..5).map { task("t-$it", "cuerpo $it", updatedAt = t0) }
        ImportMerge.apply(store, CONFIG, ImportMerge.plan(store, REPO, CONFIG, incoming))
        val again = ImportMerge.plan(store, REPO, CONFIG, incoming)
        assertEquals(ImportMerge.Counts(same = 5), again.counts)
        assertEquals(0, again.write.size)
        assertEquals(5, store.countOf(REPO))
    }

    @Test
    fun `un estado que aqui no existe cae en el de por defecto`() = StoreFixture.withStore { store, _ ->
        val plan = ImportMerge.plan(store, REPO, CONFIG, listOf(task("x", state = StateId("s-inventado"))))
        assertEquals(CONFIG.defaultState.id, plan.write.single().stateId)
    }
}
