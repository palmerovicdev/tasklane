package com.tasklane.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.TaskFilter
import com.tasklane.service.ViewService
import javax.swing.JComponent

/**
 * El filtro de vista de la cabecera de la Tool Window.
 *
 * Va en `setTitleActions`, junto al selector de repositorio y por el mismo motivo:
 * el filtro es de la ventana, no de un estado. En la barra de cada pestaña habría
 * que pintar uno por estado diciendo todos lo mismo, y elegir «vencidas» habría que
 * repetirlo pestaña por pestaña.
 *
 * Es un desplegable y no un campo de texto a propósito: lo que se escribe es la
 * consulta —con su sintaxis—, y esto son cuatro respuestas cerradas. Ver
 * [TaskFilter].
 */
internal class ViewFilterAction(private val project: Project) : ComboBoxAction(), DumbAware {

    init {
        isSmallVariant = true
    }

    /** EDT como el selector de repositorio: los dos viven en el mismo componente. */
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val filter = ViewService.getInstance(project).filter.value
        e.presentation.text = filter.label()
        e.presentation.icon = AllIcons.General.Filter
        e.presentation.description = TasklaneBundle.message("filter.description")
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup =
        DefaultActionGroup(TaskFilter.entries.map(::SelectFilterAction))

    /**
     * Los cuatro son excluyentes, así que se pintan marcados y no como interruptores:
     * desmarcar el que ya está puesto no significa nada y no hace nada.
     */
    private inner class SelectFilterAction(private val target: TaskFilter) :
        ToggleAction(target.label()), DumbAware {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean =
            ViewService.getInstance(project).filter.value == target

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) ViewService.getInstance(project).setFilter(target)
        }
    }
}

/**
 * Nombres visibles del filtro. Aquí y no en el dominio porque son texto traducible,
 * no comportamiento — igual que los de los enums de configuración.
 */
private fun TaskFilter.label(): String = when (this) {
    TaskFilter.ALL -> TasklaneBundle.message("filter.all")
    TaskFilter.OPEN -> TasklaneBundle.message("filter.open")
    TaskFilter.OVERDUE -> TasklaneBundle.message("filter.overdue")
    TaskFilter.BOOKMARKED -> TasklaneBundle.message("filter.bookmarked")
}
