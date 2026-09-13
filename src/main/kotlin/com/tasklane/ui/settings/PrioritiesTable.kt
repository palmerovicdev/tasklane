package com.tasklane.ui.settings

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.tasklane.TasklaneBundle
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Tabla de prioridades: nombre, cuál es la de por defecto, trigger y los dos colores.
 *
 * El orden de la lista **es** la prioridad: lo de abajo pesa más en la ordenación
 * del árbol. Por eso subir y bajar no son un adorno, y por eso no hay una columna
 * numérica que pudiera discrepar del orden visible.
 */
internal class PrioritiesTable(
    private val onChanged: () -> Unit,
    /** Devuelve `false` para cancelar el borrado. Ahí es donde vive la reasignación. */
    private val onRemove: (PriorityRow) -> Boolean,
) {

    private val colorRenderer = ColorCellRenderer()

    private val model = ListTableModel<PriorityRow>(
        NameColumn(),
        DefaultColumn(),
        TriggerColumn(),
        ColorColumn(
            TasklaneBundle.message("settings.column.colorLight"),
            { it.colorLight },
            { row, value -> row.colorLight = value },
        ),
        ColorColumn(
            TasklaneBundle.message("settings.column.colorDark"),
            { it.colorDark },
            { row, value -> row.colorDark = value },
        ),
    ).apply {
        // Sin ordenación por columna: los índices de vista y de modelo coinciden, que
        // es de lo que dependen el marcado de problemas y el reordenar a mano.
        isSortable = false
    }

    private val table = TableView(model).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
    }

    private var problemRows: Set<Int> = emptySet()
    private val problemRenderer = problemAwareRenderer { problemRows }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction { addRow() }
        .setRemoveAction { removeRow() }
        .setPreferredSize(JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT))
        .createPanel()

    init {
        model.addTableModelListener { onChanged() }
    }

    var rows: List<PriorityRow>
        get() = model.items.toList()
        set(value) {
            model.items = value.toMutableList()
        }

    fun markProblems(rows: Set<Int>) {
        problemRows = rows
        table.repaint()
    }

    private fun addRow() {
        val row = PriorityRow.fresh(TasklaneBundle.message("settings.priorities.new"))
        model.addRow(row)
        val index = model.rowCount - 1
        table.selectionModel.setSelectionInterval(index, index)
        table.editCellAt(index, 0)
        table.editorComponent?.requestFocusInWindow()
    }

    private fun removeRow() {
        val index = table.selectedRow.takeIf { it >= 0 } ?: return
        val row = model.getItem(index)
        if (!onRemove(row)) return
        model.removeRow(index)
    }

    // ------------------------------------------------------------- columnas

    private inner class NameColumn : ColumnInfo<PriorityRow, String>(
        TasklaneBundle.message("settings.column.name"),
    ) {
        override fun valueOf(item: PriorityRow): String = item.name
        override fun isCellEditable(item: PriorityRow): Boolean = true
        override fun getRenderer(item: PriorityRow) = problemRenderer
        override fun setValue(item: PriorityRow, value: String) {
            item.name = value
            onChanged()
        }
    }

    private inner class DefaultColumn : ColumnInfo<PriorityRow, Boolean>(
        TasklaneBundle.message("settings.column.default"),
    ) {
        override fun valueOf(item: PriorityRow): Boolean = item.isDefault
        override fun getColumnClass(): Class<*> = Boolean::class.javaObjectType
        override fun isCellEditable(item: PriorityRow): Boolean = true
        override fun getWidth(table: JTable): Int = JBUI.scale(CHECK_WIDTH)
        override fun setValue(item: PriorityRow, value: Boolean) {
            if (!value) return
            model.items.forEach { it.isDefault = it === item }
            model.fireTableDataChanged()
            onChanged()
        }
    }

    private inner class TriggerColumn : ColumnInfo<PriorityRow, String>(
        TasklaneBundle.message("settings.column.trigger"),
    ) {
        override fun valueOf(item: PriorityRow): String = item.trigger.orEmpty()
        override fun isCellEditable(item: PriorityRow): Boolean = true
        override fun getWidth(table: JTable): Int = JBUI.scale(TRIGGER_WIDTH)
        override fun getTooltipText(): String = TasklaneBundle.message("settings.column.trigger.tooltip")
        override fun getRenderer(item: PriorityRow) = problemRenderer
        override fun setValue(item: PriorityRow, value: String) {
            // Vacío es «sin trigger», no la cadena vacía: los triggers son opcionales
            // por prioridad y el parser no debe ver un prefijo de longitud cero.
            item.trigger = value.trim().takeIf(String::isNotEmpty)
            onChanged()
        }
    }

    private inner class ColorColumn(
        title: String,
        private val read: (PriorityRow) -> Int,
        private val write: (PriorityRow, Int) -> Unit,
    ) : ColumnInfo<PriorityRow, Int>(title) {
        override fun valueOf(item: PriorityRow): Int = read(item)
        override fun isCellEditable(item: PriorityRow): Boolean = true
        override fun getWidth(table: JTable): Int = JBUI.scale(COLOR_WIDTH)
        override fun getRenderer(item: PriorityRow): TableCellRenderer = colorRenderer
        override fun getEditor(item: PriorityRow): TableCellEditor = ColorCellEditor()
        override fun setValue(item: PriorityRow, value: Int) {
            write(item, value)
            onChanged()
        }
    }



    private companion object {
        const val CHECK_WIDTH = 70
        const val TRIGGER_WIDTH = 70
        const val COLOR_WIDTH = 80
        const val PREFERRED_WIDTH = 480
        const val PREFERRED_HEIGHT = 160
    }
}
