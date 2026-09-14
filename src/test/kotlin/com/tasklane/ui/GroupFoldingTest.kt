package com.tasklane.ui

import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.treeStructure.Tree
import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.GroupKey
import com.tasklane.ui.toolwindow.GroupNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Por qué las cabeceras de grupo se pliegan con un `collapsePath` propio.
 *
 * `TasklanePanel` monta un [com.intellij.ui.CheckboxTree], que hereda de [Tree]. El
 * constructor de `CheckboxTree` necesita la `Application` del IDE, así que aquí se
 * prueba sobre [Tree] directamente: es de donde viene el `collapsePath` que causaba
 * el problema, y el árbol se configura igual que el de la tool window —raíz oculta y
 * **sin manecillas**—, que es la combinación que lo dispara.
 *
 * El primer test describe **la plataforma**: si algún día deja de comportarse así,
 * falla y avisa de que el atajo del panel sobra. El segundo describe **la cura**, que
 * es la que lleva el panel palabra por palabra.
 */
class GroupFoldingTest {

    /** El árbol del panel: una raíz invisible, cabeceras de grupo y tareas dentro. */
    private fun tree(withFix: Boolean): Pair<JTree, TreePath> {
        val root = CheckedTreeNode("tasklane")
        val group = GroupNode(GroupKey.OfDate(DateGroup.Today), 1)
        group.add(CheckedTreeNode("task"))
        root.add(group)

        val model = DefaultTreeModel(root)
        val tree = if (withFix) {
            object : Tree(model) {
                override fun collapsePath(path: TreePath) = setExpandedState(path, false)
            }
        } else {
            Tree(model)
        }
        tree.isRootVisible = false
        tree.showsRootHandles = false

        val path = TreePath(group.path)
        tree.expandPath(path)
        assertTrue("el grupo tiene que empezar desplegado", tree.isExpanded(path))
        return tree to path
    }

    /**
     * `Tree.collapsePath` pliega recursivamente cuando `ide.tree.collapse.recursively`
     * está puesto —lo está de fábrica— y ese camino se salta los nodos «siempre
     * desplegados»: los de profundidad cero para `TreeUtil.getNodeDepth`, que descuenta
     * la raíz oculta y las manecillas apagadas. Nuestras cabeceras dan justo cero.
     *
     * Y no falla: se marcha en silencio dejando el grupo abierto.
     */
    @Test
    fun `la plataforma no pliega una cabecera sin manecillas`() {
        val (tree, path) = tree(withFix = false)

        tree.collapsePath(path)

        assertTrue("si esto falla, el collapsePath propio del panel ya no hace falta", tree.isExpanded(path))
    }

    @Test
    fun `con el collapsePath del panel la cabecera si se pliega`() {
        val (tree, path) = tree(withFix = true)

        tree.collapsePath(path)

        assertFalse(tree.isExpanded(path))
        // Sólo la cabecera queda a la vista: la tarea de dentro ya no cuenta como fila.
        assertEquals(1, tree.rowCount)
    }

    /** El pliegue se recuerda escuchando `treeCollapsed`, así que tiene que dispararse. */
    @Test
    fun `plegar avisa a los oyentes`() {
        val (tree, path) = tree(withFix = true)
        val collapsed = mutableListOf<TreePath>()
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = Unit
            override fun treeCollapsed(event: TreeExpansionEvent) {
                collapsed += event.path
            }
        })

        tree.collapsePath(path)

        assertEquals(listOf(path), collapsed)
    }

    /** `collapseRow` es lo que llama el clic en la cabecera, y pasa por el mismo sitio. */
    @Test
    fun `collapseRow tambien pliega`() {
        val (tree, path) = tree(withFix = true)

        tree.collapseRow(0)

        assertFalse(tree.isExpanded(path))
    }

    /** Y se puede volver a abrir: el gesto es un interruptor, no un billete de ida. */
    @Test
    fun `desplegar despues de plegar devuelve las filas`() {
        val (tree, path) = tree(withFix = true)

        tree.collapsePath(path)
        tree.expandPath(path)

        assertTrue(tree.isExpanded(path))
        assertEquals(2, tree.rowCount)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
