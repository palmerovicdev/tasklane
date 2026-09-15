package com.tasklane.domain

import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * El **recibo** del reducer: qué repositorios hay que reescribir y qué tareas quedaron
 * aparcadas.
 *
 * Es el contrato que la Fase 1 introdujo para que `TaskService` dejara de deducirlo
 * recorriendo el modelo entero detrás de cada comando (`docs/plan-escala.md` §1.5). Lo
 * que estos tests fijan es justo lo que antes no se podía equivocar porque se
 * recalculaba, y ahora sí: si el reducer se olvida de marcar un repositorio, **los
 * cambios del usuario no llegan al disco**. Es el peor fallo posible de este plugin y
 * no tiene síntoma visible hasta que se cierra el proyecto.
 */
class ReductionTest {

    private val t0 = Instant.parse("2026-09-15T10:00:00Z")
    private val reducer = TaskReducer(Clock.fixed(t0, ZoneOffset.UTC))
    private val repo = RepoKey.ROOT
    private val other = RepoKey("web-1234abcd")
    private val config = TasklaneConfig.DEFAULT

    private fun task(
        id: String,
        repo: RepoKey = this.repo,
        stateId: StateId = TasklaneConfig.TODO,
        priorityId: PriorityId = TasklaneConfig.NORMAL,
        order: Long = 1000L,
        extra: Map<String, String> = emptyMap(),
    ) = Task(
        id = TaskId(id),
        repo = repo,
        body = "Tarea $id",
        stateId = stateId,
        priorityId = priorityId,
        createdAt = t0,
        updatedAt = t0,
        order = order,
        extra = extra,
    )

    private fun snapshot(vararg tasks: Task) = TasklaneSnapshot(
        config = config,
        tasksByRepo = tasks.groupBy { it.repo },
        activeRepo = repo,
        repositories = listOf(
            RepositoryRef(repo, "root", "", RepositoryRef.Kind.PROJECT_ROOT),
            RepositoryRef(other, "web", "web", RepositoryRef.Kind.GIT, depth = 1),
        ),
        maxOrder = tasks.groupBy { it.repo }.mapValues { (_, list) -> list.maxOf { it.order } },
    )

    // --------------------------------------------------------------- qué ensucia

    @Test
    fun `un comando acotado ensucia solo su repositorio`() {
        val before = snapshot(task("a"), task("b", repo = other))
        val result = reducer.plan(before, TaskCommand.ToggleBookmark(repo, TaskId("a")))

        assertEquals(setOf(repo), result.dirty)
    }

    /**
     * Leer de disco **no** ensucia. Si lo hiciera, abrir un proyecto reescribiría todos
     * sus ficheros de tareas sin que nadie hubiera tocado nada — y con ellos sus fechas
     * de modificación y su diff.
     */
    @Test
    fun `cargar no ensucia nada`() {
        val result = reducer.plan(TasklaneSnapshot.EMPTY, TaskCommand.Loaded(repo, listOf(task("a"))))

        assertTrue(result.dirty.isEmpty())
        assertEquals(1, result.snapshot.tasksOf(repo).size)
    }

    /** Quitar un repositorio tampoco: se va, no se reescribe. */
    @Test
    fun `olvidar un repositorio no ensucia nada`() {
        val before = snapshot(task("a"), task("b", repo = other))
        assertTrue(reducer.plan(before, TaskCommand.ForgetRepo(other)).dirty.isEmpty())
    }

    /**
     * Un comando de alcance de proyecto ensucia **cada** repositorio que tocó, y sólo
     * ésos. Es el caso que impide leer el repositorio afectado del comando, que es
     * por lo que `markDirty` existía.
     */
    @Test
    fun `reasignar un estado ensucia solo los repositorios que lo tenian`() {
        val before = snapshot(
            task("a", stateId = TasklaneConfig.DOING),
            task("b", repo = other),
        )
        val result = reducer.plan(
            before,
            TaskCommand.ReassignState(TasklaneConfig.DOING, TasklaneConfig.DONE),
        )

        assertEquals("el otro repositorio no tiene ninguna en Doing", setOf(repo), result.dirty)
    }

    @Test
    fun `reasignar ensucia los dos cuando los dos tenian`() {
        val before = snapshot(
            task("a", stateId = TasklaneConfig.DOING),
            task("b", repo = other, stateId = TasklaneConfig.DOING),
        )
        val result = reducer.plan(
            before,
            TaskCommand.ReassignState(TasklaneConfig.DOING, TasklaneConfig.DONE),
        )

        assertEquals(setOf(repo, other), result.dirty)
    }

    // ------------------------------------------------------- qué NO cambia nada

