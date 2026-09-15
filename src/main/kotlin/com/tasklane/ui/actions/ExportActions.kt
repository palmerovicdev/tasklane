package com.tasklane.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import com.tasklane.TasklaneBundle
import com.tasklane.domain.export.ExportFormat
import com.tasklane.domain.export.ExportScope
import com.tasklane.service.ExportService

/**
 * Exportar al portapapeles: la pestaña entera, el grupo de fecha donde está la
 * selección, o sólo lo seleccionado.
 *
 * Lo que sale es **lo que se ve**. La pestaña construye las secciones a partir de lo
 * que acaba de pintar —búsqueda incluida—, así que exportar con un filtro puesto
 * copia el filtro, que es lo único que se puede revisar antes de pegar.
 */
internal class ExportStateAction : ExportAction(ExportScope.STATE)

internal class ExportGroupAction : ExportAction(ExportScope.GROUP) {
    /** Sin agrupación por fecha no hay grupo que exportar, y el alcance sobra. */
    override fun isAvailable(e: AnActionEvent): Boolean = panelOf(e)?.hasSelectedGroup() == true
}

internal class ExportSelectionAction : ExportAction(ExportScope.SELECTION) {
    override fun isAvailable(e: AnActionEvent): Boolean = panelOf(e)?.selectedTasks()?.isNotEmpty() == true
}

/**
 * Saca las tareas del repositorio activo a un `tasks.xml`, en el formato de siempre.
 *
 * Es la puerta de salida de la Fase 3: las tareas viven en una base SQLite y esto las
 * devuelve a un fichero de texto que otra versión del plugin —o cualquiera— sabe leer.
 * No es una exportación «para pegar» como las demás de este menú, y por eso va detrás
 * de un separador: escribe un fichero y dice dónde.
 */
internal class ExportXmlAction : PanelAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panelOf(e)?.activeRepository != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = panelOf(e)?.activeRepository?.key ?: return
        ExportService.getInstance(project).exportXml(repo)
    }
}

internal abstract class ExportAction(private val scope: ExportScope) : PanelAction() {

    protected open fun isAvailable(e: AnActionEvent): Boolean = panelOf(e) != null

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isAvailable(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = panelOf(e) ?: return
        ExportService.getInstance(project).copy(panel.exportRequest(scope))
    }
}

/**
 * Markdown sí o no. Va en el menú de exportación y no en los ajustes del proyecto
 * porque el formato depende de dónde se vaya a pegar —un ticket entiende `- [x]`, un
 * correo no—, y eso cambia entre una copia y la siguiente.
 */
internal class ExportMarkdownAction : DumbAwareToggleAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.project != null
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { ExportService.getInstance(it).format } == ExportFormat.MARKDOWN

    override fun setSelected(e: AnActionEvent, selected: Boolean) {
        val project = e.project ?: return
        ExportService.getInstance(project).format =
            if (selected) ExportFormat.MARKDOWN else ExportFormat.PLAIN
    }
}

/**
 * «Exportar y quitar» para el repositorio ausente que esté seleccionado.
 *
 * Sólo aparece cuando hay uno: es la única situación en la que quitar un repositorio
 * de la lista no es perder datos, porque sus tareas salen al portapapeles antes.
 * Ver `docs/architecture.html` §7.
 */
internal class ExportAndRemoveRepoAction : PanelAction() {

    override fun update(e: AnActionEvent) {
        val ref = panelOf(e)?.activeRepository
        e.presentation.isEnabledAndVisible = ref != null && !ref.available
        // El nombre del repositorio va en el texto de la acción, no sólo en el
        // diálogo: es destructiva y tiene que decir sobre qué actúa antes de pulsarla.
        ref?.let {
            e.presentation.text =
                TasklaneBundle.message("action.Tasklane.ExportAndRemoveRepo.named", it.displayName)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ref = panelOf(e)?.activeRepository ?: return
        ExportService.getInstance(project).exportAndRemove(ref)
    }
}
