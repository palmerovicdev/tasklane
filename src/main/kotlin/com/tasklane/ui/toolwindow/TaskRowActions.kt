package com.tasklane.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.util.ui.JBUI
import com.tasklane.domain.model.Task
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree

/**
 * Los controles de la derecha de cada fila: desplegar, el marcador y el menú `⋮`.
 *
 * Sólo aparecen en la fila que tiene el ratón encima, así que esto mantiene además
 * cuál es —`hoveredRow` del renderer— y repinta las dos filas implicadas en cada
 * cambio. Repintar dos filas y no el árbol entero importa: `mouseMoved` se dispara
 * decenas de veces por segundo.
 *
 * El marcador se pulsa **sin** abrir el menú: es la acción que se repite, y meterla
 * dentro de un desplegable la convertiría en dos clics. Lo mismo el desplegable de la
 * tarjeta, que además tiene que poder pulsarse una y otra vez sin perder el sitio. El
 * menú reutiliza el grupo `Tasklane.ContextMenu` del `plugin.xml`, el mismo del clic
 * derecho: una sola lista de acciones que mantener.
 */
internal object TaskRowActions {

    fun install(tree: JTree, renderer: TaskTreeRenderer, panel: TasklanePanel) {
        val mouse = object : MouseAdapter() {

            override fun mouseMoved(e: MouseEvent) {
                hover(rowAtHeight(tree, e.y))
                // Mano sobre los controles. Se pregunta sólo cerca del borde derecho:
                // resolver el control exige preparar y medir la fila, y esto corre en
                // cada píxel que recorre el ratón.
                if (nearActions(tree, e) && renderer.targetAt(tree, e.point) != null) {
                    tree.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
            }

            override fun mouseExited(e: MouseEvent) = hover(-1)

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1 || e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
                when (renderer.targetAt(tree, e.point)) {
                    TaskTreeRenderer.RowTarget.EXPAND -> {
                        e.consume()
                        taskAt(tree, e.point)?.let(panel::toggleExpanded)
                    }

                    TaskTreeRenderer.RowTarget.BOOKMARK -> {
                        e.consume()
                        taskAt(tree, e.point)?.let(panel::toggleBookmark)
                    }

                    TaskTreeRenderer.RowTarget.MENU -> {
                        e.consume()
                        showMenu(tree, e.point)
                    }

                    // El asa no se pulsa: se arrastra, y eso lo atiende `CardReorder`.
                    TaskTreeRenderer.RowTarget.GRIP -> e.consume()

                    null -> Unit
                }
            }

            private fun hover(row: Int) {
                if (renderer.hoveredRow == row) return
                val previous = renderer.hoveredRow
                renderer.hoveredRow = row
                repaintRow(tree, previous)
                repaintRow(tree, row)
            }
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)
    }

    /** Si el ratón está en la banda derecha de la fila, que es donde viven los controles. */
    private fun nearActions(tree: JTree, e: MouseEvent): Boolean {
        val row = rowAtHeight(tree, e.y)
        if (row < 0) return false
        val bounds = paintedRowBounds(tree, row) ?: return false
        return e.x > bounds.x + bounds.width - JBUI.scale(BAND)
    }

    private fun taskAt(tree: JTree, point: Point): Task? {
        val row = rowAtHeight(tree, point.y)
        if (row < 0) return null
        return (tree.getPathForRow(row)?.lastPathComponent as? TaskNode)?.task
    }

    /**
     * El menú actúa sobre la selección, así que la fila del `⋮` entra en ella antes
     * de abrirlo. Si ya estaba, la selección se respeta tal cual: pulsar el menú de
     * una de varias filas seleccionadas no debe reducirlas a una.
     */
    private fun showMenu(tree: JTree, point: Point) {
        val row = rowAtHeight(tree, point.y)
        if (row < 0) return
        if (!tree.isRowSelected(row)) tree.selectionRows = intArrayOf(row)

        val group = ActionManager.getInstance().getAction(CONTEXT_MENU) as? ActionGroup ?: return
        val menu = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.TOOLWINDOW_POPUP, group)
        menu.setTargetComponent(tree)
        menu.component.show(tree, point.x, point.y)
    }

    /** El ancho entero: la fila del ratón y la anterior cambian de aspecto, no de tamaño. */
    private fun repaintRow(tree: JTree, row: Int) {
        if (row < 0) return
        val bounds = tree.getRowBounds(row) ?: return
        tree.repaint(0, bounds.y, tree.width, bounds.height)
    }

    private const val CONTEXT_MENU = "Tasklane.ContextMenu"

    /** Ancho de la banda derecha donde puede haber un control. */
    private const val BAND = 64
}
