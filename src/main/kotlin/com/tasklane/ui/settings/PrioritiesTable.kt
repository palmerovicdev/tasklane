package com.tasklane.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.ui.ColorChooserService
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.tasklane.TasklaneBundle
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.TableCellRenderer

/**
 * Tabla de prioridades: nombre, cuál es la de por defecto, trigger y los dos colores.
 *
 * El orden de la lista **es** la prioridad. Por eso las filas se arrastran por su asa y
 * se suben y bajan con flechas —ver [RowReorder]—, y por eso no hay una columna numérica
 * que pudiera discrepar del orden visible.
 *
 * **La más alta arriba** (2.3.0), que es como sale en la ventana: agrupando por
 * prioridad, en el menú *Priority* y en la lista que abre el distintivo. Hasta la 2.2 la
 * tabla iba en el orden de la configuración —la más baja primero, «lo de abajo pesa
 * más»— y se leía al revés que todo lo demás. La configuración no cambia: [rows] da la
 * vuelta al entrar y al salir, y las filas marcadas por la validación, que llegan en
 * índices de la configuración, también.
 */
internal class PrioritiesTable(
    private val project: Project,
    private val onChanged: () -> Unit,
    /** Devuelve `false` para cancelar el borrado. Ahí es donde vive la reasignación. */
    private val onRemove: (PriorityRow) -> Boolean,
) {

    private val colorRenderer = ColorCellRenderer()

    private val model = ListTableModel<PriorityRow>(
        HandleColumn<PriorityRow>(),
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

    private val reorder = RowReorder(table, model)

    private var problemRows: Set<Int> = emptySet()
    private val problemRenderer = problemAwareRenderer { problemRows }

    val component: JComponent = reorder.decorate(
        ToolbarDecorator.createDecorator(table)
            .setAddAction { addRow() }
            .setRemoveAction { removeRow() }
            .setPreferredSize(JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)),
    ).createPanel()

    init {
        model.addTableModelListener { onChanged() }
        reorder.install()
        installColorPicker()
    }

    /** En el orden de la configuración: de la más baja a la más alta. */
    var rows: List<PriorityRow>
        get() = model.items.asReversed().toList()
        set(value) {
            model.items = value.asReversed().toMutableList()
        }

    /** [rows] llega en índices de la configuración, como la devuelve la validación. */
    fun markProblems(rows: Set<Int>) {
        val last = model.rowCount - 1
        problemRows = rows.mapTo(HashSet()) { last - it }
        table.repaint()
    }

    /**
     * Una prioridad nueva entra abajo del todo, como en cualquier tabla, y abajo es la
     * más baja. Hasta la 2.2 abajo era la más alta: con la tabla del revés, añadir una
     * la colaba por encima de todas. Desde ahí se arrastra adonde toque.
     */
    private fun addRow() {
        val row = PriorityRow.fresh(TasklaneBundle.message("settings.priorities.new"))
        model.addRow(row)
        val index = model.rowCount - 1
        table.selectionModel.setSelectionInterval(index, index)
        table.editCellAt(index, NAME)
        table.editorComponent?.requestFocusInWindow()
    }

    private fun removeRow() {
        val index = table.selectedRow.takeIf { it >= 0 } ?: return
        val row = model.getItem(index)
        if (!onRemove(row)) return
        model.removeRow(index)
    }

    // ------------------------------------------------------------- color

    /**
     * Los colores se cambian **pulsando la muestra**, que abre el selector de la
     * plataforma junto a la celda.
     *
     * Hasta la 2.2 la muestra era un editor de celda —un `ColorPanel`— y elegir un color
     * no hacía nada. El `ColorPanel` abre su selector como un *popup* asíncrono; el popup
     * se lleva el foco, y una `JBTable` da la edición por terminada al perderlo
     * (`terminateEditOnFocusLost`): guardaba el color **viejo** y quitaba el editor antes
     * de que se eligiera nada. El color nuevo llegaba después a un panel que ya no estaba
     * en la tabla. Sin editor no hay edición que se pueda cortar: el color se escribe en
     * la fila en cuanto el selector lo da.
     *
     * La fila se busca otra vez al escribir, por identidad, en vez de guardarse el
     * índice: con el selector abierto se puede arrastrar otra fila por encima.
     */
    private fun installColorPicker() {
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                val row = table.rowAtPoint(e.point)
                val column = table.columnAtPoint(e.point)
                val info = colorColumnAt(column) ?: return
                if (row < 0) return
                pickColor(model.getItem(row), row, column, info)
            }

            override fun mouseMoved(e: MouseEvent) {
                if (table.columnAtPoint(e.point) == RowReorder.HANDLE) return
                val onColor = colorColumnAt(table.columnAtPoint(e.point)) != null && table.rowAtPoint(e.point) >= 0
                table.cursor = if (onColor) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        }
        table.addMouseListener(mouse)
        table.addMouseMotionListener(mouse)
    }

    private fun colorColumnAt(column: Int): ColorColumn? =
        model.columnInfos.getOrNull(column) as? ColorColumn

    private fun pickColor(item: PriorityRow, row: Int, column: Int, info: ColorColumn) {
        val cell = table.getCellRect(row, column, false)
        ColorChooserService.getInstance().showPopup(
            project,
            Color(info.valueOf(item)),
            { color, _ ->
                val index = model.items.indexOfFirst { it === item }
                if (color != null && index >= 0) {
                    info.write(item, color.rgb and RGB_MASK)
                    model.fireTableCellUpdated(index, column)
                }
            },
            RelativePoint(table, Point(cell.x, cell.y + cell.height)),
            false,
        )
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

    /** No editable a propósito: se cambia con un clic. Ver [installColorPicker]. */
    private inner class ColorColumn(
        title: String,
        private val read: (PriorityRow) -> Int,
        val write: (PriorityRow, Int) -> Unit,
    ) : ColumnInfo<PriorityRow, Int>(title) {
        override fun valueOf(item: PriorityRow): Int = read(item)
        override fun isCellEditable(item: PriorityRow): Boolean = false
        override fun getWidth(table: JTable): Int = JBUI.scale(COLOR_WIDTH)
        override fun getRenderer(item: PriorityRow): TableCellRenderer = colorRenderer
        override fun getTooltipText(): String = TasklaneBundle.message("settings.column.color.tooltip")
    }

    private companion object {
        /** La columna del nombre, detrás del asa. */
        const val NAME = 1
        const val CHECK_WIDTH = 70
        const val TRIGGER_WIDTH = 70
        const val COLOR_WIDTH = 80
        const val PREFERRED_WIDTH = 480
        const val PREFERRED_HEIGHT = 160
        const val RGB_MASK = 0xFFFFFF
    }
}
