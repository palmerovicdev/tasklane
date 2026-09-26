package com.tasklane.ui.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Reordenar arrastrando la tarjeta por su asa (2.11.0), en una pestaña con orden manual.
 *
 * **Por el asa y no por toda la tarjeta**, que es lo que parecería natural: arrastrar
 * sobre la tarjeta ya selecciona su texto —ver [CardTextSelection]—, y un mismo gesto no
 * puede significar dos cosas. El asa (⋮⋮) vive abajo a la derecha, en el hueco que la
 * columna de controles deja libre, y sale bajo el ratón como el menú (2.11.1; en la 2.11.0
 * era la franja de prioridad, ocho píxeles a los que costaba atinar). El mismo cambio se
 * hace con `⌘⇧↑/↓` desde el teclado.
 *
 * **Dentro de su grupo.** Soltar en otro grupo —otro día, otra etiqueta— no puede
 * significar «ponla ahí»: el grupo sale de la tarea, no de dónde está. Así que fuera del
 * grupo de origen no hay línea de soltar, y soltar no hace nada.
 *
 * Lo que se decide aquí son sólo las **vecinas**: qué tarea queda encima y cuál debajo.
 * El número que le toca lo pone el almacén, que es quien sabe si queda hueco.
 *
 * **En el tablero el asa lleva además a otra columna** (2.17.0), vaya o no ésta a mano: en
 * cuanto el ratón sale a otra columna, lo que se pinta y lo que pasa al soltar es cosa de
 * ella. Ver [CardCarry].
 */
internal class CardReorder(
    private val tree: JTree,
    private val renderer: TaskTreeRenderer,
    /** Adónde más puede ir la tarjeta: en el tablero, a otra columna (2.17.0). */
    private val carry: CardCarry? = null,
    /** La selección de antes de pulsar el asa, que el `TreeUI` ya ha podido cambiar. Ver [carried]. */
    private val selectionAtPress: () -> List<TreePath> = { tree.selectionPaths.orEmpty().toList() },
    private val onDrop: (task: Task, above: TaskId?, below: TaskId?) -> Unit,
) {

    private class Drop(val above: TaskId?, val below: TaskId?, val lineY: Int)

    private var dragged: TaskNode? = null
    private var drop: Drop? = null

    /**
     * Lo que se lleva a otra columna: la selección entera si la tarjeta del asa es parte de
     * una de varias, y si no, ella sola. Reordenar dentro de la columna mueve siempre sólo
     * la del asa, como hasta ahora.
     */
    private var carried: List<Task> = emptyList()

    /** Si hay un arrastre en curso: mientras dura, el resto de gestos del ratón se callan. */
    val active: Boolean get() = dragged != null

    fun install() {
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
                if (!renderer.isOnHandle(tree, e.point)) return
                val row = rowAtHeight(tree, e.y)
                val node = tree.getPathForRow(row)?.lastPathComponent as? TaskNode
                dragged = node
                carried = node?.let(::carriedWith).orEmpty()
            }

            override fun mouseDragged(e: MouseEvent) {
                val node = dragged ?: return
                // Sobre otra columna manda ella: la línea de aquí se borra, y la de allí
                // la pinta la otra.
                val away = carry?.over(carried, e.locationOnScreen) == true
                val next = if (away || !renderer.reorderable) null else dropAt(node, e.y)
                if (next?.lineY != drop?.lineY) {
                    drop = next
                    tree.repaint()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                val node = dragged ?: return
                val target = drop
                val tasks = carried
                dragged = null
                drop = null
                carried = emptyList()
                tree.repaint()
                if (carry?.drop(tasks, e.locationOnScreen) == true) return
                if (target != null) onDrop(node.task, target.above, target.below)
            }
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)
    }

    /**
     * Ver [carried]. En el orden en que se ven, que es en el que llegarán a la otra columna.
     * Si al pulsar se quedó reducida a la tarjeta del asa, se devuelve: lo que se ve
     * seleccionado tiene que ser lo que se lleva.
     */
    private fun carriedWith(node: TaskNode): List<Task> {
        if (carry == null) return listOf(node.task)
        val before = selectionAtPress()
        val selected = before.mapNotNull { it.lastPathComponent as? TaskNode }
        if (node !in selected || selected.size < 2) return listOf(node.task)
        if (tree.selectionPaths.orEmpty().toList() != before) tree.selectionPaths = before.toTypedArray()
        return selected.sortedBy { tree.getRowForPath(TreePath(it.path)) }.map { it.task }
    }

    /** La línea de soltar, encima de todo. La llama el `paint` del árbol. */
    fun paint(g: Graphics) {
        val target = drop ?: return
        val g2 = g.create()
        try {
            g2.color = JBColor.namedColor("DragAndDrop.borderColor", JBColor.BLUE)
            g2.fillRect(0, target.lineY - JBUI.scale(1), tree.width, JBUI.scale(2))
        } finally {
            g2.dispose()
        }
    }

    /**
     * Dónde caería [node] soltándola a la altura [y]: entre qué dos tareas de su grupo, y
     * dónde pintar la línea. `null` fuera del grupo o encima de ella misma, que no mueve nada.
     */
    private fun dropAt(node: TaskNode, y: Int): Drop? {
        val row = rowAtHeight(tree, y)
        if (row < 0) return null
        val over = tree.getPathForRow(row)?.lastPathComponent as? TaskNode ?: return null
        if (over.parent !== node.parent) return null
        val bounds: Rectangle = tree.getRowBounds(row) ?: return null
        val after = y >= bounds.y + bounds.height / 2

        val siblings = tasksOf(node.parent as? DefaultMutableTreeNode ?: return null)
        val others = siblings.filter { it !== node }
        val at = others.indexOf(over).let { if (it < 0) return null else if (after) it + 1 else it }
        val above = others.getOrNull(at - 1)
        val below = others.getOrNull(at)
        // Soltarla justo donde está no la mueve: sin línea, para que se note.
        val index = siblings.indexOf(node)
        if (siblings.getOrNull(index - 1) === above && siblings.getOrNull(index + 1) === below) return null
        return Drop(above?.task?.id, below?.task?.id, if (after) bounds.y + bounds.height else bounds.y)
    }

    companion object {
        /** Las tareas de un grupo —o de la raíz—, en el orden en que se ven, sin cabeceras ni centinelas. */
        fun tasksOf(parent: DefaultMutableTreeNode): List<TaskNode> =
            (0 until parent.childCount).mapNotNull { parent.getChildAt(it) as? TaskNode }
    }
}

/**
 * Adónde más puede ir una tarjeta que se arrastra por el asa (2.17.0): en el tablero, a
 * otra columna. Lo pone cada columna; en la tool window no hay, y el asa sólo reordena.
 */
internal interface CardCarry {

    /**
     * El ratón, en coordenadas de pantalla, llevando [tasks]. `true` si está sobre **otra**
     * columna, que es entonces quien pinta dónde caerían; `false` en cualquier otro sitio, y
     * entonces no queda nada pintado fuera de la columna de origen.
     */
    fun over(tasks: List<Task>, screen: Point): Boolean

    /** Soltarlas ahí. `true` si las recogió otra columna. Borra lo pintado en cualquier caso. */
    fun drop(tasks: List<Task>, screen: Point): Boolean
}
