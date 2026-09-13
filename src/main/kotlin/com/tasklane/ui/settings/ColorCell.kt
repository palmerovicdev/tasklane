package com.tasklane.ui.settings

import com.intellij.ui.ColorPanel
import java.awt.Color
import java.awt.Component
import javax.swing.AbstractCellEditor
import javax.swing.JTable
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Celda de color, con [ColorPanel] —el selector estándar de la plataforma— en vez de
 * un campo de texto hexadecimal.
 *
 * Hay dos columnas, clara y oscura, y no una sola: el modelo guarda ambos valores
 * para que `JBColor` resuelva el tema sin que el plugin se entere de cuál está
 * activo. Elegir un único color obligaría a derivar el otro, y ningún cálculo
 * automático acierta con los dos contrastes a la vez.
 *
 * El valor de la celda es el RGB como `Int`, igual que en el modelo de dominio.
 */
internal class ColorCellEditor : AbstractCellEditor(), TableCellEditor {

    private val panel = ColorPanel()

    init {
        // El selector confirma en cuanto el usuario elige: nada de tener que pulsar
        // fuera de la celda para que el color cuaje.
        panel.addActionListener { stopCellEditing() }
    }

    override fun getCellEditorValue(): Any = panel.selectedColor?.rgb?.and(RGB_MASK) ?: 0

    override fun getTableCellEditorComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        row: Int,
        column: Int,
    ): Component {
        panel.selectedColor = Color(value as? Int ?: 0)
        return panel
    }
}

internal class ColorCellRenderer : TableCellRenderer {

    private val panel = ColorPanel().apply {
        setEditable(false)
        isOpaque = true
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        panel.selectedColor = Color(value as? Int ?: 0)
        panel.background = if (isSelected) table.selectionBackground else table.background
        return panel
    }
}

private const val RGB_MASK = 0xFFFFFF
