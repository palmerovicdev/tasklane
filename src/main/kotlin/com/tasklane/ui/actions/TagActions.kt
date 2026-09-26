package com.tasklane.ui.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.JBColor
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import com.tasklane.domain.model.Task

/**
 * *Tags ▸*: sumar y quitar etiquetas a la selección sin abrir el diálogo (P31).
 *
 * Hasta la 2.23 las etiquetas sólo se tocaban desde el diálogo y tarea a tarea: etiquetar
 * `#release` las doce tareas de una entrega eran doce diálogos. *Add…* suma a todas, sin
 * quitarle a ninguna las que ya lleva; *Remove ▸* lista las que lleva la selección.
 */
internal class TagsActionGroup : DefaultActionGroup(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val panel = TasklaneDataKeys.PANEL.getData(e.dataContext)
        e.presentation.isVisible = panel != null
        e.presentation.isEnabled = panel != null && panel.isSelectionEditable()
    }
}

internal class AddTagsAction : PanelAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panelOf(e)?.isSelectionEditable() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.addTagsSelected()
    }
}

/**
 * *Remove ▸*: una entrada por etiqueta de la selección, con su color si lo tiene. Apagado
 * si ninguna seleccionada lleva etiquetas. Se recalcula en cada apertura, como *Move To*.
 */
internal class RemoveTagActionGroup : ActionGroup(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val panel = TasklaneDataKeys.PANEL.getData(e.dataContext)
        e.presentation.isVisible = panel != null
        e.presentation.isEnabled = panel != null &&
            panel.isSelectionEditable() &&
            panel.selectedTasks().any { it.tags.isNotEmpty() }
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val panel = e?.let { TasklaneDataKeys.PANEL.getData(it.dataContext) } ?: return EMPTY_ARRAY
        return SelectionTags.of(panel.selectedTasks()).map { tag ->
            val color = panel.tagColor(tag)?.let { JBColor(it.light, it.dark) }
            RemoveTagAction(tag, color)
        }.toTypedArray()
    }
}

private class RemoveTagAction(private val tag: String, color: JBColor?) : PanelAction() {

    init {
        templatePresentation.setText("#$tag", false)
        // El mismo cuadro que la prioridad en su menú: ver `PriorityDot.menu`.
        templatePresentation.icon = color?.let { ColorIcon(JBUI.scale(MENU), JBUI.scale(MENU_COLOR), it, true) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panelOf(e)?.isSelectionEditable() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.removeTagSelected(tag)
    }

    private companion object {
        const val MENU = 16
        const val MENU_COLOR = 10
    }
}

/**
 * Las etiquetas de una selección, para *Remove ▸*: una vez cada una sin mirar mayúsculas
 * —`api` y `API` son la misma, como en [com.tasklane.domain.text.TagParser]—, con la forma
 * de la primera que la lleva. Primero las que llevan más tareas de la selección, que son
 * las que se vinieron a quitar; a igualdad, por orden alfabético.
 */
internal object SelectionTags {

    fun of(tasks: List<Task>): List<String> = tasks
        .flatMap { task -> task.tags.distinctBy { it.lowercase() } }
        .groupBy { it.lowercase() }
        .values
        .sortedWith(compareByDescending<List<String>> { it.size }.thenBy { it.first().lowercase() })
        .map { it.first() }
}
