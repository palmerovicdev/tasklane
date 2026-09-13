package com.tasklane.ui.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.SplitButtonAction
import com.intellij.openapi.project.DumbAware
import com.tasklane.domain.model.StateId
import com.tasklane.service.TaskService

/**
 * El botón *New Task* de la barra, partido en dos.
 *
 * El cuerpo crea en la pestaña activa —el destino natural— y la flecha abre la lista
 * de estados para apuntar algo donde no se está mirando sin cambiar de pestaña
 * primero.
 *
 * [useDynamicSplitButton] se apaga a propósito. Encendido, el botón **recuerda** la
 * última opción del desplegable y pasa a crear ahí para siempre, como el de *Run*:
 * aquí eso sería una trampa, porque al lado hay pestañas que dicen en qué estado se
 * está y el botón habría dejado de obedecerlas en silencio.
 */
internal class NewTaskSplitAction : SplitButtonAction(NewTaskTargets()), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun useDynamicSplitButton(): Boolean = false

    /**
     * Se pide al `ActionManager` y no se construye uno nuevo para que el botón herede
     * lo declarado en `plugin.xml` —texto, icono— y, si el usuario le asignó un atajo
     * en el keymap, también lo enseñe.
     */
    override fun getMainAction(e: AnActionEvent): AnAction? =
        ActionManager.getInstance().getAction(NEW_TASK)

    private companion object {
        const val NEW_TASK = "Tasklane.NewTask"
    }
}

/**
 * Los destinos del desplegable: un estado por cada uno de los configurados, en el
 * mismo orden que las pestañas. Se calculan en cada apertura porque los estados son
 * configuración del proyecto y pueden haber cambiado desde la anterior.
 */
private class NewTaskTargets : ActionGroup(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val states = TaskService.getInstance(project).snapshot.value.config.states
        return states.map { NewTaskInStateAction(it.id, it.name) }.toTypedArray()
    }
}

private class NewTaskInStateAction(private val target: StateId, name: String) : PanelAction() {

    init {
        templatePresentation.text = name
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panelOf(e)?.isEditable() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.createTask(target)
    }
}