    /**
     * **El contrato del que ahora depende `TaskService`.** Desde la Fase 1 el servicio
     * decide por identidad —`result.snapshot === before`— si hay algo que hacer, así
     * que un comando que no cambia nada tiene que devolver la MISMA instancia y no una
     * copia equivalente. Una copia pasaría el filtro y dispararía un repintado y un
     * volcado por cada evento de VCS repetido.
     */
    @Test
    fun `un comando sin efecto devuelve el mismo snapshot`() {
        // Con tarea en los dos repositorios, para que `loading` esté vacío y
        // `RepositoriesChanged` no tenga tampoco eso que cambiar.
        val before = snapshot(task("a"), task("b", repo = other))

        val casos = listOf(
            TaskCommand.ConfigChanged(config),
            TaskCommand.SelectRepo(repo),
            TaskCommand.ReassignState(TasklaneConfig.DOING, TasklaneConfig.DOING),
            TaskCommand.ReassignPriority(TasklaneConfig.LOW, TasklaneConfig.LOW),
            TaskCommand.RepositoriesChanged(before.repositories),
            TaskCommand.Create(repo, "   "),
            TaskCommand.Delete(repo, listOf(TaskId("no-existe"))),
            TaskCommand.UpdateBody(repo, TaskId("no-existe"), "otra cosa"),
            TaskCommand.ChangeState(repo, TaskId("a"), TasklaneConfig.TODO),
            TaskCommand.SetTags(repo, TaskId("a"), emptyList()),
            TaskCommand.BackfillCompletedAt(setOf(TasklaneConfig.DONE)),
        )

        for (command in casos) {
            val result = reducer.plan(before, command)
            assertSame("$command deberia devolver el mismo snapshot", before, result.snapshot)
            assertTrue("$command no deberia ensuciar nada", result.dirty.isEmpty())
        }
    }

    // ------------------------------------------------------------- qué se aparca

    private val huerfana = "s-que-ya-no-existe"

    @Test
    fun `cargar una tarea con un estado inexistente la anuncia`() {
        val result = reducer.plan(
            TasklaneSnapshot.EMPTY,
            TaskCommand.Loaded(repo, listOf(task("a", stateId = StateId(huerfana)))),
        )

        assertEquals(listOf(TaskId("a")), result.remapped.map { it.id })
        assertEquals("y queda en el estado por defecto", TasklaneConfig.TODO, result.snapshot.tasksOf(repo).single().stateId)
    }

    /**
     * **Sólo las nuevas.** Una tarea que ya venía aparcada no se vuelve a anunciar en
     * cada comando; antes eso se conseguía comparando el conjunto de huérfanas de
     * antes con el de después, que es justamente lo que costaba dos `flatten()` del
     * snapshot entero por pulsación.
     */
    @Test
    fun `una tarea que ya venia aparcada no se vuelve a anunciar`() {
        val aparcada = task("a", extra = mapOf(TaskReducer.ORIG_STATE to huerfana))
        val before = snapshot(aparcada)

        val result = reducer.plan(before, TaskCommand.ToggleBookmark(repo, TaskId("a")))
        assertTrue("ya estaba aparcada: no hay noticia", result.remapped.isEmpty())

        // Y tampoco al volver a pasar por la configuración, que es lo que ocurre en
        // cada arranque y en cada paso por los ajustes.
        assertTrue(reducer.plan(before, TaskCommand.ConfigChanged(config.copy(repoDepth = 2))).remapped.isEmpty())
    }

    @Test
    fun `un comando normal no anuncia nada`() {
        val before = snapshot(task("a"))
        assertTrue(reducer.plan(before, TaskCommand.UpdateBody(repo, TaskId("a"), "otro")).remapped.isEmpty())
    }

    /** Volver a poner el estado que faltaba devuelve la tarea a su sitio, sin anunciarla. */
    @Test
    fun `si el estado vuelve la tarea vuelve y no se anuncia`() {
        val volvio = config.copy(
            states = config.states + com.tasklane.domain.model.TaskState(StateId(huerfana), "Vuelto", 3),
        )
        val before = snapshot(task("a", extra = mapOf(TaskReducer.ORIG_STATE to huerfana)))

        val result = reducer.plan(before, TaskCommand.ConfigChanged(volvio))

        assertEquals(StateId(huerfana), result.snapshot.tasksOf(repo).single().stateId)
        assertTrue(result.remapped.isEmpty())
        assertEquals("y hay que reescribirlo, porque la tarea cambió", setOf(repo), result.dirty)
    }

    // ------------------------------------------------------------ el orden, O(1)

