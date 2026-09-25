package com.tasklane.ui.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Reordenar arrastrando la tarjeta por su franja (2.11.0), en una pestaña con orden manual.
 *
 * **Por la franja y no por toda la tarjeta**, que es lo que parecería natural: arrastrar
 * sobre la tarjeta ya selecciona su texto —ver [CardTextSelection]—, y un mismo gesto no
 * puede significar dos cosas. La franja de prioridad es el borde izquierdo de todas las
 * tarjetas, no tiene nada que pulsar, y el cursor de mover la anuncia al pasar por encima.
 * El mismo cambio se hace con `⌘⇧↑/↓` desde el teclado.
 *
 * **Dentro de su grupo.** Soltar en otro grupo —otro día, otra etiqueta— no puede
 * significar «ponla ahí»: el grupo sale de la tarea, no de dónde está. Así que fuera del
 * grupo de origen no hay línea de soltar, y soltar no hace nada.
 *
 * Lo que se decide aquí son sólo las **vecinas**: qué tarea queda encima y cuál debajo.
 * El número que le toca lo pone el almacén, que es quien sabe si queda hueco.
 */
internal class CardReorder(
    private val tree: JTree,
    private val renderer: TaskTreeRenderer,
    private val onDrop: (task: Task, above: TaskId?, below: TaskId?) -> Unit,
) {

    private class Drop(val above: TaskId?, val below: TaskId?, val lineY: Int)

    private var dragged: TaskNode? = null
    private var drop: Drop? = null

    /** Si hay un arrastre en curso: mientras dura, el resto de gestos del ratón se callan. */
    val active: Boolean get() = dragged != null

    fun install() {
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
                if (!renderer.isOnHandle(tree, e.point)) return
                val row = rowAtHeight(tree, e.y)
                dragged = tree.getPathForRow(row)?.lastPathComponent as? TaskNode
            }

            override fun mouseDragged(e: MouseEvent) {
                val node = dragged ?: return
                val next = dropAt(node, e.y)
                if (next?.lineY != drop?.lineY) {
                    drop = next
                    tree.repaint()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                val node = dragged ?: return
                val target = drop
                dragged = null
                drop = null
                tree.repaint()
                if (target != null) onDrop(node.task, target.above, target.below)
            }
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)
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
