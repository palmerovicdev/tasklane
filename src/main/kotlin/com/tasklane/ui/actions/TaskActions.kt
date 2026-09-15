package com.tasklane.ui.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.tasklane.domain.model.Grouping
import com.tasklane.service.SearchService
import com.tasklane.ui.settings.TasklaneConfigurable
import com.tasklane.ui.settings.label
import com.tasklane.ui.toolwindow.TasklanePanel

/**
 * Las acciones de la Tool Window, declaradas en `plugin.xml`.
 *
 * Están registradas y no creadas a mano dentro del panel por lo que eso trae gratis:
 * salen en *Settings → Keymap* —reasignables— y en *Search Everywhere*, y el mismo
 * objeto sirve a la barra de herramientas, al menú contextual y al atajo. El precio
 * es que la acción no puede capturar su panel en el constructor: lo pide al
 * `DataContext` con [TasklaneDataKeys.PANEL], que es lo que la hace funcionar sea
 * cual sea la pestaña con el foco.
 *
 * Texto, descripción e icono viven en `plugin.xml` y en el bundle, no aquí.
 */
internal abstract class PanelAction : DumbAwareAction() {

    protected fun panelOf(e: AnActionEvent): TasklanePanel? = TasklaneDataKeys.PANEL.getData(e.dataContext)

    /** EDT porque `update` mira la selección del árbol, que es estado de Swing. */
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

internal class NewTaskAction : PanelAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panelOf(e)?.isEditable() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.createTask()
    }
}

internal class EditTaskAction : PanelAction() {
    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isEnabled = panel != null && panel.isSelectionEditable() && panel.selectedTasks().size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.editSelected()
    }
}

internal class ToggleBookmarkAction : PanelAction() {
    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isEnabled = panel != null && panel.isSelectionEditable() && panel.selectedTasks().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.toggleBookmarkSelected()
    }
}

internal class DeleteTaskAction : PanelAction() {
    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isEnabled = panel?.canDeleteSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.deleteSelected()
    }
}

internal class ToggleCompleteAction : PanelAction() {
    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isEnabled = panel?.isSelectionEditable() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.toggleSelected()
    }
}

internal class FocusSearchAction : PanelAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panelOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.focusSearch()
    }
}

/**
 * La agrupación es una propiedad **del estado**, no de la vista, así que el toggle
 * escribe en la configuración del proyecto y el cambio vuelve por el snapshot. Es lo
 * que hace que quede recordada al reabrir y que se comparta con el equipo.
 */
internal class GroupByDateAction : DumbAwareToggleAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = TasklaneDataKeys.PANEL.getData(e.dataContext) != null
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        TasklaneDataKeys.PANEL.getData(e.dataContext)?.grouping == Grouping.BY_DATE

    override fun setSelected(e: AnActionEvent, selected: Boolean) {
        TasklaneDataKeys.PANEL.getData(e.dataContext)
            ?.setGrouping(if (selected) Grouping.BY_DATE else Grouping.NONE)
    }
}

/**
 * Las cuatro agrupaciones, en un desplegable de la barra.
 *
 * Sustituye al interruptor de *agrupar por fecha*, que sólo alcanzaba a una de las
 * cuatro: las otras dos —prioridad y etiqueta— existían desde el rediseño de las
 * filas y había que ir a *Settings* para llegar a ellas, que es un viaje largo para
 * algo que se cambia mirando la lista. [GroupByDateAction] sigue declarada porque es
 * asignable en el keymap y quien le puso un atajo espera que siga funcionando.
 */
internal class GroupingActionGroup : ActionGroup(), DumbAware {

    /**
     * Los cuatro hijos se crean **una vez** y se reutilizan.
     *
     * Devolver instancias nuevas en cada `getChildren` —que es lo que se hacía— dejaba
     * el visto pegado a la opción que estuviera marcada la primera vez: el sistema de
     * acciones cachea la `Presentation` por instancia de acción, y una instancia recién
     * creada en cada apertura del desplegable no tiene de dónde heredar la suya. La
     * lista sí se reagrupaba; el visto seguía en la agrupación anterior.
     */
    private val children: Array<AnAction> =
        Grouping.entries.map<Grouping, AnAction>(::SelectGroupingAction).toTypedArray()

    init {
        isPopup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = TasklaneDataKeys.PANEL.getData(e.dataContext) != null
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = children
}

/**
 * Las cuatro son excluyentes, así que se pintan marcadas. La agrupación es una
 * propiedad **del estado** y no de la vista: esto escribe en la configuración del
 * proyecto y el cambio vuelve por el snapshot, que es lo que hace que quede recordada
 * y se comparta con el equipo.
 */
private class SelectGroupingAction(private val target: Grouping) :
    ToggleAction(target.label()), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        TasklaneDataKeys.PANEL.getData(e.dataContext)?.grouping == target

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) TasklaneDataKeys.PANEL.getData(e.dataContext)?.setGrouping(target)
    }
}

/**
 * Alcance de la búsqueda. Sólo aparece cuando hay más de un repositorio: con uno
 * solo, «buscar en todos» y «buscar aquí» son la misma cosa.
 */
internal class SearchAllReposAction : DumbAwareToggleAction() {

    /** EDT porque `update` consulta la pestaña, que es un componente de Swing. */
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val panel = TasklaneDataKeys.PANEL.getData(e.dataContext)
        e.presentation.isVisible = (panel?.repositoryCount ?: 0) > 1
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { SearchService.getInstance(it).allRepos.value } == true

    override fun setSelected(e: AnActionEvent, selected: Boolean) {
        e.project?.let { SearchService.getInstance(it).setAllRepos(selected) }
    }
}

internal class TasklaneSettingsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, TasklaneConfigurable::class.java)
    }
}
