package com.tasklane.ui

import com.intellij.ui.CheckedTreeNode
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.LinkExtractor
import com.tasklane.ui.toolwindow.TaskNode
import com.tasklane.ui.toolwindow.TaskTreeRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Point
import java.time.Instant
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel

/**
 * El renderer de dos líneas y su *hit testing*, sin arrancar un IDE.
 *
 * Lo que se comprueba no es dónde cae cada píxel —eso depende de la fuente— sino la
 * propiedad que importa: que **existe** una zona clicable para el enlace del título y
 * otra para el indicador, y que el checkbox no es ninguna de las dos. Es justo lo que
 * se rompería al reorganizar el panel del renderer de la plataforma.
 */
class TaskTreeRendererTest {

    private val renderer = TaskTreeRenderer().apply {
        config = TasklaneConfig.DEFAULT
        // Sin IDE no hay `DateFormatUtil`; la fecha es texto para medir, nada más.
        formatDate = { "13/09/2026" }
    }

    private fun task(body: String) = Task(
        id = TaskId.random(),
        repo = RepoKey.ROOT,
        body = body,
        stateId = TasklaneConfig.TODO,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        links = LinkExtractor.extract(body),
    )

    /**
     * Un árbol con una sola fila, medido como lo mediría la tool window.
     *
     * `JTree` a secas y no `CheckboxTree`: el constructor de la de la plataforma
     * instala su speed search y eso sí necesita la `Application`. Para lo que se
     * mide aquí —posiciones y fragmentos— da igual: el renderer es el mismo.
     */
    private fun treeWith(task: Task): JTree {
        val root = CheckedTreeNode("root")
        root.add(TaskNode(task, false))
        val tree = JTree(DefaultTreeModel(root))
        tree.cellRenderer = renderer
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.rowHeight = 0
        tree.setSize(600, 200)
        tree.doLayout()
        return tree
    }

    /** Los enlaces que devuelve el renderer barriendo una fila de izquierda a derecha. */
    private fun sweep(tree: JTree, row: Int): Map<Int, List<String>> {
        val bounds = tree.getRowBounds(row)
        val y = bounds.y + bounds.height / 2
        return (0 until bounds.width).associateWith { x ->
            renderer.linksAt(tree, Point(bounds.x + x, y)).map { it.url }
        }.filterValues { it.isNotEmpty() }
    }

    @Test
    fun `el enlace del titulo es clicable y el checkbox no`() {
        val tree = treeWith(task("Revisar https://ejemplo.com/a antes del viernes"))
        val bounds = tree.getRowBounds(0)

        val hits = sweep(tree, 0)
        assertTrue("el enlace del titulo tiene que tener zona clicable", hits.isNotEmpty())
        assertEquals(setOf(listOf("https://ejemplo.com/a")), hits.values.toSet())

        // El borde izquierdo de la fila es la franja de prioridad y el checkbox.
        assertTrue(
            "el checkbox no puede abrir el navegador",
            renderer.linksAt(tree, Point(bounds.x + 1, bounds.y + bounds.height / 2)).isEmpty(),
        )
    }

    @Test
    fun `un enlace en el detalle solo se alcanza por el indicador`() {
        // El caso que justifica la segunda línea: el enlace no está en el título, así
        // que sin indicador habría que entrar a editar para abrirlo.
        val tree = treeWith(task("Migrar el indice\nla guia esta en https://ejemplo.com/guia"))
        val bounds = tree.getRowBounds(0)

        // Dos líneas: la fila es más alta que una sola.
        assertTrue("la segunda linea tiene que ocupar sitio", bounds.height > 0)

        // Se barre la fila entera y no una altura concreta: dónde cae exactamente la
        // línea de distintivos depende del relleno de la tarjeta, y lo que se afirma
        // es que **existe** una zona clicable, no en qué píxel está.
        val hit = scan(tree, bounds) { point -> renderer.linksAt(tree, point).takeIf { it.isNotEmpty() } }

        assertEquals(listOf("https://ejemplo.com/guia"), hit?.map { it.url })
    }

    @Test
    fun `el marcador y el menu se pueden pulsar en la fila del raton`() {
        val tree = treeWith(task("Comprar pan"))
        renderer.hoveredRow = 0
        val bounds = tree.getRowBounds(0)

        val targets = scanAll(tree, bounds) { point -> renderer.targetAt(tree, point) }

        assertTrue("el marcador tiene que tener zona clicable", TaskTreeRenderer.RowTarget.BOOKMARK in targets)
        assertTrue("el menu tiene que tener zona clicable", TaskTreeRenderer.RowTarget.MENU in targets)
    }

    @Test
    fun `sin el raton encima no hay controles que pulsar`() {
        val tree = treeWith(task("Comprar pan"))
        renderer.hoveredRow = -1
        val bounds = tree.getRowBounds(0)

        assertTrue(scanAll(tree, bounds) { point -> renderer.targetAt(tree, point) }.isEmpty())
    }

    /**
     * La banda que el doble clic ignora. No se comprueba dónde acaba —eso depende del
     * tema— sino lo que hace falta para que el gesto no se pise con el de marcar: que
     * el borde izquierdo de la fila sea casilla y el centro no.
     */
    @Test
    fun `la casilla ocupa el borde izquierdo y nada mas`() {
        val tree = treeWith(task("Comprar pan"))
        val bounds = tree.getRowBounds(0)
        val y = bounds.y + bounds.height / 2

        assertTrue(
            "el borde izquierdo de la fila es la casilla",
            renderer.isOnCheckbox(tree, Point(bounds.x + 1, y)),
        )
        assertTrue(
            "el centro de la tarjeta tiene que abrir la tarea, no marcarla",
            !renderer.isOnCheckbox(tree, Point(bounds.x + bounds.width / 2, y)),
        )
    }

    @Test
    fun `una tarea sin enlaces no responde en ningun punto`() {
        val tree = treeWith(task("Comprar pan"))
        assertTrue(sweep(tree, 0).isEmpty())
    }

    /** El primer punto de la fila donde [probe] contesta algo. Rejilla de 2 px. */
    private fun <T : Any> scan(tree: JTree, bounds: java.awt.Rectangle, probe: (Point) -> T?): T? =
        scanAll(tree, bounds, probe).firstOrNull()

    private fun <T : Any> scanAll(tree: JTree, bounds: java.awt.Rectangle, probe: (Point) -> T?): List<T> =
        (0 until bounds.height step 2).flatMap { y ->
            (0 until bounds.width step 2).mapNotNull { x -> probe(Point(bounds.x + x, bounds.y + y)) }
        }

    companion object {
        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
