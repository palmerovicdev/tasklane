package com.tasklane.ui.search

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.Task
import com.tasklane.service.SearchService
import com.tasklane.service.TaskService
import com.tasklane.ui.common.PriorityDot
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Las tareas en *Search Everywhere* (2.10.0): `⇧⇧`, escribir, `Enter`, y la tool window
 * se abre con la tarea seleccionada.
 *
 * La ventana puede ir cerrada y encontrar una tarea sigue costando dos teclas. Busca con
 * el mismo índice, la misma sintaxis —`p:high`, `#api`, `file:Auth`— y el mismo alcance
 * que el buscador de la ventana; ver [SearchService.find].
 *
 * Sale en la pestaña *All* y en una propia, *Tasks*. Con la consulta vacía no enseña
 * nada: listar tareas al azar debajo de cada `⇧⇧` sería ruido en el diálogo más usado
 * del IDE.
 *
 * La 2026.3 depreca `SearchEverywhereContributor` y su fábrica en favor de `SeItemsProvider`,
 * y el Plugin Verifier lo cuenta. Se queda a propósito (2.26.2): la API nueva es
 * `@ApiStatus.Experimental` entera —también en la build que depreca la vieja—, así que
 * migrar sólo cambiaría el aviso por otro, y la propia plataforma sigue envolviendo sus
 * contributors de clases y símbolos para el diálogo nuevo. La vieja no está
 * `@ScheduledForRemoval`; se revisa cuando `SeItemsProvider` salga de experimental.
 */
internal class TaskSearchContributor(private val project: Project) : SearchEverywhereContributor<Task> {

    override fun getSearchProviderId(): String = ID

    override fun getGroupName(): String = TasklaneBundle.message("searchEverywhere.group")

    /** Detrás de clases, ficheros y símbolos, que es a lo que se viene a `⇧⇧`. */
    override fun getSortWeight(): Int = SORT_WEIGHT

    override fun showInFindResults(): Boolean = false

    override fun isShownInSeparateTab(): Boolean = true

    override fun fetchElements(pattern: String, progressIndicator: ProgressIndicator, consumer: Processor<in Task>) {
        if (pattern.isBlank() || project.isDisposed) return
        for (task in SearchService.getInstance(project).find(pattern).take(LIMIT)) {
            progressIndicator.checkCanceled()
            if (!consumer.process(task)) return
        }
    }

    override fun processSelectedItem(selected: Task, modifiers: Int, searchText: String): Boolean {
        TaskService.getInstance(project).revealTasks(listOf(selected))
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in Task> = Renderer(project)

    override fun dispose() = Unit

    /**
     * Una línea por tarea: el punto de su prioridad, el título, y en gris el estado —y el
     * repositorio si hay más de uno—, que es lo que distingue dos tareas que se llaman igual.
     */
    private class Renderer(private val project: Project) : ColoredListCellRenderer<Task>() {
        override fun customizeCellRenderer(
            list: JList<out Task>,
            value: Task?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val task = value ?: return
            val snapshot = TaskService.getInstance(project).snapshot.value
            val config = snapshot.config
            icon = PriorityDot.menu(config.priorityOrDefault(task.priorityId))
            val done = task.completedAt != null
            append(
                task.title,
                if (done) SimpleTextAttributes.GRAYED_ATTRIBUTES.derive(SimpleTextAttributes.STYLE_STRIKEOUT, null, null, null)
                else SimpleTextAttributes.REGULAR_ATTRIBUTES,
            )
            val where = buildList {
                add(config.stateOrDefault(task.stateId).name)
                if (snapshot.repositories.size > 1) {
                    add(snapshot.repositories.firstOrNull { it.key == task.repo }?.displayName ?: task.repo.value)
                }
            }
            append("  " + where.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    class Factory : SearchEverywhereContributorFactory<Task> {
        override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<Task> =
            TaskSearchContributor(requireNotNull(initEvent.project))

        override fun isAvailable(project: Project): Boolean = !project.isDefault
    }

    companion object {
        const val ID = "TasklaneTasks"
        private const val SORT_WEIGHT = 700
        private const val LIMIT = 50
    }
}
