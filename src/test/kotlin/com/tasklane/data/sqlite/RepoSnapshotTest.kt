package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.sqlite.StoreFixture.task
import com.tasklane.data.sqlite.StoreFixture.withStore
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La foto de un repositorio con la que se exporta a XML y con la que se exporta **antes
 * de quitarlo**. En la segunda, lo que salga es lo que se borra: estos casos fijan que
 * sale **todo** y que no se mueve mientras se lee.
 */
class RepoSnapshotTest {

    private val otro = RepoKey("otro")

    @Test
    fun `cuenta y reparte por estado todo lo del repositorio, y nada de los demas`() = withStore { store, db ->
        store.importBatch(
            (0 until 30).map {
                task(
                    "t$it",
                    state = listOf(TasklaneConfig.TODO, TasklaneConfig.DOING, TasklaneConfig.DONE)[it % 3],
                    bookmarked = it % 4 == 0,
                    priority = CONFIG.priorities[it % CONFIG.priorities.size].id,
                )
            } + task("ajena", repo = otro),
            CONFIG,
        )

        RepoSnapshot.open(db, REPO, CONFIG) { snapshot ->
            assertEquals(30, snapshot.total)
            assertEquals(
                "en el orden de la configuración",
                listOf(TasklaneConfig.TODO, TasklaneConfig.DOING, TasklaneConfig.DONE),
                snapshot.states(),
            )
            val ids = buildList {
                for (state in snapshot.states()) snapshot.eachInState(state, chunk = 4) { chunk -> chunk.forEach { add(it.id.value) } }
            }
            assertEquals("todas, y cada una una vez", 30, ids.toSet().size)
            assertEquals(30, ids.size)
            assertEquals(30, buildList { snapshot.eachInOrder(chunk = 7) { addAll(it) } }.size)
        }
    }

    /**
     * Un estado que la configuración ya no conoce **sale igual**, detrás de los demás. Es
     * la mitad de la corrección de «Exportar y quitar»: iterar la configuración se dejaría
     * sus tareas fuera, y el `DELETE` de después se las llevaría sin copia.
     */
    @Test
    fun `un estado que ya no esta en la configuracion tambien sale`() = withStore { store, db ->
        val borrado = StateId("s-borrado")
        store.importBatch(listOf(task("a"), task("b", state = borrado)), CONFIG)

        RepoSnapshot.open(db, REPO, CONFIG) { snapshot ->
            assertEquals(listOf(TasklaneConfig.TODO, borrado), snapshot.states())
            val fromGone = buildList { snapshot.eachInState(borrado) { addAll(it) } }
            assertEquals(listOf("b"), fromGone.map { it.id.value })
        }
    }

    @Test
    fun `lo que se escribe mientras la foto esta abierta no entra`() = withStore { store, db ->
        store.importBatch((0 until 10).map { task("t$it") }, CONFIG)

        RepoSnapshot.open(db, REPO, CONFIG) { snapshot ->
            store.importBatch(listOf(task("tarde")), CONFIG)
            assertEquals(10, snapshot.total)
            assertEquals(10, buildList { snapshot.eachInState(TasklaneConfig.TODO) { addAll(it) } }.size)
        }
        RepoSnapshot.open(db, REPO, CONFIG) { assertEquals(11, it.total) }
    }
}
