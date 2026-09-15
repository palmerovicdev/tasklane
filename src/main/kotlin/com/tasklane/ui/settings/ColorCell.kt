package com.tasklane.ui.settings

import com.intellij.ui.ColorPanel
import java.awt.Color
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * La muestra de una celda de color, con [ColorPanel] —el de la plataforma— en vez de
 * un texto hexadecimal.
 *
 * Hay dos columnas, clara y oscura, y no una sola: el modelo guarda ambos valores
 * para que `JBColor` resuelva el tema sin que el plugin se entere de cuál está
 * activo. Elegir un único color obligaría a derivar el otro, y ningún cálculo
 * automático acierta con los dos contrastes a la vez.
 *
 * **Sólo pinta.** Hasta la 2.2 había también un editor de celda con otro `ColorPanel`
 * dentro, y es lo que hacía que cambiar el color no funcionara: el porqué está en
 * `PrioritiesTable.installColorPicker`, que es lo que lo sustituye.
 *
 * El valor de la celda es el RGB como `Int`, igual que en el modelo de dominio.
 */
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
