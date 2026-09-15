package com.tasklane.data.sqlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * El fichero abierto: las pragmas que la fase necesita, la promesa de la versión futura
 * y que abrir dos veces no rehaga nada.
 */
class TaskDbTest {

    private fun <T> withDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("tasklane-db")
        return try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `abre con WAL, claves ajenas y la version del esquema`() = withDir { dir ->
        val db = TaskDb.open(dir)!!
        try {
            assertEquals("wal", db.writer.first("PRAGMA journal_mode") { it.getString(0).orEmpty() }?.lowercase())
            assertEquals(1, db.writer.count("PRAGMA foreign_keys"))
            assertEquals(TaskSchema.VERSION, db.writer.count("PRAGMA user_version"))
            assertFalse(db.readOnly)
        } finally {
            db.close()
        }
    }

    /** Abrir un proyecto ya abierto antes no puede rehacer el esquema ni tocar los datos. */
    @Test
    fun `abrir dos veces es abrir`() = withDir { dir ->
        TaskDb.open(dir)!!.let { first ->
            TaskStore(first).importBatch(listOf(StoreFixture.task("a")), StoreFixture.CONFIG)
            first.close()
        }

        val second = TaskDb.open(dir)!!
        try {
            assertEquals(1, second.writer.count("SELECT count(*) FROM task"))
            assertEquals(TaskSchema.VERSION, second.writer.count("PRAGMA user_version"))
        } finally {
            second.close()
        }
    }

    /**
     * La promesa que `TasksCodec` hacía con el atributo `version`, trasladada a
     * `PRAGMA user_version`: una base escrita por una versión **posterior** del plugin
     * se abre en solo lectura y se avisa, en vez de degradarla escribiéndola con un
     * esquema viejo.
     */
    @Test
    fun `una base de una version futura se abre sin escritura`() = withDir { dir ->
        TaskDb.open(dir)!!.let { first ->
            first.writer.execute("PRAGMA user_version = ${TaskSchema.VERSION + 1}")
            first.close()
        }

        val future = TaskDb.open(dir)!!
        try {
            assertTrue(future.readOnly)
            assertEquals(TaskSchema.VERSION + 1, future.version)
            // Y nadie escribe: el almacén se cruza de brazos.
            val store = TaskStore(future)
            assertEquals(0, store.apply(StoreFixture.CONFIG, listOf(
                com.tasklane.domain.command.Mutation.Upsert(listOf(StoreFixture.task("a"))),
            )).rows)
        } finally {
            future.close()
        }
    }

    /** Un directorio imposible no puede tumbar el plugin: se abre sin almacén y ya. */
    @Test
    fun `un sitio donde no se puede escribir devuelve null`() = withDir { dir ->
        val file = dir.resolve("soy-un-fichero")
        Files.writeString(file, "x")
        assertEquals(null, TaskDb.open(file))
    }

    @Test
    fun `el lector ve lo que el escritor confirma`() = withDir { dir ->
        val db = TaskDb.open(dir)!!
        try {
            TaskStore(db).importBatch(listOf(StoreFixture.task("a")), StoreFixture.CONFIG)
            assertNotNull(db.reader.first("SELECT id FROM task WHERE id = 'a'") { it.getString(0).orEmpty() })
        } finally {
            db.close()
        }
    }
}
