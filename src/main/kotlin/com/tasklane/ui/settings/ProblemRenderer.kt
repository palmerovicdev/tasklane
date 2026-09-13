package com.tasklane.ui.settings

import com.intellij.ui.JBColor
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Renderer que tiñe la celda cuando su fila tiene un problema de validación.
 *
 * Marcar la fila exacta —y no sólo enseñar un texto debajo de la tabla— es lo que
 * hace utilizable el aviso cuando hay ocho prioridades y dos comparten trigger.
 *
 * Los índices son del modelo: estas tablas no son ordenables, así que coinciden con
 * los de la vista. Se recibe un proveedor y no un conjunto para que revalidar no
 * obligue a reconstruir los renderers.
 */
internal fun problemAwareRenderer(problems: () -> Set<Int>): TableCellRenderer =
    object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            foreground = when {
                isSelected -> table.selectionForeground
                row in problems() -> JBColor.RED
                else -> table.foreground
            }
            return this
        }
    }
