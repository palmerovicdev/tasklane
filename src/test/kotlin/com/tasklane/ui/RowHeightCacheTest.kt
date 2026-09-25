package com.tasklane.ui

import com.intellij.ui.CheckedTreeNode
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.LinkExtractor
import com.tasklane.ui.toolwindow.TaskNode
import com.tasklane.ui.toolwindow.TaskTreeRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.time.Instant
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.DefaultTreeModel

/**
 * Lo que el árbol tiene **guardado** de cada fila y lo que el renderer mide **ahora**
 * tienen que ser el mismo número.
 *
 * Es la propiedad de la que cuelga todo lo que se ve de la lista, y no sólo el aspecto
 * de una tarjeta:
 *
 * - `JTree` con `rowHeight = 0` pinta cada fila dentro de la altura que tiene guardada,
 *   mientras que el renderer envuelve el texto contra el ancho de **este** momento.
 *   Cuando no coinciden, [com.tasklane.ui.toolwindow.RowStack] tiene que dejar caer el
 *   último renglón para que quepa la línea de distintivos: tarjetas a las que les falta
 *   una línea sin que nada lo explique.
 * - Y el `JScrollPane` calcula con esas mismas alturas si hace falta barra y hasta
 *   dónde llega la lista. Con las viejas la lista parece más corta de lo que es y el
 *   final no se alcanza: la última tarjeta se queda cortada contra el borde.
 *
 * El ancho cambia solo, sin que nadie toque la tool window: el árbol mide exactamente
 * lo que se ve (`getScrollableTracksViewportWidth`), así que **aparecer la barra de
 * desplazamiento vertical ya lo estrecha**. Por eso esto no es un caso raro de
 * redimensionar, sino lo que pasa en cuanto la lista crece de más.
 *
 * Lo que demuestra este test es que las medidas viejas **no valen** para el ancho
 * nuevo, que es lo que obliga a que `TasklanePanel` las tire en el `setBounds` del
 * árbol —dentro del reparto de sitio, antes de pintar— y no en un `componentResized`,
 * que es un evento encolado y llega una pasada tarde.
 */
class RowHeightCacheTest {

    private val renderer = TaskTreeRenderer().apply {
        config = TasklaneConfig.DEFAULT
        // Sin IDE no hay `DateFormatUtil`; la fecha es texto para medir, nada más.
        formatDate = { "13/09/2026" }
    }

    /** Títulos de largos variados: los que envuelven son los que cambian de altura. */
    private val bodies = listOf(
        "ver lo de unpublished changes",
        "mejorar selector de businesses en add category de email template last config and save",
        "cambiar la forma de buscar por la paleta de la compañia",
        "revisar bien la impl de spacer con Brian",
        "problema de businesses cuando creo y voy a getById no me salen en websites",
        "actualizar la doc de vitepress",
        "revisar por que se sigue rompiendo scroll y demas en calendar",
    )

    private fun task(body: String) = Task(
        id = TaskId.random(),
        repo = RepoKey.ROOT,
        body = body,
        stateId = TasklaneConfig.TODO,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        links = LinkExtractor.extract(body),
        attachments = ImageRefParser.parse(body),
    )

    /** El árbol de la tool window en lo que aquí importa: mide lo que se ve. */
    private class ListTree(model: DefaultTreeModel) : JTree(model) {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }

    /**
     * Le tira al árbol las medidas guardadas, que es lo que hace `TasklanePanel` vía
     * `TreeUtil.invalidateCacheAndRepaint` en cuanto cambia de ancho.
     */
    private fun remeasure(tree: JTree) {
        val ui = tree.ui as BasicTreeUI
        ui.leftChildIndent = ui.leftChildIndent
    }

    /** La altura que el árbol tiene guardada de [row]. Es la que usa para pintarla. */
    private fun cached(tree: JTree, row: Int) = tree.getRowBounds(row).height

    /** La altura que el renderer mide para [row] contra el ancho de ahora mismo. */
    private fun fresh(tree: JTree, row: Int): Int {
        val node = tree.getPathForRow(row).lastPathComponent
        return renderer
            .getTreeCellRendererComponent(tree, node, false, false, true, row, false)
            .preferredSize.height
    }

    private fun mismatches(tree: JTree) =
        (0 until tree.rowCount).count { cached(tree, it) != fresh(tree, it) }

    private fun scrollWith(width: Int): Pair<JScrollPane, JTree> {
        val root = CheckedTreeNode("root")
        bodies.forEach { root.add(TaskNode(task(it), false)) }
        val tree = ListTree(DefaultTreeModel(root))
        tree.cellRenderer = renderer
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.rowHeight = 0

        val scroll = JScrollPane(tree)
        scroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scroll.size = Dimension(width, 600)
        scroll.doLayout()
        scroll.validate()
        // `JTree` mide sus filas en cuanto tiene modelo, y ahí todavía no tiene ancho.
        remeasure(tree)
        return scroll to tree
    }

    private fun resize(scroll: JScrollPane, width: Int) {
        scroll.size = Dimension(width, 600)
        scroll.doLayout()
        scroll.validate()
    }

    @Test
    fun `con el mismo ancho lo guardado y lo medido coinciden`() {
        val (_, tree) = scrollWith(520)

        assertEquals(0, mismatches(tree))
    }

    /**
     * El fallo, escrito como tal: estrechar deja alturas que ya no valen. Quien lo
     * arregla es el `setBounds` del árbol de `TasklanePanel`, que remide ahí mismo; sin
     * remedir, el árbol pinta y el `JScrollPane` calcula con estos números.
     */
    @Test
    fun `estrechar deja alturas que ya no valen hasta que se remide`() {
        val (scroll, tree) = scrollWith(520)
        val altoAncho = tree.preferredSize.height

        resize(scroll, 340)
        assertTrue("estrechar tiene que descuadrar alguna fila", mismatches(tree) > 0)

        remeasure(tree)
        assertEquals("y remedir las cuadra todas", 0, mismatches(tree))
        assertTrue(
            "con menos ancho el texto ocupa más líneas, así que la lista es más alta",
            tree.preferredSize.height > altoAncho,
        )
    }

    /**
     * Y lo que se pierde por no remedir no es un píxel: es el final de la lista.
     *
     * Con las alturas viejas el árbol anuncia menos alto del que tiene, así que el
     * hueco visible no llega a la última fila —la tarjeta de abajo se queda cortada
     * contra el borde y no hay forma de bajar más—.
     */
    @Test
    fun `sin remedir la lista se anuncia mas corta de lo que es`() {
        val (scroll, tree) = scrollWith(520)

        resize(scroll, 340)
        val anunciado = tree.preferredSize.height
        remeasure(tree)
        val real = tree.preferredSize.height

        assertTrue("se anuncia corta: faltan ${real - anunciado} px de lista", anunciado < real)
        assertEquals(
            "y el alto real es la suma de las filas",
            (0 until tree.rowCount).sumOf { cached(tree, it) },
            real,
        )
    }
}
