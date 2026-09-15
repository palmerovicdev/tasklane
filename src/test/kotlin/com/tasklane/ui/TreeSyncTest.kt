package com.tasklane.ui

import com.intellij.ui.CheckedTreeNode
import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.ui.toolwindow.GroupNode
import com.tasklane.ui.toolwindow.MoreNode
import com.tasklane.ui.toolwindow.Row
import com.tasklane.ui.toolwindow.TaskNode
import com.tasklane.ui.toolwindow.TaskTreeModel
import com.tasklane.ui.toolwindow.TreeSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * Por qué el árbol se sincroniza por diferencias, y qué tiene que cumplir para que
 * sirva de algo.
 *
 * La propiedad que se comprueba una y otra vez es **la identidad de los nodos**: un
 * nodo que sobrevive a un repintado conserva su altura ya medida en la caché de
 * `JTree`, y medir una fila cuesta ~0,064 ms del hilo de interfaz (`docs/plan-escala.md`
 * §0-bis.4). `removeAllChildren()` + `reload()` la tiraba entera; de ahí los 2,1 s de
 * repintado con 100.000 tareas. Si estos `assertSame` se convierten en `assertNotSame`,
 * la Fase 2 deja de valer aunque la lista siga saliendo bien.
 *
 * `JTree` a secas y no `CheckboxTree` por lo mismo que en [TaskTreeRendererTest]: el
 * constructor de la de la plataforma necesita la `Application` del IDE, y lo que se
 * mide aquí es el modelo.
 */
class TreeSyncTest {

    private val root = CheckedTreeNode("root")
    private val model = TaskTreeModel(root)
    private val tree = JTree(model).apply {
        isRootVisible = false
        showsRootHandles = false
    }

    /**
     * La misma tarea es **el mismo objeto**, como en producción: el reducer sólo copia
     * lo que toca, así que las demás llegan al repintado siendo las de antes. De eso
     * vive [TaskNode.update], que decide por identidad si hay algo que remedir — el
     * mismo contrato que la Fase 1 dejó puesto en el servicio.
     */
    private val tasks = HashMap<String, Task>()

