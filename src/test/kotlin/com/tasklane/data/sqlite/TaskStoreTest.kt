package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.sqlite.StoreFixture.task
import com.tasklane.data.sqlite.StoreFixture.withStore
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Lo que el almacén tiene que cumplir para que el resto de la fase se sostenga: que lo
 * que entra vuelve a salir igual, que los contadores no se desvían nunca del `count(*)`
 * de verdad, y que las operaciones de alcance de proyecto hacen lo mismo que hacía el
 * reducer cuando recorría el corpus.
 */
class TaskStoreTest {

    private fun TaskStore.put(vararg tasks: com.tasklane.domain.model.Task) =
        apply(CONFIG, listOf(Mutation.Upsert(tasks.toList())))

    /** Lo que `counter` dice frente a lo que la tabla tiene. Es la invariante de la clase. */
    private fun assertCountersMatch(db: TaskDb) {
        val real = db.writer.rows(
            "SELECT repo, state, count(*), sum(completed_at = ${TaskSchema.NO_DATE}), sum(bookmarked) " +
                "FROM task GROUP BY repo, state",
        ) { listOf(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3), it.getInt(4)) }
        val kept = db.writer.rows("SELECT repo, state, n, n_open, n_marked FROM counter ORDER BY repo, state") {
            listOf(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3), it.getInt(4))
        }
        assertEquals("los contadores tienen que decir lo que dice la tabla", real.sortedBy { it.toString() }, kept.sortedBy { it.toString() })

        val realPriority = db.writer.rows("SELECT repo, priority, count(*) FROM task GROUP BY repo, priority") {
            listOf(it.getString(0), it.getString(1), it.getInt(2))
        }
        val keptPriority = db.writer.rows("SELECT repo, priority, n FROM priority_counter") {
            listOf(it.getString(0), it.getString(1), it.getInt(2))
        }
        assertEquals(realPriority.sortedBy { it.toString() }, keptPriority.sortedBy { it.toString() })
    }

    @Test
    fun `una tarea vuelve a salir tal y como entro`() = withStore { store, _ ->
        val original = task(
            "t1",
            body = "Arreglar el login\nSegunda linea con ![](tasklane:abc) y https://ejemplo.test/x",
            tags = listOf("api", "urgente"),
            anchors = listOf(CodeAnchor.of("src/Auth.kt", 41, 7, "fun login()")),
            dueDate = Instant.parse("2026-02-01T10:00:00Z"),
            bookmarked = true,
            order = 3000,
            extra = mapOf("futuro" to "valor con = y \\ dentro"),
        )
        store.put(original)

        val read = store.task(TaskId("t1"))
        assertNotNull(read)
        assertEquals(original.body, read!!.body)
        assertEquals(original.tags, read.tags)
        assertEquals(original.anchors, read.anchors)
        assertEquals(original.dueDate, read.dueDate)
        assertEquals(original.bookmarked, read.bookmarked)
        assertEquals(original.order, read.order)
        assertEquals(original.extra, read.extra)
        assertEquals(original.links, read.links)
        assertEquals(original.attachments, read.attachments)
    }

    @Test
    fun `los contadores siguen a las escrituras`() = withStore { store, db ->
        store.put(
            task("a"),
            task("b", bookmarked = true),
            task("c", state = TasklaneConfig.DONE, completedAt = Instant.parse("2026-01-02T00:00:00Z")),
        )
        assertCountersMatch(db)

        // Editar una tarea que cambia de estado mueve su cuenta de un sitio al otro.
        store.put(task("a", state = TasklaneConfig.DOING))
        assertCountersMatch(db)

        store.apply(CONFIG, listOf(Mutation.Delete(listOf(TaskId("b")))))
        assertCountersMatch(db)
        assertNull(store.task(TaskId("b")))
    }

    @Test
    fun `reasignar un estado no materializa ni una tarea`() = withStore { store, db ->
        store.put(task("a"), task("b"), task("c", state = TasklaneConfig.DOING))
        val at = Instant.parse("2026-03-01T12:00:00Z")

        val applied = store.apply(
            CONFIG,
            listOf(Mutation.Reassign(TasklaneConfig.TODO, TasklaneConfig.DONE, at)),
        )

        assertEquals(2, applied.rows)
        assertCountersMatch(db)
        val moved = store.task(TaskId("a"))!!
        assertEquals(TasklaneConfig.DONE, moved.stateId)
        // Entrar en terminal sella la fecha, igual que `TaskReducer.applyState`.
        assertEquals(at, moved.completedAt)
        assertEquals(at, moved.updatedAt)
        assertEquals(TasklaneConfig.DOING, store.task(TaskId("c"))!!.stateId)
    }

    @Test
    fun `rellenar completedAt no toca updatedAt`() = withStore { store, db ->
        val updated = Instant.parse("2026-01-05T08:00:00Z")
        store.put(task("a", state = TasklaneConfig.DONE, updatedAt = updated))

        store.apply(CONFIG, listOf(Mutation.Backfill(setOf(TasklaneConfig.DONE))))

        val read = store.task(TaskId("a"))!!
        assertEquals(updated, read.completedAt)
        assertEquals(updated, read.updatedAt)
        assertCountersMatch(db)
    }

    @Test
    fun `un estado que desaparece aparca sus tareas y las devuelve al volver`() = withStore { store, db ->
        store.put(task("a", state = TasklaneConfig.DOING), task("b", state = TasklaneConfig.DOING))

        val without = CONFIG.copy(states = CONFIG.states.filterNot { it.id == TasklaneConfig.DOING }).normalized()
        val parked = store.apply(without, listOf(Mutation.Renormalize(CONFIG)))

        assertEquals(2, parked.parked.count)
        assertEquals(setOf(TaskId("a"), TaskId("b")), parked.parked.ids)
        val moved = store.task(TaskId("a"))!!
        assertEquals(without.defaultState.id, moved.stateId)
        assertEquals(TasklaneConfig.DOING.value, moved.extra[TaskReducer.ORIG_STATE])
        assertCountersMatch(db)

        // Y vuelve sola cuando el estado reaparece.
        store.apply(CONFIG, listOf(Mutation.Renormalize(without)))
        val back = store.task(TaskId("a"))!!
        assertEquals(TasklaneConfig.DOING, back.stateId)
        assertTrue(back.extra.isEmpty())
        assertCountersMatch(db)
    }

    @Test
    fun `cambiar el anclaje de un estado rehace su fecha de orden`() = withStore { store, db ->
        val created = Instant.parse("2026-01-01T00:00:00Z")
        val updated = Instant.parse("2026-06-01T00:00:00Z")
        store.put(task("a", createdAt = created, updatedAt = updated))
        assertEquals(updated.toEpochMilli(), sortDateOf(db, "a"))

        val byCreated = CONFIG.copy(
            states = CONFIG.states.map {
                if (it.id == TasklaneConfig.TODO) it.copy(anchor = com.tasklane.domain.model.DateAnchor.CREATED) else it
            },
        ).normalized()
        store.apply(byCreated, listOf(Mutation.Renormalize(CONFIG)))

        assertEquals(created.toEpochMilli(), sortDateOf(db, "a"))
    }

    @Test
    fun `olvidar un repositorio se lo lleva todo, indice de texto incluido`() = withStore { store, db ->
        store.put(
            task("a", body = "Zarandaja del repo que se va", tags = listOf("x"), anchors = listOf(CodeAnchor.of("A.kt", 1))),
            task("b", body = "Lo del otro repo", repo = com.tasklane.domain.model.RepoKey("otro")),
        )

        store.apply(CONFIG, listOf(Mutation.Forget(REPO)))

        assertEquals(0, db.writer.count("SELECT count(*) FROM task WHERE repo = 'root'"))
        assertEquals(0, db.writer.count("SELECT count(*) FROM tag"))
        assertEquals(0, db.writer.count("SELECT count(*) FROM anchor"))
        assertEquals(0, matches(db, "zarandaja"))
        assertEquals("lo del otro repositorio sigue indexado", 1, matches(db, "otro"))
        assertNotNull(store.task(TaskId("b")))
        assertCountersMatch(db)
    }

    @Test
    fun `el indice de texto sigue a las ediciones`() = withStore { store, db ->
        store.put(task("a", body = "Revision del login"))
        assertEquals(1, matches(db, "revision"))

        store.put(task("a", body = "Pulir la tarjeta"))
        assertEquals("el cuerpo viejo deja de encontrarse", 0, matches(db, "revision"))
        assertEquals(1, matches(db, "tarjeta"))
    }

    @Test
    fun `la migracion por lotes deja los mismos contadores que las escrituras sueltas`() = withStore { store, db ->
        val batch = (0 until 200).map {
            task(
                "t$it",
                state = if (it % 3 == 0) TasklaneConfig.DONE else TasklaneConfig.TODO,
                completedAt = if (it % 3 == 0) Instant.parse("2026-01-02T00:00:00Z") else null,
                bookmarked = it % 10 == 0,
                tags = if (it % 5 == 0) listOf("lote") else emptyList(),
            )
        }
        store.importBatch(batch, CONFIG)

        assertEquals(200, db.writer.count("SELECT count(*) FROM task"))
        assertEquals(40, db.writer.count("SELECT count(*) FROM tag"))
        assertCountersMatch(db)
        assertEquals(200, matches(db, "tarea"))
    }

    @Test
    fun `la migracion sobrevive a un id repetido dentro de la tanda`() = withStore { store, db ->
        store.importBatch(listOf(task("a", body = "Primera"), task("a", body = "Segunda")), CONFIG)

        assertEquals(1, db.writer.count("SELECT count(*) FROM task"))
        assertEquals("Primera", store.task(TaskId("a"))!!.body)
        assertCountersMatch(db)
    }

    @Test
    fun `nextOrder sube con lo que ya hay`() = withStore { store, _ ->
        assertEquals(TaskStore.ORDER_GAP, store.nextOrder(REPO))
        store.put(task("a", order = 5000))
        assertEquals(6000L, store.nextOrder(REPO))
    }

    @Test
    fun `una prioridad que desaparece aparca sus tareas`() = withStore { store, db ->
        store.put(task("a", priority = TasklaneConfig.HIGH))
        val without = CONFIG.copy(priorities = CONFIG.priorities.filterNot { it.id == TasklaneConfig.HIGH })
            .normalized()

        store.apply(without, listOf(Mutation.Renormalize(CONFIG)))

        val read = store.task(TaskId("a"))!!
        assertEquals(without.defaultPriority.id, read.priorityId)
        assertEquals(TasklaneConfig.HIGH.value, read.extra[TaskReducer.ORIG_PRIORITY])
        assertCountersMatch(db)
    }

    @Test
    fun `reordenar las prioridades rehace el rango sin tocar las tareas`() = withStore { store, db ->
        store.put(task("a", priority = TasklaneConfig.HIGH))
        assertEquals(2, rankOf(db, "a"))

        val flipped = CONFIG.copy(priorities = CONFIG.priorities.reversed()).normalized()
        store.apply(flipped, listOf(Mutation.Renormalize(CONFIG)))

        assertEquals(0, rankOf(db, "a"))
    }

    @Test
    fun `un estado nuevo en la configuracion no reescribe nada`() = withStore { store, db ->
        store.put(task("a"))
        val extra = CONFIG.copy(
            states = CONFIG.states + TaskState(StateId("s-extra"), "Extra", 3),
            priorities = CONFIG.priorities + TaskPriority(com.tasklane.domain.model.PriorityId("p-x"), "X", 3, 0, 0),
        ).normalized()

        val applied = store.apply(extra, listOf(Mutation.Renormalize(CONFIG)))

        assertEquals("ConfigChanged se emite en cada arranque: no puede escribir", 0, applied.rows)
        assertCountersMatch(db)
    }

    /**
     * El id que vale la pena recordar es el **primero**, no el fallback por el que pasó
     * después. Sin esto, dos configuraciones desincronizadas seguidas dejarían la tarea
     * apuntando a un estado que tampoco existe.
     */
    @Test
    fun `el primer origen es el que se recuerda tras dos remapeos`() = withStore { store, _ ->
        store.put(task("a", state = TasklaneConfig.DOING))

        val sinDoing = CONFIG.copy(states = CONFIG.states.filterNot { it.id == TasklaneConfig.DOING }).normalized()
        store.apply(sinDoing, listOf(Mutation.Renormalize(CONFIG)))
        // Y ahora desaparece también el estado por defecto, en el que acaba de aparcarse.
        val sinTodo = sinDoing.copy(states = sinDoing.states.filterNot { it.id == sinDoing.defaultState.id })
            .normalized()
        store.apply(sinTodo, listOf(Mutation.Renormalize(sinDoing)))

        assertEquals(
            "el que se recuerda es de donde venía de verdad",
            TasklaneConfig.DOING.value,
            store.task(TaskId("a"))!!.extra[TaskReducer.ORIG_STATE],
        )
    }

    /**
     * Moverla a mano es una decisión del usuario: deja de ser una huérfana aparcada y
     * por eso pierde la marca que la haría volver sola. La vuelta automática nunca puede
     * pisar una decisión.
     */
    @Test
    fun `mover a mano una tarea aparcada cancela la vuelta automatica`() = withStore { store, _ ->
        store.put(task("a", state = TasklaneConfig.DOING))
        val sinDoing = CONFIG.copy(states = CONFIG.states.filterNot { it.id == TasklaneConfig.DOING }).normalized()
        store.apply(sinDoing, listOf(Mutation.Renormalize(CONFIG)))

        // Reasignar es exactamente lo que hace el diálogo de borrar un estado.
        store.apply(
            sinDoing,
            listOf(Mutation.Reassign(sinDoing.defaultState.id, TasklaneConfig.DONE, Instant.parse("2026-03-01T00:00:00Z"))),
        )
        // Y el estado vuelve a la configuración.
        store.apply(CONFIG, listOf(Mutation.Renormalize(sinDoing)))

        val read = store.task(TaskId("a"))!!
        assertEquals("se queda donde el usuario la puso", TasklaneConfig.DONE, read.stateId)
        assertTrue(read.extra.isEmpty())
    }

    @Test
    fun `reasignar una prioridad no toca las demas`() = withStore { store, db ->
        store.put(task("a", priority = TasklaneConfig.HIGH), task("b", priority = TasklaneConfig.LOW))

        store.apply(
            CONFIG,
            listOf(Mutation.Reprioritize(TasklaneConfig.HIGH, TasklaneConfig.NORMAL, Instant.parse("2026-03-01T00:00:00Z"))),
        )

        assertEquals(TasklaneConfig.NORMAL, store.task(TaskId("a"))!!.priorityId)
        assertEquals(TasklaneConfig.LOW, store.task(TaskId("b"))!!.priorityId)
        assertCountersMatch(db)
    }

    /**
     * El informe de diagnóstico sale de siete agregados en vez de un recorrido del
     * corpus. Este caso es el que dice que cuentan lo mismo.
     */
    @Test
    fun `las cuentas del informe dicen lo que hay`() = withStore { store, _ ->
        val sha = "a".repeat(64)
        val tasks = listOf(
            task(
                "a",
                body = "Una\n![](tasklane:$sha)",
                tags = listOf("x", "y"),
                anchors = listOf(CodeAnchor.of("A.kt", 1)),
            ),
            task("b", body = "Dos\n![](tasklane:$sha)", tags = listOf("x")),
            task("c", body = "Tres", extra = mapOf(TaskReducer.ORIG_STATE to "s-ido")),
        )
        store.importBatch(tasks, CONFIG)

        val stats = store.statsOf(REPO)

        assertEquals(3, stats.tasks)
        assertEquals(tasks.sumOf { it.body.length.toLong() }, stats.bodyChars)
        assertEquals(1, stats.anchors)
        assertEquals(3, stats.tags)
        assertEquals("dos referencias al mismo blob", 2, stats.imageRefs)
        assertEquals("y un solo blob distinto", 1, stats.distinctImages)
        assertEquals(1, stats.orphans)
    }

    /** Los adjuntos que el recolector no puede tocar, sin recorrer el corpus. */
    @Test
    fun `las referencias a imagenes salen de su tabla`() = withStore { store, _ ->
        val sha = "b".repeat(64)
        store.put(task("a", body = "Con captura\n![](tasklane:$sha)"))
        assertEquals(setOf(sha), store.referencedBlobs(REPO))

        // Quitar la referencia del cuerpo la quita de la tabla: es lo que hace que el
        // recolector pueda volver a considerar el fichero.
        store.put(task("a", body = "Sin captura"))
        assertTrue(store.referencedBlobs(REPO).isEmpty())
    }

    /** Qué tareas cuelgan de un fichero: un salto de índice, no el modelo entero (§3.6). */
    @Test
    fun `las anclas se preguntan por ruta`() = withStore { store, _ ->
        store.put(
            task("a", anchors = listOf(CodeAnchor.of("src/Auth.kt", 41, 7, "fun login()"))),
            task("b", anchors = listOf(CodeAnchor.of("src/Main.kt", 1))),
            // Lo terminal no se marca: una tarea hecha ya no es una nota sobre el código.
            task(
                "c",
                state = TasklaneConfig.DONE,
                completedAt = Instant.parse("2026-01-02T00:00:00Z"),
                anchors = listOf(CodeAnchor.of("src/Auth.kt", 9)),
            ),
        )

        val found = store.anchorsIn("src/Auth.kt")

        assertEquals(1, found.size)
        assertEquals(TaskId("a"), found.single().task.id)
        assertEquals(41, found.single().anchor.line)
        assertEquals(7, found.single().anchor.column)
        assertTrue(store.anchorsIn("src/NoExiste.kt").isEmpty())
    }

    private fun sortDateOf(db: TaskDb, id: String): Long =
        db.writer.first("SELECT sort_date FROM task WHERE id = ?", id) { it.getLong(0) }!!

    private fun rankOf(db: TaskDb, id: String): Int =
        db.writer.count("SELECT priority_rank FROM task WHERE id = '$id'")

    private fun matches(db: TaskDb, term: String): Int =
        db.writer.count("SELECT count(*) FROM task_fts WHERE task_fts MATCH ?", term)
}
