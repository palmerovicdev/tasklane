package com.tasklane.ui.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import java.awt.BorderLayout
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/**
 * Alta y edición de una tarea.
 *
 * Es un [DialogWrapper] deliberadamente sencillo: el popup compacto optimizado para
 * teclado es la Fase 4. Lo que importa aquí es cerrar el ciclo CRUD.
 */
internal class TaskEditDialog(
    project: Project,
    private val config: TasklaneConfig,
    initialBody: String = "",
    initialState: StateId? = null,
    initialPriority: PriorityId? = null,
) : DialogWrapper(project) {

    private val bodyArea = JBTextArea(initialBody, 6, 48).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val stateCombo = comboOf(config.states, initialState?.let(config::state) ?: config.defaultState) { it.name }
    private val priorityCombo =
        comboOf(config.priorities, initialPriority?.let(config::priority) ?: config.defaultPriority) { it.name }

    init {
        title = TasklaneBundle.message(
            if (initialBody.isEmpty()) "dialog.task.new.title" else "dialog.task.edit.title",
        )
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBScrollPane(bodyArea), BorderLayout.CENTER)
        add(
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(stateCombo, BorderLayout.WEST)
                add(priorityCombo, BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
    }

    override fun getPreferredFocusedComponent(): JComponent = bodyArea

    /** Una tarea sin texto no es una tarea: se bloquea el OK en vez de crear ruido. */
    override fun doValidate(): ValidationInfo? =
        if (bodyArea.text.isBlank()) ValidationInfo(TasklaneBundle.message("dialog.task.empty"), bodyArea) else null

    val body: String get() = bodyArea.text.trim()
    val stateId: StateId get() = (stateCombo.selectedItem as TaskState).id
    val priorityId: PriorityId get() = (priorityCombo.selectedItem as TaskPriority).id

    private fun <T : Any> comboOf(items: List<T>, selected: T, label: (T) -> String): JComboBox<T> {
        // Nada de items.toTypedArray(): exigiria un parametro de tipo reificado.
        val model: ComboBoxModel<T> = DefaultComboBoxModel<T>().apply { items.forEach(::addElement) }
        return JComboBox(model).apply {
            selectedItem = selected
            // Ambos overloads de SimpleListCellRenderer.create estan deprecados,
            // asi que se extiende la clase directamente.
            renderer = object : SimpleListCellRenderer<T>() {
                override fun customize(
                    list: JList<out T>,
                    value: T?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value?.let(label).orEmpty()
                }
            }
        }
    }
}
