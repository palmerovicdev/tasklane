package com.tasklane.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.tasklane.domain.model.TaskFilter
import com.tasklane.service.TaskService
import com.tasklane.service.ViewService

/**
 * Los cuatro filtros de vista como acciones del Keymap (P34). El desplegable de la cabecera
 * los construye en código, así que hasta la 2.24 no se podían asignar ni buscar.
 *
 * Son interruptores: pulsar otra vez el que está puesto vuelve a *All tasks*, que es lo que
 * se espera de una tecla que «enseña sólo lo vencido». No necesitan pestaña —el filtro es de
 * la ventana entera—, así que funcionan desde cualquier sitio del proyecto.
 */
internal abstract class ShowTasksAction(private val target: TaskFilter) : DumbAwareToggleAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { ViewService.getInstance(it).filter.value == target } == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        ViewService.getInstance(project).setFilter(if (state) target else TaskFilter.ALL)
    }
}

internal class ShowAllTasksAction : ShowTasksAction(TaskFilter.ALL)

internal class ShowOpenTasksAction : ShowTasksAction(TaskFilter.OPEN)

internal class ShowOverdueTasksAction : ShowTasksAction(TaskFilter.OVERDUE)

internal class ShowBookmarkedTasksAction : ShowTasksAction(TaskFilter.BOOKMARKED)

/**
 * Pasar al repositorio siguiente del selector de la cabecera (P34), dando la vuelta al final:
 * es recorrer un desplegable, no avanzar por una lista con principio y fin como los estados.
 * Sólo con más de uno, como el selector.
 */
internal class NextRepositoryAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && TaskService.getInstance(project).snapshot.value.repositories.size > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = TaskService.getInstance(e.project ?: return)
        val snapshot = service.snapshot.value
        val repositories = snapshot.repositories
        if (repositories.size < 2) return
        val index = repositories.indexOfFirst { it.key == snapshot.activeRepo }
        service.selectRepo(repositories[(index + 1).mod(repositories.size)].key)
    }
}
