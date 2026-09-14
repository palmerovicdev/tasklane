package com.tasklane.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import com.tasklane.TasklaneBundle
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.service.TaskService
import com.tasklane.ui.common.PriorityDot
import javax.swing.Icon

/**
 * El desplegable que abre el distintivo de prioridad de una tarjeta.
 *
 * Cambiar la prioridad desde donde se lee es el camino corto del submenú *Priority*:
 * quien mira una lista y decide que algo corre está mirando ese distintivo, y llegar
 * hasta él ya es la mitad del gesto. Un clic abre la lista, otro la cambia.
 *
 * Actúa sobre **la tarjeta pulsada** y no sobre la selección, igual que el marcador de
 * la fila: se pulsa lo que se está señalando, y obligar a seleccionar primero
 * convertiría un gesto de un clic en dos. Para varias a la vez está el menú
 * contextual, que sí va por la selección.
 */
internal object PriorityPopup {

    fun show(project: Project, config: TasklaneConfig, task: Task, at: RelativePoint) {
        val service = TaskService.getInstance(project)
        // Un repositorio en solo lectura no se toca, y enseñar una lista que no va a
        // hacer nada es peor que no abrirla.
        if (service.isReadOnly(task.repo)) return

        // De la más alta a la más baja: es el orden en que se agrupa la lista por
        // prioridad, así que es el que ya está aprendido.
        val priorities = config.priorities.sortedByDescending { it.order }
        if (priorities.isEmpty()) return

        val step = object : BaseListPopupStep<TaskPriority>(
            TasklaneBundle.message("toolwindow.row.priority.title"),
            priorities,
        ) {
            override fun getTextFor(value: TaskPriority): String = value.name

            override fun getIconFor(value: TaskPriority): Icon = PriorityDot.menu(value)

            /** La que ya tiene sale marcada: la lista dice también en qué punto está. */
            override fun getDefaultOptionIndex(): Int = priorities.indexOfFirst { it.id == task.priorityId }

            override fun onChosen(value: TaskPriority, finalChoice: Boolean): PopupStep<*>? {
                if (value.id != task.priorityId) {
                    service.apply(TaskCommand.ChangePriority(task.repo, task.id, value.id))
                }
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).show(at)
    }
}
