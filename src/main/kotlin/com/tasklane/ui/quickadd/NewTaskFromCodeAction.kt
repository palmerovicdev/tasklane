package com.tasklane.ui.quickadd

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.tasklane.code.CodeAnchors
import com.tasklane.domain.command.TaskCommand
import com.tasklane.service.TaskService
import com.tasklane.ui.editor.TaskEditDialog

/**
 * Crear una tarea **desde el código**: el mismo diálogo, con el sitio ya puesto.
 *
 * Vive en el menú contextual del editor, que es donde se mira cuando se piensa «esto
 * hay que arreglarlo». Se lleva dos cosas del editor: el ancla —fichero y línea del
 * cursor, ver [CodeAnchors]— y, si hay algo seleccionado, ese texto como cuerpo de la
 * tarea. Lo segundo es lo que evita tener que describir con palabras el trozo que se
 * está mirando.
 *
 * La tarea nace en el **repositorio activo**, igual que [QuickAddAction] y que *New
 * Task*. El ancla no lo cambia: su ruta es relativa al proyecto, no al repositorio, así
 * que una tarea de un repositorio puede apuntar perfectamente a un fichero de otro —y
 * quien apunta algo mientras lee código ajeno querrá que su nota vaya a su lista, no a
 * la del repositorio que estaba abriendo—.
 */
internal class NewTaskFromCodeAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Invisible y no sólo apagada donde no aplica: el menú contextual del editor es
     * largo y una entrada muerta en cada fichero que no es código —o en un proyecto sin
     * Tasklane que tocar— es ruido permanente.
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.let(TaskService::getInstance)
        e.presentation.isVisible = project != null && CodeAnchors.isAnchorable(e)
        e.presentation.isEnabled = service != null && !service.isReadOnly(service.snapshot.value.activeRepo)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val anchor = CodeAnchors.capture(project, e) ?: return
        val service = TaskService.getInstance(project)
        // Capturado antes de abrir el diálogo y reutilizado al confirmar, por lo mismo
        // que en [QuickAddAction]: el snapshot se reasigna mientras está abierto.
        val snapshot = service.snapshot.value
        val repo = snapshot.activeRepo

        val dialog = TaskEditDialog(
            project,
            snapshot.config,
            repo,
            initialBody = CodeAnchors.selection(e),
            initialAnchors = listOf(anchor),
            isNew = true,
        )
        if (!dialog.showAndGet()) return
        service.apply(
            TaskCommand.Create(
                repo = repo,
                body = dialog.body,
                stateId = dialog.stateId,
                priorityId = dialog.priorityId,
                tags = dialog.tags,
                dueDate = dialog.dueDate,
                anchors = dialog.anchors,
            ),
        )
    }
}
