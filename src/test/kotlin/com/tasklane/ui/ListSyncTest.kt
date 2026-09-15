package com.tasklane.ui

import com.intellij.ui.CheckedTreeNode
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.paging.MemoryPager
import com.tasklane.paging.TaskPager
import com.tasklane.service.SearchResults
import com.tasklane.ui.toolwindow.EmptyGroupNode
import com.tasklane.ui.toolwindow.GroupBudget
import com.tasklane.ui.toolwindow.GroupNode
import com.tasklane.ui.toolwindow.ListSync
import com.tasklane.ui.toolwindow.MoreNode
import com.tasklane.ui.toolwindow.TaskNode
import com.tasklane.ui.toolwindow.TaskTreeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * La lista acotada, de punta a punta: pager de verdad, árbol de verdad.
 *
 * Es el test que cubre lo que la Fase 2 **cambia para el usuario**, y por eso mira
 * filas y no estructuras: cuántas hay, qué dice la última, qué pasa al abrir una
 * cabecera y qué pasa al pedir que se enseñe algo que está en la fila diez mil.
 *
 * [ListSync] vive fuera de `TasklanePanel` justo para que esto se pueda escribir: lo
 * único que necesita es un `JTree`, y `JTree` no necesita un IDE. Lo que se queda en el
 * panel es lo que sí lo necesita —la tool window, los atajos, el diálogo—.
 */
class ListSyncTest {

    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private val today = LocalDate.of(2026, 9, 15)
    private val zone = ZoneId.of("UTC")
    private val todo = TasklaneConfig.TODO

    private val root = CheckedTreeNode("root")
    private val model = TaskTreeModel(root)
    private val tree = JTree(model).apply {
        isRootVisible = false
        showsRootHandles = false
    }

    private fun task(
        id: String,
        tags: List<String> = emptyList(),
        updatedAt: Instant = now,
    ) = Task(
        id = TaskId(id),
        repo = RepoKey.ROOT,
        body = id,
        stateId = todo,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = now,
        updatedAt = updatedAt,
        tags = tags,
    )

    /** Tareas numeradas con el orden natural ya decidido: la 0 arriba. */
    private fun many(count: Int, tags: List<String> = emptyList()) =
        List(count) { i -> task("t%05d".format(i), tags, now.minusSeconds(i.toLong())) }

    private fun config(grouping: Grouping, anchor: DateAnchor = DateAnchor.UPDATED) =
        TasklaneConfig.DEFAULT.let { base ->
            base.copy(
                states = base.states.map {
                    if (it.id == todo) TaskState(todo, "ToDo", 0, grouping, anchor, isDefault = true) else it
                },
            )
        }

