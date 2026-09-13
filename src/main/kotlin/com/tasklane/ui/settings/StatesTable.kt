package com.tasklane.ui.settings

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.Grouping
import javax.swing.JComponent
import javax.swing.JTable

/**
 * Tabla de estados: nombre, cuál es el destino por defecto, cuál cierra la tarea y
 * cómo agrupa.
 *
 * Añadir, quitar, subir y bajar los pone [ToolbarDecorator] sobre el `EditableModel`
 * que `ListTableModel` ya implementa; sólo se sustituyen alta y baja, porque ambas
 * necesitan decisiones que la tabla no puede tomar sola (un nombre, y qué hacer con
 * las tareas que se quedarían sin estado).
 */
internal class StatesTable(
    private val onChanged: () -> Unit,
    /** Devuelve `false` para cancelar el borrado. Ahí es donde vive la reasignación. */
    private val onRemove: (StateRow) -> Boolean,
) {

    private val model = ListTableModel<StateRow>(
        NameColumn(),
        DefaultColumn(),
        TerminalColumn(),
        GroupingColumn(),
        AnchorColumn(),
    ).apply {
        // Sin ordenación por columna: los índices de vista y de modelo coinciden, que
        // es de lo que dependen el marcado de problemas y el reordenar a mano.
        isSortable = false
    }

    private val table = TableView(model).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
    }

    /** Filas con un problema de validación; se pintan marcadas. */
    private var problemRows: Set<Int> = emptySet()
    private val problemRenderer = problemAwareRenderer { problemRows }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction { addRow() }
        .setRemoveAction { removeRow() }
        .setPreferredSize(JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT))
        .createPanel()

    init {
        // Un único punto de notificación: ediciones, altas, bajas y reordenaciones
        // pasan todas por el modelo.
        model.addTableModelListener { onChanged() }
    }

    var rows: List<StateRow>
        get() = model.items.toList()
        set(value) {
            model.items = value.toMutableList()
        }

    fun markProblems(rows: Set<Int>) {
        problemRows = rows
        table.repaint()
    }

    private fun addRow() {
        val row = StateRow.fresh(TasklaneBundle.message("settings.states.new"))
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

    private inner class NameColumn : ColumnInfo<StateRow, String>(
        TasklaneBundle.message("settings.column.name"),
    ) {
        override fun valueOf(item: StateRow): String = item.name
        override fun isCellEditable(item: StateRow): Boolean = true
        override fun setValue(item: StateRow, value: String) {
            item.name = value
            onChanged()
        }

        override fun getRenderer(item: StateRow) = problemRenderer
    }

    /**
     * Exactamente un estado por defecto: marcar uno desmarca el resto. Desmarcar el
     * activo no hace nada — dejar el proyecto sin destino para las tareas nuevas no
     * es un estado válido, así que no se ofrece como opción.
     */
    private inner class DefaultColumn : ColumnInfo<StateRow, Boolean>(
        TasklaneBundle.message("settings.column.default"),
    ) {
        override fun valueOf(item: StateRow): Boolean = item.isDefault
        override fun getColumnClass(): Class<*> = Boolean::class.javaObjectType
        override fun isCellEditable(item: StateRow): Boolean = true
        override fun getWidth(table: JTable): Int = JBUI.scale(CHECK_WIDTH)
        override fun setValue(item: StateRow, value: Boolean) {
            if (!value) return
            model.items.forEach { it.isDefault = it === item }
            model.fireTableDataChanged()
            onChanged()
        }
    }

    private inner class TerminalColumn : ColumnInfo<StateRow, Boolean>(
        TasklaneBundle.message("settings.column.terminal"),
    ) {
        override fun valueOf(item: StateRow): Boolean = item.terminal
        override fun getColumnClass(): Class<*> = Boolean::class.javaObjectType
        override fun isCellEditable(item: StateRow): Boolean = true
        override fun getWidth(table: JTable): Int = JBUI.scale(CHECK_WIDTH)
        override fun getTooltipText(): String = TasklaneBundle.message("settings.column.terminal.tooltip")
        override fun setValue(item: StateRow, value: Boolean) {
            item.terminal = value
            onChanged()
        }
    }

    private inner class GroupingColumn : EnumColumn<StateRow, Grouping>(
        TasklaneBundle.message("settings.column.grouping"),
        Grouping.entries.toTypedArray(),
        Grouping::label,
        onChanged,
    ) {
        override fun valueOf(item: StateRow): Grouping = item.grouping
        override fun write(item: StateRow, value: Grouping) {
            item.grouping = value
        }
    }

    private inner class AnchorColumn : EnumColumn<StateRow, DateAnchor>(
        TasklaneBundle.message("settings.column.anchor"),
        DateAnchor.entries.toTypedArray(),
        DateAnchor::label,
        onChanged,
    ) {
        override fun valueOf(item: StateRow): DateAnchor = item.anchor
        override fun isCellEditable(item: StateRow): Boolean = item.grouping == Grouping.BY_DATE
        override fun write(item: StateRow, value: DateAnchor) {
            item.anchor = value
        }
    }

    private companion object {
        const val CHECK_WIDTH = 70
        const val PREFERRED_WIDTH = 480
        const val PREFERRED_HEIGHT = 160
    }
}