    private fun task(id: String) = tasks.getOrPut(id) {
        Task(
            id = TaskId(id),
            repo = RepoKey.ROOT,
            body = id,
            stateId = TasklaneConfig.TODO,
            priorityId = TasklaneConfig.NORMAL,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
    }

    private fun rows(vararg ids: String): List<Row> = ids.map { Row.OfTask(task(it), false) }

    private fun sync(rows: List<Row>) = TreeSync.sync(model, root, rows)

    private fun children(): List<String> =
        (0 until root.childCount).map { (root.getChildAt(it) as TaskNode).task.id.value }

    private fun nodeAt(index: Int) = root.getChildAt(index)

    // ------------------------------------------------------- identidad de los nodos

    @Test
    fun `lo que sigue estando es el mismo nodo`() {
        sync(rows("a", "b", "c"))
        val before = (0 until 3).map(::nodeAt)

        sync(rows("a", "b", "c"))

        assertEquals(before, (0 until 3).map(::nodeAt))
        for (index in 0 until 3) assertSame(before[index], nodeAt(index))
    }

    @Test
    fun `una tarea que cambia de contenido conserva su nodo`() {
        sync(listOf(Row.OfTask(task("a"), false)))
        val node = nodeAt(0) as TaskNode

        val edited = task("a").copy(body = "otro cuerpo")
        val touched = sync(listOf(Row.OfTask(edited, true)))

        assertSame("el nodo se pone al día, no se sustituye", node, nodeAt(0))
        assertSame(edited, node.task)
        assertTrue("pero su fila hay que remedirla", node.isChecked)
        assertEquals(1, touched)
    }

    @Test
    fun `repintar sin cambios no toca ni una fila`() {
        val same = rows("a", "b", "c")
        sync(same)

        assertEquals(0, sync(same))
    }

    @Test
    fun `reordenar mueve el nodo en vez de rehacerlo`() {
        sync(rows("a", "b", "c"))
        val b = nodeAt(1)

        sync(rows("b", "a", "c"))

        assertEquals(listOf("b", "a", "c"), children())
        assertSame(b, nodeAt(0))
    }

    @Test
    fun `lo que desaparece se va y lo nuevo entra donde le toca`() {
        sync(rows("a", "b", "c"))
        val c = nodeAt(2)

        val touched = sync(rows("a", "nueva", "c"))

        assertEquals(listOf("a", "nueva", "c"), children())
        // Una sola fila medida: quitar 'b' e insertar 'nueva' en su hueco deja a 'c'
        // en el mismo índice, así que ni se mueve ni se remide.
        assertEquals(1, touched)
        assertSame(c, nodeAt(2))
    }

    @Test
    fun `una tarea borrada y otra con su mismo sitio no heredan nodo`() {
        sync(rows("a"))
        val a = nodeAt(0)

        sync(rows("b"))

        assertNotSame(a, nodeAt(0))
        assertEquals(listOf("b"), children())
    }

    // ------------------------------------------------------------- filas mezcladas

    @Test
    fun `tareas, centinela y grupo vacio conviven bajo el mismo padre`() {
        val group = GroupNode(GroupKey.OfDate(DateGroup.Today), 3)
        model.insertNodeInto(group, root, 0)

        TreeSync.sync(model, group, rows("a", "b") + Row.OfMore(MoreNode.Direction.AFTER, 4_213))

        assertEquals(3, group.childCount)
        assertEquals(4_213, (group.getChildAt(2) as MoreNode).remaining)
    }

    @Test
    fun `el centinela se pone al dia sin cambiar de nodo`() {
        sync(listOf(Row.OfMore(MoreNode.Direction.AFTER, 900)))
        val sentinel = nodeAt(0)

        val touched = sync(listOf(Row.OfMore(MoreNode.Direction.AFTER, 800)))

        assertSame(sentinel, nodeAt(0))
        assertEquals(800, (sentinel as MoreNode).remaining)
        assertEquals(1, touched)
    }

    @Test
    fun `los dos centinelas de una ventana son filas distintas`() {
        sync(
            listOf(Row.OfMore(MoreNode.Direction.BEFORE, 450)) +
                rows("a") +
                Row.OfMore(MoreNode.Direction.AFTER, 450),
        )

        assertEquals(3, root.childCount)
        assertEquals(MoreNode.Direction.BEFORE, (nodeAt(0) as MoreNode).direction)
        assertEquals(MoreNode.Direction.AFTER, (nodeAt(2) as MoreNode).direction)
    }

    // ------------------------------------------------------------- grupos perezosos

    /**
     * La regla que hace posible que una cabecera anuncie 800.000 tareas sin tenerlas:
     * un grupo vacío **no es una hoja**, así que se puede desplegar, y es ese gesto el
     * que pide su primera página. Con el `isLeaf` de fábrica —hoja == sin hijos— el
     * árbol se negaba a desplegarlo y no había nada que disparase la carga.
     */
    @Test
    fun `una cabecera sin hijos se puede desplegar`() {
        val group = GroupNode(GroupKey.OfDate(DateGroup.Today), 800_000)
        model.insertNodeInto(group, root, 0)
        val path = TreePath(group.path)

        assertFalse("una cabecera nunca es hoja", model.isLeaf(group))
        tree.expandPath(path)

        assertTrue(tree.isExpanded(path))
    }

    @Test
    fun `una tarea si es hoja`() {
        sync(rows("a"))

        assertTrue(model.isLeaf(nodeAt(0)))
    }

    /** Plegar un grupo le quita los hijos: un grupo cerrado no tiene por qué cargarlos. */
    @Test
    fun `vaciar un grupo lo deja con su cuenta y sin filas`() {
        val group = GroupNode(GroupKey.OfDate(DateGroup.Today), 5)
        model.insertNodeInto(group, root, 0)
        TreeSync.sync(model, group, rows("a", "b"))

        TreeSync.clear(model, group)

        assertEquals(0, group.childCount)
        assertEquals("la cabecera sigue sabiendo cuántas hay", 5, group.size)
    }

    /**
     * La cuenta de una cabecera sale del agregado, no de los hijos: cambia sola cuando
     * una tarea entra o sale del grupo, aunque el grupo esté plegado.
     */
    @Test
    fun `la cuenta de la cabecera se actualiza sin tocar sus hijos`() {
        sync(listOf(Row.OfGroup(GroupKey.OfDate(DateGroup.Today), 5)))
        val group = nodeAt(0) as GroupNode

        val touched = sync(listOf(Row.OfGroup(GroupKey.OfDate(DateGroup.Today), 6)))

        assertSame(group, nodeAt(0))
        assertEquals(6, group.size)
        assertEquals(1, touched)
    }

    /**
     * Y lo que de verdad importa de eso: un grupo desplegado **sigue desplegado**
     * cuando aparece otro por encima. Antes esto lo rehacía `expandGroups()` después
     * de cada `reload()`, con su medida de filas detrás.
     */
    @Test
    fun `insertar una cabecera por encima no cierra las de abajo`() {
        sync(listOf(Row.OfGroup(GroupKey.OfDate(DateGroup.Yesterday), 2)))
        val yesterday = nodeAt(0) as GroupNode
        TreeSync.sync(model, yesterday, rows("a", "b"))
        tree.expandPath(TreePath(root))
        tree.expandPath(TreePath(yesterday.path))

        sync(
            listOf(
                Row.OfGroup(GroupKey.OfDate(DateGroup.Today), 1),
                Row.OfGroup(GroupKey.OfDate(DateGroup.Yesterday), 2),
            ),
        )

        assertSame(yesterday, nodeAt(1))
        assertTrue(tree.isExpanded(TreePath(yesterday.path)))
    }

    // ------------------------------------------------------------------ la raíz

    /**
     * El modo de fallo que esta fase podía introducir y que no se ve en ninguna
     * aserción de contenido: `JTree` marca la raíz como desplegada al **instalar** el
     * modelo, y sólo si ya tiene hijos. Aquí la lista nace vacía y se llena por
     * diferencias, así que sin desplegarla a mano el árbol se queda en blanco con el
     * modelo lleno. Ver `TasklanePanel.expandRoot`.
     */
    @Test
    fun `la raiz no se despliega sola al llenarse`() {
        val rootPath = TreePath(root)
        assertFalse("nace vacía, y por tanto hoja", tree.isExpanded(rootPath))

        sync(rows("a", "b"))

        assertFalse("llenarla no la despliega: eso es lo que hacía el reload()", tree.isExpanded(rootPath))
        tree.expandPath(rootPath)
        assertEquals(2, tree.rowCount)
    }
}
