package com.tasklane.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorChooserService
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.TagColor
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Tabla de etiquetas del repositorio activo (P30): nombre, cuántas tareas la llevan y los
 * dos colores, que son opcionales.
 *
 * Se parece a la de prioridades a propósito —se renombra en la celda, se borra con `-`
 * y borrar pregunta adónde van sus tareas—, pero tiene tres diferencias que vienen de
 * que una etiqueta no existe fuera de sus tareas:
 *
 * - **No hay `+`.** Una etiqueta sin tareas no es nada; nacen al escribirlas.
 * - **Renombrar a un nombre que ya está fusiona**: las dos acaban en una al aplicar.
 * - **Sin orden a mano.** Salen las más usadas primero, que es como se sugieren.
 *
 * Los colores se cambian pulsando la celda, como en la de prioridades —el porqué está en
 * `PrioritiesTable.installColorPicker`—, y se quitan con el botón de la barra.
 */
internal class TagsTable(
    private val project: Project,
    private val onChanged: () -> Unit,
    /** Devuelve `false` para cancelar el borrado. Ahí es donde se elige adónde van sus tareas. */
    private val onRemove: (TagRow) -> Boolean,
) {

    private val model = ListTableModel<TagRow>(
        NameColumn(),
        TasksColumn(),
        ColorColumn(TasklaneBundle.message("settings.column.colorLight"), TagColor::light) { color, rgb ->
            TagColor(rgb, color?.dark ?: rgb)
        },
        ColorColumn(TasklaneBundle.message("settings.column.colorDark"), TagColor::dark) { color, rgb ->
            TagColor(color?.light ?: rgb, rgb)
        },
    ).apply {
        // Como en la de prioridades: los índices de vista y de modelo coinciden, que es de
        // lo que depende el marcado de problemas.
        isSortable = false
    }

    private val table = TableView(model).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        emptyText.text = TasklaneBundle.message("settings.tags.empty")
    }

    private var problemRows: Set<Int> = emptySet()
    private val problemRenderer = problemAwareRenderer { problemRows }
    private val colorRenderer = TagColorRenderer()

    private val clearColor = object : DumbAwareAction(
        TasklaneBundle.message("settings.tags.clearColor"),
        null,
        AllIcons.Actions.Cancel,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected()?.color != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val row = selected() ?: return
            paint(row, null)
        }
    }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .disableAddAction()
        .disableUpDownActions()
        .setRemoveAction { removeRow() }
        .addExtraAction(clearColor)
        .setPreferredSize(JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT))
        .createPanel()

    init {
        model.addTableModelListener { onChanged() }
        installColorPicker()
    }

    var rows: List<TagRow>
        get() = model.items.toList()
        set(value) {
            model.items = value.toMutableList()
        }

    fun markProblems(rows: Set<Int>) {
        problemRows = rows
        table.repaint()
    }

    private fun selected(): TagRow? = table.selectedRow.takeIf { it >= 0 }?.let(model::getItem)

    private fun removeRow() {
        val index = table.selectedRow.takeIf { it >= 0 } ?: return
        val row = model.getItem(index)
        if (!onRemove(row)) return
        model.removeRow(index)
    }

    /**
     * Pone [color] en [row] y en las filas que son **la misma etiqueta** escrita de otra
     * forma —`API` y `api`—: el color se guarda sin mirar mayúsculas, y si cada fila
     * enseñara uno, el que se guarde dependería del orden de la tabla.
     */
    private fun paint(row: TagRow, color: TagColor?) {
        val key = TagColor.key(TagEdits.clean(row.name) ?: row.name)
        model.items.forEachIndexed { index, other ->
            if (other === row || TagColor.key(TagEdits.clean(other.name) ?: other.name) == key) {
                other.color = color
                other.colorCleared = color == null
                model.fireTableRowsUpdated(index, index)
            }
        }
    }

    // ------------------------------------------------------------- color

    private fun installColorPicker() {
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                val row = table.rowAtPoint(e.point)
                val column = table.columnAtPoint(e.point)
                val info = model.columnInfos.getOrNull(column) as? ColorColumn ?: return
                if (row < 0) return
                pickColor(model.getItem(row), row, column, info)
            }

            override fun mouseMoved(e: MouseEvent) {
                val onColor = model.columnInfos.getOrNull(table.columnAtPoint(e.point)) is ColorColumn &&
                    table.rowAtPoint(e.point) >= 0
                table.cursor = if (onColor) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        }
        table.addMouseListener(mouse)
        table.addMouseMotionListener(mouse)
    }

    /**
     * Sin color todavía, el selector arranca en gris, y el primero que se elige vale para
     * los dos temas hasta que se cambie el otro: una etiqueta a medio pintar no existe.
     */
    private fun pickColor(item: TagRow, row: Int, column: Int, info: ColorColumn) {
        val cell = table.getCellRect(row, column, false)
        ColorChooserService.getInstance().showPopup(
            project,
            Color(item.color?.let(info.read) ?: DEFAULT_RGB),
            { color, _ ->
                if (color != null && model.items.any { it === item }) paint(item, info.write(item.color, color.rgb and RGB_MASK))
            },
            RelativePoint(table, Point(cell.x, cell.y + cell.height)),
            false,
        )
    }

    // ------------------------------------------------------------- columnas

    private inner class NameColumn : ColumnInfo<TagRow, String>(TasklaneBundle.message("settings.column.name")) {
        override fun valueOf(item: TagRow): String = item.name
        override fun isCellEditable(item: TagRow): Boolean = true
        override fun getRenderer(item: TagRow) = problemRenderer
        override fun getTooltipText(): String = TasklaneBundle.message("settings.tags.name.tooltip")
        override fun setValue(item: TagRow, value: String) {
            item.name = value
            onChanged()
        }
    }

    /** Cuántas la llevan **hoy**: renombrar o fusionar no la cambia hasta aplicar. */
    private class TasksColumn : ColumnInfo<TagRow, Int>(TasklaneBundle.message("settings.column.tasks")) {
        private val renderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }

        override fun valueOf(item: TagRow): Int = item.tasks
        override fun getWidth(table: JTable): Int = JBUI.scale(TASKS_WIDTH)
        override fun getRenderer(item: TagRow): TableCellRenderer = renderer
    }

    /** No editable a propósito: se cambia con un clic. Ver [installColorPicker]. */
    private inner class ColorColumn(
        title: String,
        val read: (TagColor) -> Int,
        /** El color de la fila con este tono cambiado. */
        val write: (TagColor?, Int) -> TagColor,
    ) : ColumnInfo<TagRow, Int?>(title) {
        override fun valueOf(item: TagRow): Int? = item.color?.let(read)
        override fun isCellEditable(item: TagRow): Boolean = false
        override fun getWidth(table: JTable): Int = JBUI.scale(COLOR_WIDTH)
        override fun getRenderer(item: TagRow): TableCellRenderer = colorRenderer
        override fun getTooltipText(): String = TasklaneBundle.message("settings.column.color.tooltip")
    }

    /** La muestra, o «None» en gris: la mayoría de etiquetas no llevan color. */
    private class TagColorRenderer : TableCellRenderer {
        private val swatch = ColorCellRenderer()
        private val none = SimpleColoredComponent().apply { isOpaque = true }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            if (value != null) return swatch.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            none.clear()
            none.background = if (isSelected) table.selectionBackground else table.background
            none.append(TasklaneBundle.message("settings.tags.noColor"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return none
        }
    }

    private companion object {
        const val TASKS_WIDTH = 60
        const val COLOR_WIDTH = 80
        const val PREFERRED_WIDTH = 480
        const val PREFERRED_HEIGHT = 140
        const val RGB_MASK = 0xFFFFFF
        const val DEFAULT_RGB = 0x808080
    }
}
