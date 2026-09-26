package com.tasklane.domain

import com.tasklane.data.sqlite.TaskStore
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.AnchorMove
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
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

/**
 * Las invariantes del modelo, tarea a tarea.
 *
 * Desde la Fase 3 el reducer devuelve **mutaciones** en vez de un modelo entero, así que
 * los casos se escriben contra [Model] —el almacén de mentira de `Reducing.kt`— que las
 * aplica a una lista. Se prueba lo mismo que antes y se sigue pudiendo encadenar
 * `crear → editar → completar`.
 *
 * Lo que **no** está aquí y antes sí: reasignar un estado, rellenar `completedAt` y
 * aparcar lo que apunta a configuración que ya no existe. Esa semántica se mudó a SQL
 * con la fase, y se prueba contra SQLite de verdad en `TaskStoreTest`; mantener aquí una
 * segunda implementación en Kotlin sería garantizar que un día divergen.
 */
class TaskReducerTest {

    private val t0 = Instant.parse("2026-09-13T10:00:00Z")
    private val reducer = TaskReducer(Clock.fixed(t0, ZoneOffset.UTC))
    private val repo = RepoKey.ROOT
    private val empty = Model()

    private fun create(body: String, model: Model = empty) =
        reducer.step(model, TaskCommand.Create(repo, body))

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
        val task = reducer.step(
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

        val conFecha = reducer.step(creada, TaskCommand.SetDueDate(repo, id, vence))
        assertEquals(vence, conFecha.activeTasks.single().dueDate)

        val sinFecha = reducer.step(conFecha, TaskCommand.SetDueDate(repo, id, null))
        assertNull("null quita el vencimiento", sinFecha.activeTasks.single().dueDate)
    }

    @Test
    fun `marcar no cuenta como editar`() {
        // Si `updatedAt` cambiase, marcar una tarea vieja para no perderla de vista
        // la movería al grupo de hoy: justo lo contrario de lo que se pidió.
        val creada = create("Revisar contrato")
        val antes = creada.activeTasks.single()
        val marcada = reducer.step(creada, TaskCommand.ToggleBookmark(repo, antes.id)).activeTasks.single()

        assertTrue(marcada.bookmarked)
        assertEquals("marcar no toca la fecha de modificacion", antes.updatedAt, marcada.updatedAt)

        val desmarcada = reducer
            .step(reducer.step(creada, TaskCommand.ToggleBookmark(repo, antes.id)), TaskCommand.ToggleBookmark(repo, antes.id))
            .activeTasks.single()
        assertFalse(desmarcada.bookmarked)
    }

    @Test
    fun `las etiquetas se normalizan al fijarlas`() {
        val creada = create("Migrar el indice")
        val id = creada.activeTasks.single().id
        val conTags = reducer
            .step(creada, TaskCommand.SetTags(repo, id, listOf(" api ", "", "api", "docs")))
            .activeTasks.single()

        assertEquals(listOf("api", "docs"), conTags.tags)
    }

    /**
     * *Tags ▸ Add…* sobre una selección (P31): suma sin quitar las que ya lleva, y lo que ya
     * lleva —aunque sea con otras mayúsculas— no se repite.
     */
    @Test
    fun `sumar etiquetas no quita ni repite`() {
        val creada = reducer.step(empty, TaskCommand.Create(repo, "Migrar", tags = listOf("api", "docs")))
        val id = creada.activeTasks.single().id

        val despues = TaskReducer(Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC))
            .step(creada, TaskCommand.AddTags(repo, id, listOf("API", " release ", "", "Release", "ui")))
            .activeTasks.single()

