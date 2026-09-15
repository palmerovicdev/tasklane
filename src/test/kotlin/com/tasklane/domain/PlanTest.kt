package com.tasklane.domain

import com.tasklane.domain.command.Mutation
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/**
 * El **plan** del reducer: qué mutaciones produce un comando, y cuáles no produce
 * ninguna.
 *
 * Sustituye al recibo de la Fase 1 (`Reduction.dirty`) y fija lo mismo que fijaba aquél,
 * un escalón más abajo. Lo que estos casos protegen es el peor fallo posible de este
 * plugin y el que no tiene síntoma visible hasta que se cierra el proyecto: **que un
 * cambio del usuario no llegue al disco**, o —al revés— que un comando que no cambió
 * nada escriba igual y dispare un repintado por cada evento de VCS repetido.
 *
 * La otra mitad del contrato está en `TaskStoreTest`: aquí se comprueba **qué se pide**,
 * allí **qué hace el almacén con ello**.
 */
class PlanTest {

    private val t0 = Instant.parse("2026-09-15T10:00:00Z")
    private val reducer = TaskReducer(Clock.fixed(t0, ZoneOffset.UTC))
    private val repo = RepoKey.ROOT
    private val other = RepoKey("web-1234abcd")
    private val config = TasklaneConfig.DEFAULT.normalized()

    private fun task(
        id: String,
        repo: RepoKey = this.repo,
        stateId: StateId = TasklaneConfig.TODO,
        priorityId: PriorityId = TasklaneConfig.NORMAL,
        order: Long = 1000L,
    ) = Task(
        id = TaskId(id),
        repo = repo,
        body = "Tarea $id",
        stateId = stateId,
        priorityId = priorityId,
        createdAt = t0,
        updatedAt = t0,
        order = order,
    )

    private fun subject(vararg tasks: Task) =
        TaskReducer.Subject(config, tasks.toList(), nextOrder = { 2000L })

    private fun plan(command: TaskCommand, vararg tasks: Task) = reducer.plan(subject(*tasks), command)

    /** La tarea que produce un `Upsert` de una sola fila, que es el caso normal. */
    private fun upserted(command: TaskCommand, vararg tasks: Task): Task =
        (plan(command, *tasks).mutations.single() as Mutation.Upsert).tasks.single()

    private val repositories = listOf(
        RepositoryRef(repo, "root", "", RepositoryRef.Kind.PROJECT_ROOT),
        RepositoryRef(other, "web", "web", RepositoryRef.Kind.GIT, depth = 1),
    )

    // --------------------------------------------------------- lo que no hace nada

    /**
     * Ninguno de éstos cambia nada, y ninguno puede producir una mutación.
     *
     * Es el filtro del que cuelga todo lo demás: `ConfigChanged` se emite en cada
     * arranque, los eventos de VCS llegan repetidos, y la ventana manda `SelectRepo` al
     * restaurar la selección guardada. Una mutación de más aquí es una transacción, un
     * número de revisión nuevo y un repintado por cada uno de ellos.
     */
    @Test
    fun `un comando sin efecto no pide ninguna mutacion`() {
        val a = task("a")
        val casos = listOf(
            TaskCommand.ConfigChanged(config),
            TaskCommand.SelectRepo(repo),
            TaskCommand.ReassignState(TasklaneConfig.DOING, TasklaneConfig.DOING),
            TaskCommand.ReassignPriority(TasklaneConfig.LOW, TasklaneConfig.LOW),
            TaskCommand.RepositoriesChanged(repositories),
            TaskCommand.Create(repo, "   "),
            TaskCommand.Delete(repo, listOf(TaskId("no-existe"))),
            TaskCommand.UpdateBody(repo, TaskId("no-existe"), "otra cosa"),
            TaskCommand.ChangeState(repo, TaskId("a"), TasklaneConfig.TODO),
            TaskCommand.SetTags(repo, TaskId("a"), emptyList()),
            TaskCommand.BackfillCompletedAt(emptySet()),
        )

        for (command in casos) {
            assertTrue("$command no debería pedir nada", plan(command, a).isEmpty)
        }
    }

