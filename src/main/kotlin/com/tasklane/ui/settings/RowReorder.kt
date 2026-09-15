package com.tasklane.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.tasklane.TasklaneBundle
import java.awt.Component
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.TableCellRenderer

/**
 * Reordenar las filas de una tabla de los ajustes **arrastrándolas por su asa**, y con
 * las flechas de la barra de siempre.
 *
 * En estas tablas el orden no es un adorno: en la de prioridades **es** la prioridad
 * —lo de abajo pesa más— y en la de estados es el orden de las pestañas. Hasta la 2.2 la
 * única forma de cambiarlo eran las flechas ↑↓ de la barra, que nadie asocia con «esto
 * se ordena»; lo primero que se intenta con una lista así es cogerla y moverla (2.3.0).
 *
 * ## Por qué un arrastre propio y no el de la plataforma
 *
 * `ToolbarDecorator` instala `RowsDnDSupport` cuando la tabla tiene flechas, y ése va por
 * el *drag and drop* de AWT: la fila no se mueve hasta soltarla, se inicia desde
 * cualquier celda —también desde la casilla, que en estas tablas se marca al pulsar— y
 * no dice en ningún sitio que se puede. Aquí:
 *
 * - **Se coge por el asa** de la primera columna, con el cursor de mover encima. Es lo
 *   que lo hace descubrible y lo que evita que un clic en otra celda empiece un arrastre.
 * - **La fila se mueve mientras se arrastra**, una posición cada vez, así que lo que se
 *   ve es el orden que quedará.
 * - Las flechas siguen ahí, con sus atajos, pero son **estas** y no las del decorador:
 *   con las del decorador vendría también su arrastre, y dos arrastres sobre la misma
 *   tabla se pisan. Ver [decorate].
 *
 * Todo pasa por [move], que intercambia filas adyacentes del modelo: la tabla notifica
 * cada paso por el oyente de siempre, y quien valida y marca los ajustes como
 * modificados no se entera de que hubo un arrastre.
 */
internal class RowReorder<T>(private val table: JTable, private val model: ListTableModel<T>) {

    /** La fila que se está arrastrando, o `-1`. */
    private var dragged = -1

    /**
     * Mueve la fila [from] a la posición [to], empujando las de en medio, y la deja
     * seleccionada. Fuera de rango no hace nada.
     */
    fun move(from: Int, to: Int) {
        val rows = model.rowCount
        if (from == to || from !in 0 until rows || to !in 0 until rows) return
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        val step = if (to > from) 1 else -1
        var at = from
        while (at != to) {
            model.exchangeRows(at, at + step)
            at += step
        }
        table.selectionModel.setSelectionInterval(to, to)
        table.scrollRectToVisible(table.getCellRect(to, 0, true))
    }

    /** Mueve la fila seleccionada [delta] posiciones. Es lo que hacen las flechas. */
    fun moveSelection(delta: Int) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        move(row, (row + delta).coerceIn(0, model.rowCount - 1))
    }

    /** Engancha el arrastre a la tabla. */
    fun install() {
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragged = if (SwingUtilities.isLeftMouseButton(e) && onHandle(e.point)) table.rowAtPoint(e.point) else -1
            }

            override fun mouseDragged(e: MouseEvent) {
                if (dragged < 0) return
                val target = rowAt(e.point)
                if (target == dragged) {
                    // La plataforma ya ha movido la selección detrás del ratón; se
                    // devuelve a la fila que se arrastra.
                    table.selectionModel.setSelectionInterval(dragged, dragged)
                    return
                }
                move(dragged, target)
                dragged = target
            }

            override fun mouseReleased(e: MouseEvent) {
                dragged = -1
            }

            override fun mouseMoved(e: MouseEvent) {
                table.cursor = if (onHandle(e.point)) MOVE else Cursor.getDefaultCursor()
            }

            override fun mouseExited(e: MouseEvent) {
                if (dragged < 0) table.cursor = Cursor.getDefaultCursor()
            }
        }
        table.addMouseListener(mouse)
        table.addMouseMotionListener(mouse)
    }

    /**
     * La fila bajo [point], **pegada a los extremos**: arrastrar por encima de la
     * primera la deja la primera, y por debajo de la última, la última.
     */
    private fun rowAt(point: Point): Int {
        val row = table.rowAtPoint(point)
        return when {
            row >= 0 -> row
            point.y < 0 -> 0
            else -> model.rowCount - 1
        }
    }

    private fun onHandle(point: Point): Boolean =
        table.columnAtPoint(point) == HANDLE && table.rowAtPoint(point) >= 0

    /**
     * Pone la tabla dentro de su barra con **estas** flechas en vez de las del decorador.
     *
     * `disableUpDownActions` es lo único que impide que el decorador instale su propio
     * arrastre: lo hace siempre que haya flechas suyas. Las de aquí llevan el mismo icono
     * y el mismo atajo, así que desde fuera la barra no cambia.
     */
    fun decorate(decorator: ToolbarDecorator): ToolbarDecorator =
        decorator.disableUpDownActions().addExtraActions(arrow(-1), arrow(1))

    private fun arrow(delta: Int): AnAction {
        val up = delta < 0
        val action = object : DumbAwareAction(
            TasklaneBundle.message(if (up) "settings.row.up" else "settings.row.down"),
            null,
            if (up) AllIcons.Actions.MoveUp else AllIcons.Actions.MoveDown,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                val row = table.selectedRow
                e.presentation.isEnabled = row >= 0 && if (up) row > 0 else row < model.rowCount - 1
            }

            override fun actionPerformed(e: AnActionEvent) = moveSelection(delta)
        }
        action.registerCustomShortcutSet(if (up) CommonShortcuts.MOVE_UP else CommonShortcuts.MOVE_DOWN, table)
        return action
    }

    companion object {
        /** El asa va en la primera columna. */
        const val HANDLE = 0

        private val MOVE: Cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    }
}

/**
 * La columna del asa: el icono de los seis puntos y nada más. No se edita ni se ordena,
 * y su tooltip es la única explicación que hace falta.
 */
internal class HandleColumn<T> : ColumnInfo<T, Unit>("") {

    private val renderer = HandleRenderer()

    override fun valueOf(item: T) = Unit
    override fun isCellEditable(item: T): Boolean = false
    override fun getWidth(table: JTable): Int = JBUI.scale(WIDTH)
    override fun getRenderer(item: T): TableCellRenderer = renderer
    override fun getTooltipText(): String = TasklaneBundle.message("settings.row.drag")

    private class HandleRenderer : TableCellRenderer {
        private val label = JBLabel(AllIcons.General.Drag).apply {
            horizontalAlignment = SwingConstants.CENTER
            isOpaque = true
            toolTipText = TasklaneBundle.message("settings.row.drag")
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component = label.apply {
            background = if (isSelected) table.selectionBackground else table.background
        }
    }

    private companion object {
        const val WIDTH = 24
    }
}
