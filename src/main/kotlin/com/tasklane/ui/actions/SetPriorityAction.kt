package com.tasklane.ui.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.tasklane.domain.model.TaskPriority
import com.tasklane.ui.common.PriorityDot

/**
 * *Priority ▸*: cambiar la prioridad de la selección sin abrir el diálogo.
 *
 * Es el mismo argumento que el de *Move To*. La prioridad es, con el estado, lo que
 * más se retoca de una tarea ya escrita —se sube lo que corre y se baja lo que se
 * puede esperar—, y hasta la `1.3.0` costaba abrir un modal, tocar un desplegable y
 * aceptar. Aquí son dos clics, y desde el distintivo de la tarjeta, uno.
 *
 * Cada entrada lleva **su color**, no sólo su nombre: el nombre lo pone el usuario en
 * los ajustes y puede ser cualquier cosa, mientras que el color es lo que se reconoce
 * en la lista. Es el mismo punto que pinta la tarjeta; ver [PriorityDot].
 */
internal class SetPriorityActionGroup : ActionGroup(), DumbAware {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val panel = TasklaneDataKeys.PANEL.getData(e.dataContext)
        e.presentation.isVisible = panel != null
        e.presentation.isEnabled = panel != null && panel.isSelectionEditable()
    }

    /**
     * Se recalculan en cada apertura por lo mismo que las de *Move To*: las
     * prioridades son configuración del proyecto y pueden haber cambiado de nombre,
     * de color, de orden o de número desde la vez anterior.
     */
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val panel = e?.let { TasklaneDataKeys.PANEL.getData(it.dataContext) } ?: return EMPTY_ARRAY
        return panel.priorities.map(::SetPriorityAction).toTypedArray()
    }
}

/**
 * La prioridad que ya tienen **todas** las tareas seleccionadas sale apagada: no hay
 * nada que cambiar. Con la selección repartida entre varias, en cambio, todas las
 * entradas siguen vivas, porque cualquiera de ellas las unifica.
 */
private class SetPriorityAction(private val target: TaskPriority) : PanelAction() {

    init {
        templatePresentation.text = target.name
        templatePresentation.icon = PriorityDot.menu(target)
    }

    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isEnabled = panel != null &&
            panel.isSelectionEditable() &&
            panel.selectedTasks().any { it.priorityId != target.id }
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.setPrioritySelected(target.id)
    }
}
