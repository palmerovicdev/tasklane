package com.tasklane.data.sqlite

import com.tasklane.bench.SyntheticCorpus
import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.withStore
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.model.RepoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El auditor de las invariantes que `integrity_check` no ve. Si esto no detecta una
 * contabilidad rota, las pruebas de caída de la Fase 6 no prueban nada; y si [StoreAudit.rebuild]
 * no la arregla, la recuperación tampoco.
 */
class StoreAuditTest {

    private val corpus = SyntheticCorpus.tasks(300, RepoKey("root"), config = CONFIG)

    private fun TaskStore.seed() {
        importBatch(corpus.take(200), CONFIG)
        apply(CONFIG, listOf(Mutation.Upsert(corpus.drop(200))))
        apply(CONFIG, listOf(Mutation.Delete(corpus.take(10).map { it.id })))
    }

    @Test
    fun `una base escrita por el almacen no tiene nada que decir`() = withStore { store, db ->
        store.seed()
        assertEquals(emptyList<String>(), StoreAudit.check(db.writer))
    }

    @Test
    fun `ve cada una de las cosas que se pueden romper sin que SQLite se entere`() = withStore { store, db ->
        store.seed()
        val sql = db.writer
        sql.execute("UPDATE counter SET n = n + 1 WHERE rowid = (SELECT min(rowid) FROM counter)")
        sql.execute("UPDATE priority_counter SET n = n - 1 WHERE rowid = (SELECT min(rowid) FROM priority_counter)")
        // Una tarea que se va sin pasar por el índice de texto: justo lo que no cascadea.
        sql.execute("DELETE FROM task WHERE seq = (SELECT max(seq) FROM task)")
        sql.execute("UPDATE tag SET sort_date = sort_date + 1 WHERE rowid = (SELECT min(rowid) FROM tag)")
        sql.execute("UPDATE task SET has_anchor = 1 - has_anchor WHERE seq = (SELECT min(seq) FROM task)")

        val problems = StoreAudit.check(sql)
        for (needle in listOf("contadores de estado", "contadores de prioridad", "índice de texto", "tupla de orden", "etiquetas o anclas")) {
            assertTrue("no vio «$needle» en $problems", problems.any { needle in it })
        }
    }

    @Test
    fun `rehacer lo derivado lo deja todo cuadrado y la busqueda funciona`() = withStore { store, db ->
        store.seed()
        val sql = db.writer
        sql.execute("DELETE FROM counter")
        sql.execute("DELETE FROM task_fts_docsize")
        sql.execute("UPDATE tag SET state = 'x'")
        assertTrue(StoreAudit.check(sql).isNotEmpty())

        sql.transaction { StoreAudit.rebuild(sql) }

        assertEquals(emptyList<String>(), StoreAudit.check(sql))
        assertEquals(290, sql.count("SELECT sum(n) FROM counter"))
        assertTrue(sql.count("SELECT count(*) FROM task_fts WHERE task_fts MATCH 'token'") > 0)
    }
}
