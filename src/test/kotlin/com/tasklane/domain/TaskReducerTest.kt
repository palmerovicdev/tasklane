package com.tasklane.domain

import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TaskReducerTest {

    private val t0 = Instant.parse("2026-09-13T10:00:00Z")
    private val reducer = TaskReducer(Clock.fixed(t0, ZoneOffset.UTC))
    private val repo = RepoKey.ROOT
    private val empty = TasklaneSnapshot.EMPTY

    private fun create(body: String, snapshot: TasklaneSnapshot = empty) =
        reducer.reduce(snapshot, TaskCommand.Create(repo, body))

    @Test
    fun `crear usa estado y prioridad por defecto`() {
        val task = create("Arreglar login").activeTasks.single()
        assertEquals("Arreglar login", task.body)
        assertEquals(TasklaneConfig.TODO, task.stateId)
        assertEquals(TasklaneConfig.NORMAL, task.priorityId)
        assertEquals(t0, task.createdAt)
        assertNull("una tarea nueva no esta completada", task.completedAt)
    }

    @Test
    fun `crear con etiquetas y vencimiento los conserva`() {
        // Es el camino del diálogo: sin esto, poner etiquetas y fecha al dar de alta
        // se perdía y sólo se podían añadir volviendo a editar.
        val vence = Instant.parse("2026-09-20T21:59:59Z")
        val task = reducer.reduce(
            empty,
            TaskCommand.Create(repo, "Preparar demo", tags = listOf("demo", " demo ", "qa"), dueDate = vence),
        ).activeTasks.single()

        assertEquals(listOf("demo", "qa"), task.tags)
        assertEquals(vence, task.dueDate)
    }

    @Test
    fun `fijar y quitar la fecha de vencimiento`() {
        val vence = Instant.parse("2026-09-20T10:00:00Z")
        val creada = create("Entregar informe")
        val id = creada.activeTasks.single().id

        val conFecha = reducer.reduce(creada, TaskCommand.SetDueDate(repo, id, vence))
        assertEquals(vence, conFecha.activeTasks.single().dueDate)

        val sinFecha = reducer.reduce(conFecha, TaskCommand.SetDueDate(repo, id, null))
        assertNull("null quita el vencimiento", sinFecha.activeTasks.single().dueDate)
    }

    @Test
    fun `marcar no cuenta como editar`() {
        // Si `updatedAt` cambiase, marcar una tarea vieja para no perderla de vista
        // la movería al grupo de hoy: justo lo contrario de lo que se pidió.
        val creada = create("Revisar contrato")
        val antes = creada.activeTasks.single()
        val marcada = reducer.reduce(creada, TaskCommand.ToggleBookmark(repo, antes.id)).activeTasks.single()

        assertTrue(marcada.bookmarked)
        assertEquals("marcar no toca la fecha de modificacion", antes.updatedAt, marcada.updatedAt)

        val desmarcada = reducer
            .reduce(reducer.reduce(creada, TaskCommand.ToggleBookmark(repo, antes.id)), TaskCommand.ToggleBookmark(repo, antes.id))
            .activeTasks.single()
        assertFalse(desmarcada.bookmarked)
    }

    @Test
    fun `las etiquetas se normalizan al fijarlas`() {
        val creada = create("Migrar el indice")
        val id = creada.activeTasks.single().id
        val conTags = reducer
            .reduce(creada, TaskCommand.SetTags(repo, id, listOf(" api ", "", "api", "docs")))
            .activeTasks.single()

        assertEquals(listOf("api", "docs"), conTags.tags)
    }

    @Test
    fun `crear desde el editor guarda el ancla`() {
        val anchor = CodeAnchor.of("src/Auth.kt", 41, text = "fun login() {")
        val task = reducer
            .reduce(empty, TaskCommand.Create(repo, "Arreglar el login", anchors = listOf(anchor, anchor)))
            .activeTasks.single()

        // Repetida una sola vez: anclar dos veces el mismo sitio es anclarlo una.
        assertEquals(listOf(anchor), task.anchors)
    }

    @Test
    fun `quitar un ancla cuenta como edicion`() {
        val anchor = CodeAnchor.of("src/Auth.kt", 41, text = "fun login() {")
        val creada = reducer.reduce(empty, TaskCommand.Create(repo, "Arreglar", anchors = listOf(anchor)))
        val id = creada.activeTasks.single().id
        val antes = creada.activeTasks.single().updatedAt

        val despues = TaskReducer(Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC))
            .reduce(creada, TaskCommand.SetAnchors(repo, id, emptyList()))
            .activeTasks.single()

        assertEquals(emptyList<CodeAnchor>(), despues.anchors)
        assertTrue("quitar un ancla tiene que tocar updatedAt", despues.updatedAt.isAfter(antes))
    }

    /** Fijar lo mismo que ya había no es una edición: no puede mover la tarea de grupo. */
    @Test
    fun `fijar las mismas anclas no toca nada`() {
        val anchor = CodeAnchor.of("src/Auth.kt", 41, text = "fun login() {")
        val creada = reducer.reduce(empty, TaskCommand.Create(repo, "Arreglar", anchors = listOf(anchor)))
        val id = creada.activeTasks.single().id

        assertSame(creada, reducer.reduce(creada, TaskCommand.SetAnchors(repo, id, listOf(anchor))))
    }

    @Test
    fun `un cuerpo vacio no crea tarea`() {
        assertSame(empty, create("   \n  "))
    }

    @Test
    fun `completar sella completedAt`() {
        val created = create("Algo")
        val id = created.activeTasks.single().id
        val done = reducer.reduce(created, TaskCommand.ToggleComplete(repo, id)).activeTasks.single()

        assertTrue(created.config.stateOrDefault(done.stateId).terminal)
        assertEquals(t0, done.completedAt)
    }

    @Test
    fun `reabrir conserva la fecha de finalizacion original`() {
        val created = create("Algo")
        val id = created.activeTasks.single().id
        val done = reducer.reduce(created, TaskCommand.ToggleComplete(repo, id))
        val reopened = reducer.reduce(done, TaskCommand.ToggleComplete(repo, id)).activeTasks.single()

        assertEquals(TasklaneConfig.TODO, reopened.stateId)
        assertNotNull(
            "salir de Done no debe destruir cuando se completo: la agrupacion historica depende de ello",
            reopened.completedAt,
        )
    }

    @Test
    fun `un estado desconocido se remapea sin perder la tarea`() {
        val huerfana = Task(
            id = TaskId("x"),
            repo = repo,
            body = "Tarea de otra configuracion",
            stateId = StateId("s-inexistente"),
            priorityId = PriorityId("p-inexistente"),
            createdAt = t0,
            updatedAt = t0,
        )

        val loaded = reducer.reduce(empty, TaskCommand.Loaded(repo, listOf(huerfana)))
        val task = loaded.activeTasks.single()

        assertEquals("la tarea sobrevive", 1, loaded.activeTasks.size)
        assertEquals(TasklaneConfig.TODO, task.stateId)
        assertEquals(TasklaneConfig.NORMAL, task.priorityId)
        assertEquals("s-inexistente", task.extra[TaskReducer.ORIG_STATE])
        assertEquals("p-inexistente", task.extra[TaskReducer.ORIG_PRIORITY])
    }

    @Test
    fun `borrar quita solo lo seleccionado`() {
        var s = create("uno")
        s = reducer.reduce(s, TaskCommand.Create(repo, "dos"))
        val victima = s.activeTasks.first { it.body == "uno" }.id

        val after = reducer.reduce(s, TaskCommand.Delete(repo, listOf(victima)))
        assertEquals(listOf("dos"), after.activeTasks.map { it.body })
    }

    @Test
    fun `los ordenes son dispersos para permitir insertar en medio`() {
        var s = create("uno")
        s = reducer.reduce(s, TaskCommand.Create(repo, "dos"))
        val orders = s.activeTasks.map { it.order }.sorted()
        assertEquals(listOf(1000L, 2000L), orders)
    }

    // ------------------------------------------- configuracion desincronizada

    /** La de fabrica menos «Doing»: simula que alguien lo borro en otra rama. */
    private val sinDoing = TasklaneConfig.DEFAULT.copy(
        states = TasklaneConfig.DEFAULT.states.filterNot { it.id == TasklaneConfig.DOING },
    )

    private fun conTareaEnDoing(): TasklaneSnapshot =
        reducer.reduce(empty, TaskCommand.Create(repo, "Algo", TasklaneConfig.DOING))

    @Test
    fun `si el estado desaparece la tarea se aparca y se recuerda de donde venia`() {
        val after = reducer.reduce(conTareaEnDoing(), TaskCommand.ConfigChanged(sinDoing))
        val task = after.activeTasks.single()

        assertEquals(TasklaneConfig.TODO, task.stateId)
        assertEquals(TasklaneConfig.DOING.value, task.extra[TaskReducer.ORIG_STATE])
    }

    @Test
    fun `si el estado vuelve la tarea vuelve sola`() {
        val aparcada = reducer.reduce(conTareaEnDoing(), TaskCommand.ConfigChanged(sinDoing))
        val restaurada = reducer
            .reduce(aparcada, TaskCommand.ConfigChanged(TasklaneConfig.DEFAULT))
            .activeTasks.single()

        assertEquals(TasklaneConfig.DOING, restaurada.stateId)
        assertNull("la marca se limpia al restaurar", restaurada.extra[TaskReducer.ORIG_STATE])
    }

    @Test
    fun `mover a mano una tarea aparcada cancela la vuelta automatica`() {
        val aparcada = reducer.reduce(conTareaEnDoing(), TaskCommand.ConfigChanged(sinDoing))
        val id = aparcada.activeTasks.single().id

        // El usuario la coloca donde quiere; eso es una decision, no un fallback.
        val movida = reducer.reduce(aparcada, TaskCommand.ChangeState(repo, id, TasklaneConfig.DONE))
        assertNull(movida.activeTasks.single().extra[TaskReducer.ORIG_STATE])

        val vuelta = reducer.reduce(movida, TaskCommand.ConfigChanged(TasklaneConfig.DEFAULT))
        assertEquals(
            "recuperar la configuracion no debe pisar lo que el usuario eligio despues",
            TasklaneConfig.DONE,
            vuelta.activeTasks.single().stateId,
        )
    }

    @Test
    fun `el primer origen es el que se recuerda tras dos remapeos`() {
        val aparcada = reducer.reduce(conTareaEnDoing(), TaskCommand.ConfigChanged(sinDoing))
        // Ahora tambien desaparece ToDo, que es donde la habiamos aparcado.
        val soloDone = TasklaneConfig.DEFAULT.copy(
            states = TasklaneConfig.DEFAULT.states.filter { it.id == TasklaneConfig.DONE },
        )
        val otraVez = reducer.reduce(aparcada, TaskCommand.ConfigChanged(soloDone)).activeTasks.single()

        assertEquals(
            "el ID que vale la pena guardar es el original, no el fallback por el que paso",
            TasklaneConfig.DOING.value,
            otraVez.extra[TaskReducer.ORIG_STATE],
        )
    }

    // ------------------------------------------------------------ reasignacion

    @Test
    fun `reasignar mueve solo las del estado de origen`() {
        var s = create("en todo")
        s = reducer.reduce(s, TaskCommand.Create(repo, "en doing", TasklaneConfig.DOING))

        val after = reducer.reduce(s, TaskCommand.ReassignState(TasklaneConfig.DOING, TasklaneConfig.DONE))
        val byBody = after.activeTasks.associateBy { it.body }

        assertEquals(TasklaneConfig.TODO, byBody.getValue("en todo").stateId)
        assertEquals(TasklaneConfig.DONE, byBody.getValue("en doing").stateId)
        assertEquals(
            "reasignar a un estado terminal sella la fecha igual que completar a mano",
            t0,
            byBody.getValue("en doing").completedAt,
        )
        assertNull(byBody.getValue("en todo").completedAt)
    }

    @Test
    fun `reasignar prioridad no toca las demas`() {
        var s = reducer.reduce(empty, TaskCommand.Create(repo, "baja", priorityId = TasklaneConfig.LOW))
        s = reducer.reduce(s, TaskCommand.Create(repo, "alta", priorityId = TasklaneConfig.HIGH))

        val after = reducer.reduce(s, TaskCommand.ReassignPriority(TasklaneConfig.LOW, TasklaneConfig.HIGH))

        assertEquals(listOf(TasklaneConfig.HIGH, TasklaneConfig.HIGH), after.activeTasks.map { it.priorityId })
    }

    // ------------------------------------------------- estado recien terminal

    @Test
    fun `rellenar completedAt usa updatedAt y no lo pisa`() {
        val viejo = Instant.parse("2026-08-01T09:00:00Z")
        val abierta = Task(
            id = TaskId("abierta"),
            repo = repo,
            body = "sin cerrar",
            stateId = TasklaneConfig.DOING,
            priorityId = TasklaneConfig.NORMAL,
            createdAt = viejo,
            updatedAt = viejo,
        )
        val yaCerrada = abierta.copy(id = TaskId("cerrada"), completedAt = t0)

        val loaded = reducer.reduce(empty, TaskCommand.Loaded(repo, listOf(abierta, yaCerrada)))
        val after = reducer.reduce(loaded, TaskCommand.BackfillCompletedAt(setOf(TasklaneConfig.DOING)))
        val byId = after.activeTasks.associateBy { it.id }

        assertEquals(viejo, byId.getValue(TaskId("abierta")).completedAt)
        assertEquals(
            "updatedAt es de donde se deriva la fecha: pisarlo destruiria el dato usado",
            viejo,
            byId.getValue(TaskId("abierta")).updatedAt,
        )
        assertEquals("una tarea que ya tenia fecha no se toca", t0, byId.getValue(TaskId("cerrada")).completedAt)
    }

    @Test
    fun `rellenar no toca estados ajenos`() {
        val s = create("en todo")
        val after = reducer.reduce(s, TaskCommand.BackfillCompletedAt(setOf(TasklaneConfig.DOING)))
        assertSame(s, after)
    }

    // ---------------------------------------------------------- repositorios

    private val api = RepoKey("api-a3f91d0e")
    private val web = RepoKey("web-1b2c3d4e")

    private fun ref(key: RepoKey, name: String) = RepositoryRef(
        key = key,
        displayName = name,
        rootPath = "/proyecto/$name",
        kind = RepositoryRef.Kind.GIT,
        depth = if (key == RepoKey.ROOT) 0 else 1,
    )

    // Lista y no vararg: Kotlin no admite `vararg` de una value class.
    private fun withRepos(keys: List<RepoKey>): TasklaneSnapshot =
        reducer.reduce(empty, TaskCommand.RepositoriesChanged(keys.map { ref(it, it.value) }))

    @Test
    fun `cada repositorio ve solo sus tareas`() {
        var s = withRepos(listOf(api, web))
        s = reducer.reduce(s, TaskCommand.Create(api, "de api"))
        s = reducer.reduce(s, TaskCommand.Create(web, "de web"))

        assertEquals(listOf("de api"), s.tasksOf(api).map { it.body })
        assertEquals(listOf("de web"), s.tasksOf(web).map { it.body })
        assertEquals("el activo es el primero del catalogo", listOf("de api"), s.activeTasks.map { it.body })
    }

    @Test
    fun `los repositorios sin leer quedan marcados como pendientes`() {
        val s = withRepos(listOf(api, web))
        assertEquals(setOf(api, web), s.loading)

        val cargado = reducer.reduce(s, TaskCommand.Loaded(api, emptyList()))
        assertEquals("leer uno no marca al otro", setOf(web), cargado.loading)
    }

    @Test
    fun `un catalogo nuevo no vuelve a marcar como pendiente lo ya leido`() {
        var s = withRepos(listOf(api))
        s = reducer.reduce(s, TaskCommand.Loaded(api, emptyList()))
        s = reducer.reduce(s, TaskCommand.RepositoriesChanged(listOf(ref(api, "api"), ref(web, "web"))))

        assertEquals(setOf(web), s.loading)
    }

    @Test
    fun `si el repositorio activo desaparece se cae al primero`() {
        var s = withRepos(listOf(api, web))
        s = reducer.reduce(s, TaskCommand.SelectRepo(web))
        assertEquals(web, s.activeRepo)

        val after = reducer.reduce(s, TaskCommand.RepositoriesChanged(listOf(ref(api, "api"))))
        assertEquals(api, after.activeRepo)
    }

    @Test
    fun `mientras el activo siga en el catalogo no se mueve`() {
        var s = withRepos(listOf(api, web))
        s = reducer.reduce(s, TaskCommand.SelectRepo(web))

        val after = reducer.reduce(s, TaskCommand.RepositoriesChanged(listOf(ref(web, "web"), ref(api, "api"))))
        assertEquals(web, after.activeRepo)
    }

    @Test
    fun `seleccionar un repositorio que aun no esta en el catalogo se respeta`() {
        // Al abrir el proyecto la seleccion guardada se restaura ANTES de que la
        // deteccion termine; validarla contra un catalogo vacio la perderia.
        val s = reducer.reduce(empty, TaskCommand.SelectRepo(web))
        assertEquals(web, s.activeRepo)
    }

    @Test
    fun `un catalogo identico no produce snapshot nuevo`() {
        val s = withRepos(listOf(api, web))
        assertSame(s, reducer.reduce(s, TaskCommand.RepositoriesChanged(s.repositories)))
    }

    @Test
    fun `un catalogo vacio no deja el activo en el aire`() {
        val s = withRepos(listOf(api))
        val after = reducer.reduce(s, TaskCommand.RepositoriesChanged(emptyList()))
        assertEquals(api, after.activeRepo)
    }

    @Test
    fun `borrar en un repositorio no toca al otro`() {
        var s = withRepos(listOf(api, web))
        s = reducer.reduce(s, TaskCommand.Create(api, "de api"))
        s = reducer.reduce(s, TaskCommand.Create(web, "de web"))
        val victima = s.tasksOf(api).single().id

        val after = reducer.reduce(s, TaskCommand.Delete(api, listOf(victima)))
        assertTrue(after.tasksOf(api).isEmpty())
        assertEquals(1, after.tasksOf(web).size)
    }

    @Test
    fun `reasignar un estado alcanza a todos los repositorios`() {
        var s = withRepos(listOf(api, web))
        s = reducer.reduce(s, TaskCommand.Create(api, "de api", TasklaneConfig.DOING))
        s = reducer.reduce(s, TaskCommand.Create(web, "de web", TasklaneConfig.DOING))

        val after = reducer.reduce(s, TaskCommand.ReassignState(TasklaneConfig.DOING, TasklaneConfig.TODO))

        assertEquals(TasklaneConfig.TODO, after.tasksOf(api).single().stateId)
        assertEquals(TasklaneConfig.TODO, after.tasksOf(web).single().stateId)
    }

    @Test
    fun `olvidar un repositorio se lleva sus tareas y mueve el activo`() {
        var s = withRepos(listOf(api, web))
        s = reducer.reduce(s, TaskCommand.Create(api, "de api"))
        s = reducer.reduce(s, TaskCommand.Create(web, "de web"))

        val after = reducer.reduce(s, TaskCommand.ForgetRepo(api))

        assertTrue("la clave desaparece, no se queda con lista vacia", api !in after.tasksByRepo)
        assertEquals(listOf(web), after.repositories.map { it.key })
        assertEquals("el activo tiene que seguir existiendo", web, after.activeRepo)
        assertEquals(listOf("de web"), after.tasksOf(web).map { it.body })
    }

    @Test
    fun `olvidar algo que no esta no cambia nada`() {
        val s = withRepos(listOf(api))
        assertSame(s, reducer.reduce(s, TaskCommand.ForgetRepo(web)))
    }

    // ------------------------------------------------------------- enlaces

    @Test
    fun `crear extrae los enlaces del cuerpo`() {
        val task = create("Revisar https://ejemplo.com/a").activeTasks.single()
        assertEquals(listOf("https://ejemplo.com/a"), task.links.map { it.url })
    }

    @Test
    fun `editar el cuerpo recalcula los enlaces`() {
        val created = create("Revisar https://ejemplo.com/a")
        val id = created.activeTasks.single().id

        val edited = reducer.reduce(created, TaskCommand.UpdateBody(repo, id, "Revisar sin enlace"))
        assertTrue(edited.activeTasks.single().links.isEmpty())
    }

    @Test
    fun `cargar de disco deriva los enlaces`() {
        // El codec NO persiste `links`: son derivados, y lo que se guarda es el
        // cuerpo. Si la carga no los recalculara, `has:link` no encontraria nada
        // hasta volver a editar la tarea.
        val stored = Task(
            id = TaskId("t1"),
            repo = repo,
            body = "Ver https://ejemplo.com/a",
            stateId = TasklaneConfig.TODO,
            priorityId = TasklaneConfig.NORMAL,
            createdAt = t0,
            updatedAt = t0,
        )
        val loaded = reducer.reduce(empty, TaskCommand.Loaded(repo, listOf(stored)))
        assertEquals(listOf("https://ejemplo.com/a"), loaded.activeTasks.single().links.map { it.url })
    }

    @Test
    fun `cambiar de estado no toca los enlaces`() {
        val created = create("Ver https://ejemplo.com/a")
        val before = created.activeTasks.single()
        val after = reducer
            .reduce(created, TaskCommand.ChangeState(repo, before.id, TasklaneConfig.DONE))
            .activeTasks.single()
        assertEquals(before.links, after.links)
    }

    // ------------------------------------------------------------- imagenes

    private val sha = "a".repeat(64)

    @Test
    fun `crear deriva las imagenes del cuerpo`() {
        val task = create("Error al entrar\n![](tasklane:$sha)").activeTasks.single()
        assertEquals(listOf(AttachmentId(sha)), task.attachments.map { it.id })
    }

    @Test
    fun `editar el cuerpo recalcula las imagenes`() {
        val created = create("Error al entrar\n![](tasklane:$sha)")
        val id = created.activeTasks.single().id

        val edited = reducer.reduce(created, TaskCommand.UpdateBody(repo, id, "Error al entrar"))
        assertTrue("quitar la referencia quita el adjunto", edited.activeTasks.single().attachments.isEmpty())
    }

    /**
     * El códec no persiste los adjuntos: son derivados del cuerpo, igual que los
     * enlaces. Sin recalcularlos al cargar, `has:image` no encontraría nada hasta
     * volver a editar la tarea, y el recolector creería que no hay nada referenciado
     * — que es como se borra una imagen que sí se estaba usando.
     */
    @Test
    fun `cargar de disco deriva las imagenes`() {
        val stored = Task(
            id = TaskId("t1"),
            repo = repo,
            body = "Con captura\n![](tasklane:$sha)",
            stateId = TasklaneConfig.TODO,
            priorityId = TasklaneConfig.NORMAL,
            createdAt = t0,
            updatedAt = t0,
        )
        val loaded = reducer.reduce(empty, TaskCommand.Loaded(repo, listOf(stored)))
        assertEquals(listOf(AttachmentId(sha)), loaded.activeTasks.single().attachments.map { it.id })
    }

    @Test
    fun `una linea que solo es una imagen no se convierte en titulo`() {
        val task = create("![](tasklane:$sha)\nArreglar el login").activeTasks.single()
        assertEquals("Arreglar el login", task.title)
    }

    @Test
    fun `una tarea que es solo una imagen sigue teniendo cuerpo`() {
        val task = create("![](tasklane:$sha)").activeTasks.single()
        assertEquals("![](tasklane:$sha)", task.title)
        assertFalse("la imagen no es detalle: ya la anuncia el indicador", task.hasDetail)
    }

    @Test
    fun `una imagen bajo el titulo no cuenta como detalle`() {
        val task = create("Arreglar el login\n![](tasklane:$sha)").activeTasks.single()
        assertFalse(task.hasDetail)
    }

    @Test
    fun `el texto bajo la imagen si cuenta como detalle`() {
        val task = create("Arreglar el login\n![](tasklane:$sha)\nPasa con Safari").activeTasks.single()
        assertTrue(task.hasDetail)
    }
}
