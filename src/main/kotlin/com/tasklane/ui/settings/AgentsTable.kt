package com.tasklane.ui.settings

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.tasklane.TasklaneBundle
import com.tasklane.domain.agent.Agent
import javax.swing.JComponent
import javax.swing.JTable

/**
 * Los agentes de *Hand Off to Agent* (2.27.0): nombre y orden, uno por fila.
 *
 * El orden de la tabla es el del submenú, así que se reordena como la de prioridades —por el
 * asa o con las flechas, ver [RowReorder]—. Borrar no pregunta: un agente no tiene tareas que
 * reasignar, y *Cancel* lo devuelve.
 *
 * No avisa de cambios: la página de ajustes pregunta por [agents] cuando quiere saber si hay
 * algo que aplicar. Ver `agentGroup`.
 */
internal class AgentsTable {

    private class AgentRow(var name: String, var command: String)

    private val model = ListTableModel<AgentRow>(
        HandleColumn<AgentRow>(),
        TextColumn(
            TasklaneBundle.message("settings.column.name"),
            TasklaneBundle.message("settings.agent.name.tooltip"),
            NAME_WIDTH,
            { it.name },
            { row, value -> row.name = value },
        ),
        TextColumn(
            TasklaneBundle.message("settings.agent.column.command"),
            TasklaneBundle.message("settings.agent.command.tooltip"),
            null,
            { it.command },
            { row, value -> row.command = value },
        ),
    ).apply {
        // Como en las demás: los índices de vista y de modelo coinciden, que es de lo que
        // depende el reordenar a mano.
        isSortable = false
    }

    private val table = TableView(model).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        emptyText.text = TasklaneBundle.message("settings.agent.empty")
    }

    private val reorder = RowReorder(table, model)

    val component: JComponent = reorder.decorate(
        ToolbarDecorator.createDecorator(table)
            .setAddAction { addRow() }
            .setRemoveAction { removeRow() }
            .setPreferredSize(JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)),
    ).createPanel()

    init {
        reorder.install()
    }

    /**
     * Tal como están en la tabla, también las filas sin orden: las quita quien guarda. Lo que
     * se está escribiendo en una celda no cuenta hasta [commitEdit].
     */
    var agents: List<Agent>
        get() = model.items.map { Agent(it.name, it.command) }
        set(value) {
            model.items = value.mapTo(mutableListOf()) { AgentRow(it.name, it.command) }
        }

    /**
     * Da por buena la celda que se esté editando. Sólo al aplicar: [agents] se consulta a cada
     * rato para saber si hay cambios, y cerrar ahí la edición cortaría a quien escribe.
     */
    fun commitEdit() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    /** Abajo del todo, escribiendo ya en la orden: sin ella la fila no es nada. */
    private fun addRow() {
        model.addRow(AgentRow("", ""))
        val index = model.rowCount - 1
        table.selectionModel.setSelectionInterval(index, index)
        table.editCellAt(index, COMMAND)
        table.editorComponent?.requestFocusInWindow()
    }

    private fun removeRow() {
        val index = table.selectedRow.takeIf { it >= 0 } ?: return
        if (table.isEditing) table.cellEditor?.cancelCellEditing()
        model.removeRow(index)
        if (model.rowCount > 0) {
            val next = index.coerceAtMost(model.rowCount - 1)
            table.selectionModel.setSelectionInterval(next, next)
        }
    }

    private class TextColumn(
        title: String,
        private val tooltip: String,
        private val width: Int?,
        private val read: (AgentRow) -> String,
        private val write: (AgentRow, String) -> Unit,
    ) : ColumnInfo<AgentRow, String>(title) {
        override fun valueOf(item: AgentRow): String = read(item)
        override fun isCellEditable(item: AgentRow): Boolean = true
        override fun getTooltipText(): String = tooltip
        override fun getWidth(table: JTable): Int = width?.let(JBUI::scale) ?: -1
        override fun setValue(item: AgentRow, value: String) = write(item, value)
    }

    private companion object {
        const val COMMAND = 2
        const val NAME_WIDTH = 140
        const val PREFERRED_WIDTH = 480
        const val PREFERRED_HEIGHT = 120
    }
}