    /**
     * `nextOrder` sale de `maxOrder`, que el reducer mantiene. Lo que hay que fijar es
     * que el atajo **dice lo mismo** que el recorrido que sustituye.
     */
    @Test
    fun `el techo del orden se calcula al cargar`() {
        val loaded = reducer.reduce(
            TasklaneSnapshot.EMPTY,
            TaskCommand.Loaded(repo, listOf(task("a", order = 1000), task("b", order = 7000))),
        )

        assertEquals(7000L, loaded.maxOrder[repo])
        assertEquals(8000L, loaded.nextOrder(repo))
    }

    @Test
    fun `crear sube el techo, y la siguiente cae detras`() {
        var snapshot = reducer.reduce(
            TasklaneSnapshot.EMPTY,
            TaskCommand.Loaded(repo, listOf(task("a", order = 5000))),
        )
        snapshot = reducer.reduce(snapshot, TaskCommand.Create(repo, "primera"))
        snapshot = reducer.reduce(snapshot, TaskCommand.Create(repo, "segunda"))

        val orders = snapshot.tasksOf(repo).map { it.order }
        assertEquals(listOf(5000L, 6000L, 7000L), orders)
        assertEquals(8000L, snapshot.nextOrder(repo))
    }

    /**
     * Borrar **no** baja el techo, y es correcto: los huecos del orden disperso son
     * justamente lo que permite insertar entre dos vecinos sin renumerar la lista. Lo
     * que no puede pasar es que la siguiente tarea reutilice un orden ya usado.
     */
    @Test
    fun `borrar no baja el techo`() {
        var snapshot = reducer.reduce(
            TasklaneSnapshot.EMPTY,
            TaskCommand.Loaded(repo, listOf(task("a", order = 1000), task("b", order = 9000))),
        )
        snapshot = reducer.reduce(snapshot, TaskCommand.Delete(repo, listOf(TaskId("b"))))

        assertEquals(10_000L, snapshot.nextOrder(repo))
    }

    @Test
    fun `cada repositorio lleva su propio techo`() {
        var snapshot = reducer.reduce(
            TasklaneSnapshot.EMPTY,
            TaskCommand.Loaded(repo, listOf(task("a", order = 3000))),
        )
        snapshot = reducer.reduce(
            snapshot,
            TaskCommand.Loaded(other, listOf(task("b", repo = other, order = 40_000))),
        )

        assertEquals(4000L, snapshot.nextOrder(repo))
        assertEquals(41_000L, snapshot.nextOrder(other))
    }

    @Test
    fun `olvidar un repositorio se lleva su techo`() {
        val before = snapshot(task("a"), task("b", repo = other, order = 50_000))
        val after = reducer.reduce(before, TaskCommand.ForgetRepo(other))

        assertNull(after.maxOrder[other])
        assertEquals(TasklaneSnapshot.ORDER_GAP, after.nextOrder(other))
    }

    // ----------------------------------------------------- guardar el diálogo

    /**
     * **`UpdateTask` tiene que hacer exactamente lo mismo que los seis comandos que
     * sustituye.** Es el test que importa de la Fase 1: la optimización sólo vale si
     * el resultado es idéntico, y «idéntico» aquí incluye `updatedAt`, `completedAt` y
     * la marca de huérfana.
     */
    @Test
    fun `UpdateTask hace lo mismo que los seis comandos sueltos`() {
        val vence = Instant.parse("2026-10-01T21:59:59Z")
        val ancla = com.tasklane.domain.model.CodeAnchor.of("src/Auth.kt", 41, 8, "fun login()")
        val before = snapshot(task("a"))

        val deUnGolpe = reducer.reduce(
            before,
            TaskCommand.UpdateTask(
                repo = repo,
                id = TaskId("a"),
                body = "Cuerpo nuevo",
                stateId = TasklaneConfig.DONE,
                priorityId = TasklaneConfig.HIGH,
                tags = listOf("api", " api ", "ui"),
                dueDate = java.util.Optional.of(vence),
                anchors = listOf(ancla, ancla),
            ),
        ).tasksOf(repo).single()

        var uno = before
        for (command in listOf(
            TaskCommand.UpdateBody(repo, TaskId("a"), "Cuerpo nuevo"),
            TaskCommand.ChangeState(repo, TaskId("a"), TasklaneConfig.DONE),
            TaskCommand.ChangePriority(repo, TaskId("a"), TasklaneConfig.HIGH),
            TaskCommand.SetTags(repo, TaskId("a"), listOf("api", " api ", "ui")),
            TaskCommand.SetDueDate(repo, TaskId("a"), vence),
            TaskCommand.SetAnchors(repo, TaskId("a"), listOf(ancla, ancla)),
        )) {
            uno = reducer.reduce(uno, command)
        }
        val deSeisGolpes = uno.tasksOf(repo).single()

        assertEquals(deSeisGolpes, deUnGolpe)
        // Y de paso, que lo que se comparó no era una tarea sin tocar.
        assertEquals("Cuerpo nuevo", deUnGolpe.body)
        assertEquals(TasklaneConfig.DONE, deUnGolpe.stateId)
        assertEquals(TasklaneConfig.HIGH, deUnGolpe.priorityId)
        assertEquals(listOf("api", "ui"), deUnGolpe.tags)
        assertEquals(vence, deUnGolpe.dueDate)
        assertEquals(listOf(ancla), deUnGolpe.anchors)
        assertEquals("entrar en terminal sella la fecha", t0, deUnGolpe.completedAt)
    }