        assertEquals(listOf("api", "docs", "release", "ui"), despues.tags)
        assertTrue("sumar etiquetas es editar la tarea", despues.updatedAt.isAfter(t0))
        assertSame("sumar lo que ya lleva no cambia nada", creada, reducer.step(creada, TaskCommand.AddTags(repo, id, listOf("API"))))
    }

    @Test
    fun `quitar una etiqueta no mira mayusculas`() {
        val creada = reducer.step(empty, TaskCommand.Create(repo, "Migrar", tags = listOf("API", "docs")))
        val id = creada.activeTasks.single().id

        val sin = reducer.step(creada, TaskCommand.RemoveTags(repo, id, setOf("api"))).activeTasks.single()

        assertEquals(listOf("docs"), sin.tags)
        assertSame("quitar la que no lleva no cambia nada", creada, reducer.step(creada, TaskCommand.RemoveTags(repo, id, setOf("ui"))))
    }

    @Test
    fun `crear desde el editor guarda el ancla`() {
        val anchor = CodeAnchor.of("src/Auth.kt", 41, text = "fun login() {")
        val task = reducer
            .step(empty, TaskCommand.Create(repo, "Arreglar el login", anchors = listOf(anchor, anchor)))
            .activeTasks.single()

        // Repetida una sola vez: anclar dos veces el mismo sitio es anclarlo una.
        assertEquals(listOf(anchor), task.anchors)
    }

    @Test
    fun `quitar un ancla cuenta como edicion`() {
        val anchor = CodeAnchor.of("src/Auth.kt", 41, text = "fun login() {")
        val creada = reducer.step(empty, TaskCommand.Create(repo, "Arreglar", anchors = listOf(anchor)))
        val id = creada.activeTasks.single().id
        val antes = creada.activeTasks.single().updatedAt

        val despues = TaskReducer(Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC))
            .step(creada, TaskCommand.SetAnchors(repo, id, emptyList()))
            .activeTasks.single()

        assertEquals(emptyList<CodeAnchor>(), despues.anchors)
        assertTrue("quitar un ancla tiene que tocar updatedAt", despues.updatedAt.isAfter(antes))
    }

    /** Fijar lo mismo que ya había no es una edición: no puede mover la tarea de grupo. */
    @Test
    fun `fijar las mismas anclas no toca nada`() {
        val anchor = CodeAnchor.of("src/Auth.kt", 41, text = "fun login() {")
        val creada = reducer.step(empty, TaskCommand.Create(repo, "Arreglar", anchors = listOf(anchor)))
        val id = creada.activeTasks.single().id

        assertSame(creada, reducer.step(creada, TaskCommand.SetAnchors(repo, id, listOf(anchor))))
    }

    /**
     * Renombrar el fichero no es editar la tarea (2.13.0): la ruta cambia y nada más. Ni
     * `updatedAt` —la tarea se iría al grupo de hoy por una refactorización que nadie hizo
     * sobre ella— ni la línea, la columna o el texto, que siguen siendo los del fichero.
     */
    @Test
    fun `renombrar el fichero mueve el ancla sin tocar la tarea`() {
        val auth = CodeAnchor.of("src/auth/Auth.kt", 41, 4, "fun login() {")
        val other = CodeAnchor.of("src/Main.kt", 3, text = "fun main() {")
        val creada = reducer.step(empty, TaskCommand.Create(repo, "Arreglar", anchors = listOf(auth, other)))
        val tarea = creada.activeTasks.single()

        val movida = TaskReducer(Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC))
            .step(creada, TaskCommand.RelinkAnchors(listOf(tarea.id), listOf(AnchorMove("src/auth", "src/security"))))
            .activeTasks.single()

        assertEquals(listOf(auth.copy(path = "src/security/Auth.kt"), other), movida.anchors)
        assertEquals("mover el fichero no es editar la tarea", tarea.updatedAt, movida.updatedAt)
        assertEquals(tarea.copy(anchors = movida.anchors), movida)
    }

    @Test
    fun `un movimiento que no toca sus anclas no escribe nada`() {
        val anchor = CodeAnchor.of("src/Auth.kt", 41, text = "fun login() {")
        val creada = reducer.step(empty, TaskCommand.Create(repo, "Arreglar", anchors = listOf(anchor)))
        val id = creada.activeTasks.single().id

        assertSame(creada, reducer.step(creada, TaskCommand.RelinkAnchors(listOf(id), listOf(AnchorMove("src/Authz.kt", "src/B.kt")))))
    }

    /** Dos anclas que acaban en el mismo sitio son una, como al crearlas. */
    @Test
    fun `dos anclas que caen en el mismo sitio se juntan`() {
        val old = CodeAnchor.of("src/Old.kt", 1, text = "x")
        val new = CodeAnchor.of("src/New.kt", 1, text = "x")
        val creada = reducer.step(empty, TaskCommand.Create(repo, "Arreglar", anchors = listOf(old, new)))
        val id = creada.activeTasks.single().id

        val movida = reducer.step(creada, TaskCommand.RelinkAnchors(listOf(id), listOf(AnchorMove("src/Old.kt", "src/New.kt"))))

        assertEquals(listOf(new), movida.activeTasks.single().anchors)
    }

    @Test
    fun `un cuerpo vacio no crea tarea`() {
        assertSame(empty, create("   \n  "))
    }

    @Test
    fun `completar sella completedAt`() {
        val created = create("Algo")
        val id = created.activeTasks.single().id
        val done = reducer.step(created, TaskCommand.ToggleComplete(repo, id)).activeTasks.single()

        assertTrue(created.config.stateOrDefault(done.stateId).terminal)
        assertEquals(t0, done.completedAt)
    }

    @Test
    fun `reabrir conserva la fecha de finalizacion original`() {
        val created = create("Algo")
        val id = created.activeTasks.single().id
        val done = reducer.step(created, TaskCommand.ToggleComplete(repo, id))
        val reopened = reducer.step(done, TaskCommand.ToggleComplete(repo, id)).activeTasks.single()

        assertEquals(TasklaneConfig.TODO, reopened.stateId)
        assertNotNull(
            "salir de Done no debe destruir cuando se completo: la agrupacion historica depende de ello",
            reopened.completedAt,
        )
    }

    @Test
    fun `borrar quita solo lo seleccionado`() {
        var s = create("uno")
        s = reducer.step(s, TaskCommand.Create(repo, "dos"))
        val victima = s.activeTasks.first { it.body == "uno" }.id

        val after = reducer.step(s, TaskCommand.Delete(repo, listOf(victima)))
        assertEquals(listOf("dos"), after.activeTasks.map { it.body })
    }

    @Test
    fun `los ordenes son dispersos para permitir insertar en medio`() {
        var s = create("uno")
        s = reducer.step(s, TaskCommand.Create(repo, "dos"))
        val orders = s.activeTasks.map { it.order }.sorted()
        assertEquals(listOf(1000L, 2000L), orders)
    }

    // ------------------------------------------- configuracion desincronizada

    /** La de fabrica menos «Doing»: simula que alguien lo borro en otra rama. */
    private val sinDoing = TasklaneConfig.DEFAULT.copy(
        states = TasklaneConfig.DEFAULT.states.filterNot { it.id == TasklaneConfig.DOING },
    )

    private fun conTareaEnDoing(): Model =
        reducer.step(empty, TaskCommand.Create(repo, "Algo", TasklaneConfig.DOING))

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
    private fun withRepos(keys: List<RepoKey>): Model =
        reducer.step(empty, TaskCommand.RepositoriesChanged(keys.map { ref(it, it.value) }))

    @Test
    fun `cada repositorio ve solo sus tareas`() {
        var s = withRepos(listOf(api, web))
        s = reducer.step(s, TaskCommand.Create(api, "de api"))
        s = reducer.step(s, TaskCommand.Create(web, "de web"))

        assertEquals(listOf("de api"), s.tasksOf(api).map { it.body })
        assertEquals(listOf("de web"), s.tasksOf(web).map { it.body })
        assertEquals("el activo es el primero del catalogo", listOf("de api"), s.activeTasks.map { it.body })
    }

    @Test
    fun `si el repositorio activo desaparece se cae al primero`() {
        var s = withRepos(listOf(api, web))
        s = reducer.step(s, TaskCommand.SelectRepo(web))
        assertEquals(web, s.activeRepo)

        val after = reducer.step(s, TaskCommand.RepositoriesChanged(listOf(ref(api, "api"))))
        assertEquals(api, after.activeRepo)
    }

    @Test
    fun `mientras el activo siga en el catalogo no se mueve`() {
        var s = withRepos(listOf(api, web))
        s = reducer.step(s, TaskCommand.SelectRepo(web))

        val after = reducer.step(s, TaskCommand.RepositoriesChanged(listOf(ref(web, "web"), ref(api, "api"))))
        assertEquals(web, after.activeRepo)
    }

    @Test
    fun `seleccionar un repositorio que aun no esta en el catalogo se respeta`() {
        // Al abrir el proyecto la seleccion guardada se restaura ANTES de que la
        // deteccion termine; validarla contra un catalogo vacio la perderia.
        val s = reducer.step(empty, TaskCommand.SelectRepo(web))
        assertEquals(web, s.activeRepo)
    }

    @Test
    fun `un catalogo identico no produce snapshot nuevo`() {
        val s = withRepos(listOf(api, web))
        assertSame(s, reducer.step(s, TaskCommand.RepositoriesChanged(s.repositories)))
    }

    @Test
    fun `un catalogo vacio no deja el activo en el aire`() {
        val s = withRepos(listOf(api))
        val after = reducer.step(s, TaskCommand.RepositoriesChanged(emptyList()))
        assertEquals(api, after.activeRepo)
    }

    @Test
    fun `borrar en un repositorio no toca al otro`() {
        var s = withRepos(listOf(api, web))
        s = reducer.step(s, TaskCommand.Create(api, "de api"))
        s = reducer.step(s, TaskCommand.Create(web, "de web"))
        val victima = s.tasksOf(api).single().id

        val after = reducer.step(s, TaskCommand.Delete(api, listOf(victima)))
        assertTrue(after.tasksOf(api).isEmpty())
        assertEquals(1, after.tasksOf(web).size)
    }

    @Test
    fun `olvidar un repositorio se lleva sus tareas y mueve el activo`() {
        var s = withRepos(listOf(api, web))
        s = reducer.step(s, TaskCommand.Create(api, "de api"))
        s = reducer.step(s, TaskCommand.Create(web, "de web"))

        val after = reducer.step(s, TaskCommand.ForgetRepo(api))

        assertTrue("las tareas se van con el repositorio", after.tasksOf(api).isEmpty())
        assertEquals(listOf(web), after.repositories.map { it.key })
        assertEquals("el activo tiene que seguir existiendo", web, after.activeRepo)
        assertEquals(listOf("de web"), after.tasksOf(web).map { it.body })
    }

    @Test
    fun `olvidar algo que no esta no cambia nada`() {
        val s = withRepos(listOf(api))
        assertSame(s, reducer.step(s, TaskCommand.ForgetRepo(web)))
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

        val edited = reducer.step(created, TaskCommand.UpdateBody(repo, id, "Revisar sin enlace"))
        assertTrue(edited.activeTasks.single().links.isEmpty())
    }

    @Test
    fun `cambiar de estado no toca los enlaces`() {
        val created = create("Ver https://ejemplo.com/a")
        val before = created.activeTasks.single()
        val after = reducer
            .step(created, TaskCommand.ChangeState(repo, before.id, TasklaneConfig.DONE))
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

        val edited = reducer.step(created, TaskCommand.UpdateBody(repo, id, "Error al entrar"))
        assertTrue("quitar la referencia quita el adjunto", edited.activeTasks.single().attachments.isEmpty())
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

    @Test
    fun `crear muchas es crear cada una, en orden y con ordenes seguidos`() {
        val repo = RepoKey.ROOT
        val empty = Model()
        val model = reducer.step(
            empty,
            TaskCommand.CreateMany(
                repo,
                listOf(
                    TaskCommand.Create(repo, "  Primera  ", tags = listOf("todo")),
                    TaskCommand.Create(repo, "   "),
                    TaskCommand.Create(repo, "Segunda"),
                ),
            ),
        )

        val tasks = model.tasksOf(repo).sortedBy { it.order }
        assertEquals(listOf("Primera", "Segunda"), tasks.map { it.body })
        assertEquals(listOf("todo"), tasks.first().tags)
        assertEquals(TaskStore.ORDER_GAP, tasks[1].order - tasks[0].order)
    }

    @Test
    fun `el hueco de crear muchas es el del almacen`() {
        assertEquals(TaskStore.ORDER_GAP, TaskReducer.CREATE_STEP)
    }
}