    private fun pager(tasks: List<Task>, grouping: Grouping) = MemoryPager(
        tasks = tasks,
        config = config(grouping),
        found = SearchResults.NONE,
        filter = TaskFilter.ALL,
        now = now,
        zone = zone,
        today = today,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    private fun list(tasks: List<Task>, grouping: Grouping = Grouping.NONE, state: StateId = todo): ListSync {
        val pager = pager(tasks, grouping)
        return ListSync(tree, state).apply {
            this.pager = pager
            outline = pager.outline(state)
            emptyText = "no queda nada"
        }
    }

    private fun rows(): List<Any> = (0 until tree.rowCount).map { tree.getPathForRow(it).lastPathComponent }

    private fun lastRow(): Any = tree.getPathForRow(tree.rowCount - 1).lastPathComponent

    // ------------------------------------------------------------------ paginación

    @Test
    fun `una lista larga se pinta a paginas, con un centinela al final`() {
        val sync = list(many(1_000))

        sync.sync()

        assertEquals(TaskPager.PAGE + 1, tree.rowCount)
        assertEquals(1_000 - TaskPager.PAGE, (lastRow() as MoreNode).remaining)
    }

    @Test
    fun `una lista corta se pinta entera y sin centinela`() {
        val sync = list(many(7))

        sync.sync()

        assertEquals(7, tree.rowCount)
        assertTrue(rows().all { it is TaskNode })
    }

    @Test
    fun `el centinela trae la siguiente pagina`() {
        val sync = list(many(1_000))
        sync.sync()

        sync.loadMore(lastRow() as MoreNode)

        assertEquals(2 * TaskPager.PAGE + 1, tree.rowCount)
        assertEquals(1_000 - 2 * TaskPager.PAGE, (lastRow() as MoreNode).remaining)
    }

    /** Y la página que ya estaba **no se vuelve a crear**: de eso vive el presupuesto de EDT. */
    @Test
    fun `traer una pagina mas no rehace la anterior`() {
        val sync = list(many(1_000))
        sync.sync()
        val first = tree.getPathForRow(0).lastPathComponent

        val touched = sync.loadMore(lastRow() as MoreNode)

        assertSame(first, tree.getPathForRow(0).lastPathComponent)
        assertEquals("las filas nuevas y el centinela puesto al día", TaskPager.PAGE + 1, touched)
    }

    @Test
    fun `el centinela desaparece al llegar al final`() {
        val sync = list(many(TaskPager.PAGE + 3))
        sync.sync()

        sync.loadMore(lastRow() as MoreNode)

        assertEquals(TaskPager.PAGE + 3, tree.rowCount)
        assertTrue(lastRow() is TaskNode)
    }

    @Test
    fun `volver al principio deja la lista como estaba`() {
        val sync = list(many(1_000))
        sync.sync()
        sync.loadMore(lastRow() as MoreNode)

        sync.rewind()
        sync.sync()

        assertEquals(TaskPager.PAGE + 1, tree.rowCount)
    }

    // ------------------------------------------------------------------ grupos

    @Test
    fun `los grupos pequeños se abren solos con su primera pagina`() {
        val sync = list(many(6, tags = listOf("api")) + many(4).map { it.copy(tags = listOf("ui")) }, Grouping.BY_TAG)

        sync.sync()

        val headers = rows().filterIsInstance<GroupNode>()
        assertEquals(2, headers.size)
        assertTrue(headers.all { tree.isExpanded(TreePath(it.path)) })
        assertEquals("dos cabeceras y las diez tareas", 12, tree.rowCount)
    }

    /**
     * La regla que hace que una cabecera pueda anunciar cientos de miles de tareas sin
     * tenerlas: por encima del umbral empieza plegada, y plegada **no tiene hijos**.
     */
    @Test
    fun `un grupo grande empieza plegado y sin filas`() {
        val big = GroupBudget.BIG_GROUP + 1
        val sync = list(many(big, tags = listOf("api")), Grouping.BY_TAG)

        sync.sync()

        assertEquals("sólo la cabecera", 1, tree.rowCount)
        val header = rows().single() as GroupNode
        assertEquals(big, header.size)
        assertEquals(0, header.childCount)
        assertFalse(tree.isExpanded(TreePath(header.path)))
    }

    @Test
    fun `abrir una cabecera cerrada le pide su primera pagina`() {
        val big = GroupBudget.BIG_GROUP + 1
        val sync = list(many(big, tags = listOf("api")), Grouping.BY_TAG)
        sync.sync()
        val header = rows().single() as GroupNode

        sync.toggled(header.key, expanded = true)
        sync.sync()

        assertEquals("cabecera, una página y el centinela", TaskPager.PAGE + 2, tree.rowCount)
        assertEquals(big - TaskPager.PAGE, (lastRow() as MoreNode).remaining)
    }

    @Test
    fun `cerrar una cabecera le quita las filas`() {
        val sync = list(many(6, tags = listOf("api")), Grouping.BY_TAG)
        sync.sync()
        val header = rows().first() as GroupNode

        sync.toggled(header.key, expanded = false)
        sync.sync()

        assertEquals(1, tree.rowCount)
        assertEquals(0, header.childCount)
        assertEquals("pero la cabecera sigue sabiendo cuántas hay", 6, header.size)
    }

    /**
     * «Hoy» se enseña aunque esté vacío —que no haya nada hoy es la respuesta que se
     * viene a buscar— y una cabecera sola encima de otra se lee como un fallo de
     * pintado, así que lleva su propia fila.
     */
    @Test
    fun `un grupo vacio enseña su fila de no queda nada`() {
        val ayer = many(2).map { it.copy(updatedAt = now.minusSeconds(60 * 60 * 24)) }
        val sync = list(ayer, Grouping.BY_DATE)

        sync.sync()

        assertEquals(GroupKey.OfDate(DateGroup.Today), (rows().first() as GroupNode).key)
        assertEquals("no queda nada", (rows()[1] as EmptyGroupNode).text)
    }

    /** Miles de etiquetas son miles de cabeceras, y una cabecera es una fila que medir. */
    @Test
    fun `las cabeceras tambien se paginan`() {
        val sync = list(List(200) { task("t$it", tags = listOf("tag%03d".format(it))) }, Grouping.BY_TAG)

        sync.sync()

        val sentinel = lastRow() as MoreNode
        assertTrue("no están las doscientas", tree.rowCount < 200)
        sync.loadMore(sentinel)
        assertTrue("y pulsarlo trae más", rows().filterIsInstance<GroupNode>().size > 50)
    }

    // ------------------------------------------------------------------ enseñar

    @Test
    fun `enseñar algo de la fila diez mil abre la ventana a su altura`() {
        val sync = list(many(10_000))
        sync.sync()

        assertTrue(sync.reveal(setOf(TaskId("t09000"))))
        sync.sync()

        val ids = rows().filterIsInstance<TaskNode>().map { it.task.id.value }
        assertTrue("la tarea está puesta", "t09000" in ids)
        assertTrue("y sin arrastrar las nueve mil de antes", tree.rowCount < 2 * TaskPager.PAGE)
        assertEquals(MoreNode.Direction.BEFORE, (rows().first() as MoreNode).direction)
    }

    @Test
    fun `el centinela de arriba trae lo que quedo por encima`() {
        val sync = list(many(10_000))
        sync.reveal(setOf(TaskId("t09000")))
        sync.sync()
        val above = rows().first() as MoreNode
        val remaining = above.remaining

        sync.loadMore(above)

        assertEquals(remaining - TaskPager.PAGE, (rows().first() as MoreNode).remaining)
        assertTrue(rows().filterIsInstance<TaskNode>().map { it.task.id.value }.contains("t09000"))
    }

    @Test
    fun `enseñar algo de un grupo cerrado lo abre`() {
        val big = GroupBudget.BIG_GROUP + 1
        val sync = list(many(big, tags = listOf("api")), Grouping.BY_TAG)
        sync.sync()
        assertEquals("empieza plegado", 1, tree.rowCount)

        assertTrue(sync.reveal(setOf(TaskId("t00003"))))
        sync.sync()

        assertTrue(tree.rowCount > 1)
        assertTrue(rows().filterIsInstance<TaskNode>().any { it.task.id.value == "t00003" })
    }

    @Test
    fun `enseñar algo que no esta no toca nada`() {
        val sync = list(many(10))
        sync.sync()

        assertFalse(sync.reveal(setOf(TaskId("no-existe"))))
    }

    // ------------------------------------------------------------------ repintar

    @Test
    fun `repintar con el modelo cambiado solo toca lo que cambio`() {
        val tasks = many(1_000)
        val sync = list(tasks)
        sync.sync()
        val second = tree.getPathForRow(1).lastPathComponent

        // Una tarea editada: el reducer copia la que toca y deja las demás como están.
        val edited = tasks.toMutableList().also { it[0] = it[0].copy(body = "otra cosa") }
        sync.pager = pager(edited, Grouping.NONE)
        val touched = sync.sync()

        assertEquals("una fila, la suya", 1, touched)
        assertSame("y las demás son los mismos nodos", second, tree.getPathForRow(1).lastPathComponent)
    }

    @Test
    fun `un repintado sin cambios no toca ninguna fila`() {
        val sync = list(many(1_000))
        sync.sync()

        assertEquals(0, sync.sync())
    }

    @Test
    fun `una lista vacia deja el arbol vacio`() {
        val sync = list(emptyList())

        sync.sync()

        assertEquals(0, tree.rowCount)
    }

    /**
     * Lo que la exportación necesita saber **antes de leer nada**: cuántas filas tiene lo
     * que va a exportar, para decidir si cabe en el portapapeles. La cabecera ya lo dice, y
     * la raíz lo cuenta la pestaña; la página cargada no tiene nada que ver.
     */
    @Test
    fun `lo que se va a exportar se cuenta sin leerlo`() {
        val grouped = list(many(1_000, tags = listOf("api")), Grouping.BY_TAG)
        grouped.sync()

        assertEquals(1_000, grouped.sizeOf(GroupKey.OfTag("api")))
        assertNotNull(grouped.outline.firstOrNull())
        assertEquals(0, grouped.sizeOf(GroupKey.OfTag("no-existe")))

        val flat = list(many(1_000))
        flat.sync()
        assertEquals(1_000, flat.sizeOf(null))
    }
}