    /** Y ninguno de ésos cambia la vista tampoco: el mismo snapshot, no una copia igual. */
    @Test
    fun `un comando sin efecto devuelve el mismo snapshot`() {
        val before = TasklaneSnapshot.EMPTY.copy(config = config, repositories = repositories)
        for (
            command in listOf(
                TaskCommand.ConfigChanged(config),
                TaskCommand.SelectRepo(before.activeRepo),
                TaskCommand.RepositoriesChanged(repositories),
            )
        ) {
            assertSame("$command debería devolver el mismo snapshot", before, reducer.view(before, command))
        }
    }

    // ------------------------------------------------- lo que no se materializa

    /**
     * Las operaciones de alcance de proyecto **no llevan tareas**, y eso es la fase
     * entera: borrar un estado con un millón de tareas dentro deja de ser un `map` sobre
     * un millón de objetos y pasa a ser una sentencia que el almacén ejecuta.
     */
    @Test
    fun `lo de alcance de proyecto se describe, no se materializa`() {
        assertEquals(
            Mutation.Reassign(TasklaneConfig.DOING, TasklaneConfig.TODO, t0),
            plan(TaskCommand.ReassignState(TasklaneConfig.DOING, TasklaneConfig.TODO)).mutations.single(),
        )
        assertEquals(
            Mutation.Reprioritize(TasklaneConfig.HIGH, TasklaneConfig.LOW, t0),
            plan(TaskCommand.ReassignPriority(TasklaneConfig.HIGH, TasklaneConfig.LOW)).mutations.single(),
        )
        assertEquals(
            Mutation.Backfill(setOf(TasklaneConfig.DONE)),
            plan(TaskCommand.BackfillCompletedAt(setOf(TasklaneConfig.DONE))).mutations.single(),
        )
        assertEquals(
            Mutation.Forget(other),
            plan(TaskCommand.ForgetRepo(other)).mutations.single(),
        )
    }

    /**
     * Un cambio de configuración lleva la **anterior** dentro de la mutación y la
     * **nueva** en el plan. No es un detalle de implementación: es lo que permite que el
     * almacén toque sólo lo que cambió en vez de reescribir el millón de filas cada vez
     * que alguien abre los ajustes.
     */
    @Test
    fun `cambiar la configuracion lleva las dos, la de antes y la de ahora`() {
        val next = config.copy(states = config.states.filterNot { it.id == TasklaneConfig.DOING }).normalized()

        val result = plan(TaskCommand.ConfigChanged(next))

        assertEquals(Mutation.Renormalize(config), result.mutations.single())
        assertEquals("con la que hay que escribir es la nueva", next, result.config)
    }

    /** Sólo se piden las que existían: un borrado que no borra nada no es una escritura. */
    @Test
    fun `borrar pide solo los ids que estaban`() {
        val mutation = plan(
            TaskCommand.Delete(repo, listOf(TaskId("a"), TaskId("no-existe"))),
            task("a"),
        ).mutations.single()

        assertEquals(Mutation.Delete(listOf(TaskId("a"))), mutation)
    }

    /** Crear pide el hueco de orden a quien lo sabe, que desde la Fase 3 es un índice. */
    @Test
    fun `crear usa el hueco de orden que le dan`() {
        assertEquals(2000L, upserted(TaskCommand.Create(repo, "Algo")).order)
    }

    // ------------------------------------------------------- el comando del diálogo

