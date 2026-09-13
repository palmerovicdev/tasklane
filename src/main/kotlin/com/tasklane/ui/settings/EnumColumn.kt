package com.tasklane.ui.settings

import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.ColumnInfo
import javax.swing.DefaultCellEditor
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Columna de tabla con un desplegable de enum, mostrando el nombre traducible en vez
 * de la constante. Dos columnas de la tabla de estados —agrupación y anclaje— son
 * exactamente esto, así que se comparte.
 */
internal abstract class EnumColumn<Item : Any, E : Enum<E>>(
    title: String,
    private val values: Array<E>,
    private val label: (E) -> String,
    private val onChanged: () -> Unit,
) : ColumnInfo<Item, E>(title) {

    override fun getColumnClass(): Class<*> = values.first()::class.java

    override fun isCellEditable(item: Item): Boolean = true

    override fun getEditor(item: Item): TableCellEditor = DefaultCellEditor(
        JComboBox(values).apply {
            renderer = object : SimpleListCellRenderer<E>() {
                override fun customize(list: JList<out E>, value: E?, index: Int, selected: Boolean, focused: Boolean) {
                    text = value?.let(label).orEmpty()
                }
            }
        },
    )

    override fun getRenderer(item: Item): TableCellRenderer = object : DefaultTableCellRenderer() {
        override fun setValue(value: Any?) {
            @Suppress("UNCHECKED_CAST")
            text = (value as? E)?.let(label).orEmpty()
        }
    }

    final override fun setValue(item: Item, value: E) {
        write(item, value)
        onChanged()
    }

    protected abstract fun write(item: Item, value: E)
}