    /** Un `null` es «no toques este campo». Sin eso, guardar borraría lo que no se editó. */
    @Test
    fun `UpdateTask no toca lo que no se le pasa`() {
        val vence = Instant.parse("2026-10-01T21:59:59Z")
        val original = task("a").copy(tags = listOf("api"), dueDate = vence, bookmarked = true)
        val before = snapshot(original)

        val after = reducer.reduce(
            before,
            TaskCommand.UpdateTask(repo, TaskId("a"), body = "Otro cuerpo"),
        ).tasksOf(repo).single()

        assertEquals(listOf("api"), after.tags)
        assertEquals(vence, after.dueDate)
        assertTrue("la marca no es cosa del diálogo", after.bookmarked)
        assertEquals(TasklaneConfig.TODO, after.stateId)
    }

    /**
     * Y `Optional.empty` es «quítasela», que no es lo mismo. Sin esta distinción no
     * habría forma de borrar una fecha de vencimiento desde el diálogo.
     */
    @Test
    fun `UpdateTask distingue entre no tocar la fecha y quitarla`() {
        val vence = Instant.parse("2026-10-01T21:59:59Z")
        val before = snapshot(task("a").copy(dueDate = vence))

        val sinTocar = reducer.reduce(before, TaskCommand.UpdateTask(repo, TaskId("a"), body = "x"))
        assertEquals(vence, sinTocar.tasksOf(repo).single().dueDate)

        val quitada = reducer.reduce(
            before,
            TaskCommand.UpdateTask(repo, TaskId("a"), dueDate = java.util.Optional.empty()),
        )
        assertNull(quitada.tasksOf(repo).single().dueDate)
    }

    /**
     * Aceptar el diálogo sin cambiar nada no puede mover `updatedAt` ni ensuciar el
     * repositorio. Con seis comandos sueltos cada uno decidía por su cuenta y la fecha
     * se tocaba igual; aquí se decide una sola vez y para toda la tarea.
     */
    @Test
    fun `aceptar el dialogo sin cambios no toca nada`() {
        val original = task("a").copy(tags = listOf("api"))
        val before = snapshot(original)

        val result = reducer.plan(
            before,
            TaskCommand.UpdateTask(
                repo = repo,
                id = TaskId("a"),
                body = original.body,
                stateId = original.stateId,
                priorityId = original.priorityId,
                tags = original.tags,
                dueDate = java.util.Optional.empty(),
                anchors = original.anchors,
            ),
        )

        assertSame(before, result.snapshot)
        assertTrue(result.dirty.isEmpty())
    }

    /** Un id que no existe no puede inventarse una tarea ni ensuciar el repositorio. */
    @Test
    fun `UpdateTask sobre un id inexistente no hace nada`() {
        val before = snapshot(task("a"))
        val result = reducer.plan(before, TaskCommand.UpdateTask(repo, TaskId("no-existe"), body = "x"))

        assertSame(before, result.snapshot)
        assertTrue(result.dirty.isEmpty())
    }

    // -------------------------------------------------------------- buscar por id

    /** El índice por id tiene que ver **todos** los repositorios, no sólo el activo. */
    @Test
    fun `task busca en todos los repositorios`() {
        val before = snapshot(task("a"), task("b", repo = other))

        assertEquals(TaskId("a"), before.task(TaskId("a"))?.id)
        assertEquals("y en el que no está abierto", other, before.task(TaskId("b"))?.repo)
        assertNull(before.task(TaskId("no-existe")))
    }

    @Test
    fun `el indice por id sigue al snapshot`() {
        val before = snapshot(task("a"))
        val after = reducer.reduce(before, TaskCommand.Delete(repo, listOf(TaskId("a"))))

        assertEquals(TaskId("a"), before.task(TaskId("a"))?.id)
        assertNull("el snapshot nuevo ya no la tiene", after.task(TaskId("a")))
    }
}