    /**
     * **`UpdateTask` tiene que dar exactamente la misma tarea que los seis comandos que
     * sustituye.** Es el test que importa de la Fase 1: la optimización sólo vale si el
     * resultado es idéntico, y «idéntico» aquí incluye `updatedAt` y `completedAt`.
     */
    @Test
    fun `UpdateTask hace lo mismo que los seis comandos sueltos`() {
        val vence = Instant.parse("2026-10-01T21:59:59Z")
        val ancla = CodeAnchor.of("src/Auth.kt", 41, 8, "fun login()")
        val original = task("a")

        val deUnGolpe = upserted(
            TaskCommand.UpdateTask(
                repo = repo,
                id = TaskId("a"),
                body = "Cuerpo nuevo",
                stateId = TasklaneConfig.DONE,
                priorityId = TasklaneConfig.HIGH,
                tags = listOf("api", " api ", "ui"),
                dueDate = Optional.of(vence),
                anchors = listOf(ancla, ancla),
            ),
            original,
        )

        var uno = original
        for (
            command in listOf(
                TaskCommand.UpdateBody(repo, TaskId("a"), "Cuerpo nuevo"),
                TaskCommand.ChangeState(repo, TaskId("a"), TasklaneConfig.DONE),
                TaskCommand.ChangePriority(repo, TaskId("a"), TasklaneConfig.HIGH),
                TaskCommand.SetTags(repo, TaskId("a"), listOf("api", " api ", "ui")),
                TaskCommand.SetDueDate(repo, TaskId("a"), vence),
                TaskCommand.SetAnchors(repo, TaskId("a"), listOf(ancla, ancla)),
            )
        ) {
            uno = upserted(command, uno)
        }

        assertEquals(uno, deUnGolpe)
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

        val after = upserted(TaskCommand.UpdateTask(repo, TaskId("a"), body = "Otro cuerpo"), original)

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
        val original = task("a").copy(dueDate = vence)

        assertEquals(vence, upserted(TaskCommand.UpdateTask(repo, TaskId("a"), body = "x"), original).dueDate)
        assertNull(
            upserted(TaskCommand.UpdateTask(repo, TaskId("a"), dueDate = Optional.empty()), original).dueDate,
        )
    }

    /**
     * Aceptar el diálogo sin cambiar nada no puede mover `updatedAt` ni escribir. Con
     * seis comandos sueltos cada uno decidía por su cuenta y la fecha se tocaba igual;
     * aquí se decide una sola vez y para toda la tarea.
     */
    @Test
    fun `aceptar el dialogo sin cambios no pide nada`() {
        val original = task("a").copy(tags = listOf("api"))

        val result = plan(
            TaskCommand.UpdateTask(
                repo = repo,
                id = TaskId("a"),
                body = original.body,
                stateId = original.stateId,
                priorityId = original.priorityId,
                tags = original.tags,
                dueDate = Optional.empty(),
                anchors = original.anchors,
            ),
            original,
        )

        assertTrue(result.isEmpty)
    }

    /** Un id que no existe no puede inventarse una tarea ni escribir nada. */
    @Test
    fun `UpdateTask sobre un id inexistente no hace nada`() {
        assertTrue(plan(TaskCommand.UpdateTask(repo, TaskId("no-existe"), body = "x"), task("a")).isEmpty)
    }

    // ------------------------------------------------------- qué hay que leer antes

    /**
     * El servicio carga **lo que el comando nombra** y nada más. Es la otra mitad del
     * cambio de alcance: si esta lista se quedara corta, el reducer recibiría un sujeto
     * vacío y el comando no haría nada; si se pasara, volveríamos a leer el corpus.
     */
    @Test
    fun `solo se lee lo que el comando nombra`() {
        assertEquals(listOf(TaskId("a")), reducer.targetsOf(TaskCommand.ToggleBookmark(repo, TaskId("a"))))
        assertEquals(
            listOf(TaskId("a"), TaskId("b")),
            reducer.targetsOf(TaskCommand.Delete(repo, listOf(TaskId("a"), TaskId("b")))),
        )
        for (
            command in listOf(
                TaskCommand.Create(repo, "Algo"),
                TaskCommand.ForgetRepo(repo),
                TaskCommand.ConfigChanged(config),
                TaskCommand.ReassignState(TasklaneConfig.DOING, TasklaneConfig.TODO),
                TaskCommand.BackfillCompletedAt(setOf(TasklaneConfig.DONE)),
            )
        ) {
            assertTrue("$command no necesita leer ninguna tarea", reducer.targetsOf(command).isEmpty())
        }
    }
}
