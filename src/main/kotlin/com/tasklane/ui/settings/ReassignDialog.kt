package com.tasklane.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/**
 * Borrar un estado o una prioridad **con tareas dentro** obliga a decir dónde van.
 *
 *     ┌ Delete state "Doing" ────────────────────────┐
 *     │  42 tasks use this state.                    │
 *     │  Move them to:  [ ToDo            ▾ ]        │
 *     │                      [ Cancel ]  [ Delete ]  │
 *     └──────────────────────────────────────────────┘
 *
 * No hay opción de «borrar y ya»: no existe un destino razonable por defecto, y
 * elegirlo por el usuario es justo la decisión que haría desaparecer tareas de
 * donde las dejó. Cancelar no borra nada; el foco arranca en el desplegable y no
 * en el botón destructivo.
 */
internal class ReassignDialog<T : Any>(
    project: Project,
    dialogTitle: String,
    private val question: String,
    options: List<T>,
    label: (T) -> String,
    confirmText: String,
) : DialogWrapper(project) {

    private val combo: JComboBox<T> = JComboBox(DefaultComboBoxModel<T>().apply { options.forEach(::addElement) })
        .apply {
            selectedItem = options.firstOrNull()
            renderer = object : SimpleListCellRenderer<T>() {
                override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, focused: Boolean) {
                    text = value?.let(label).orEmpty()
                }
            }
        }

    init {
        title = dialogTitle
        setOKButtonText(confirmText)
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(GAP))).apply {
        add(JBLabel(question), BorderLayout.NORTH)
        add(combo, BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = combo

    @Suppress("UNCHECKED_CAST")
    val target: T
        get() = combo.selectedItem as T

    private companion object {
        const val GAP = 8
    }
}
