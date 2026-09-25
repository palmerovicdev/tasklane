package com.tasklane.ui.quickadd

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.code.TodoComments
import com.tasklane.domain.command.TaskCommand
import com.tasklane.service.TaskService

/**
 * *Import TODO Comments…*: los `TODO` que ya hay en el código, a Tasklane de una vez
 * (2.8.0).
 *
 * Es el camino de ida para un proyecto que ya existía antes que su lista: sin esto, lo
 * pendiente queda repartido entre la ventana *TODO* del IDE y Tasklane, y ninguna de
 * las dos dice la verdad entera.
 *
 * **No toca el código.** Borrar cien comentarios de golpe, en ficheros que ni siquiera
 * están abiertos, es un cambio que nadie revisa; para mover uno y quitarlo está
 * `Alt+Enter` sobre él —ver `TodoToTaskIntention`—. Cada tarea nace **anclada** a su
 * TODO, así que la marca del margen aparece encima del comentario.
 *
 * **Importar dos veces no duplica.** Un TODO cuya línea ya tiene una tarea anclada con
 * ese mismo texto sale en la lista desmarcado. Se compara por texto de línea y no por
 * número: el fichero pudo cambiar entre una importación y otra.
 *
 * No es `DumbAware`: el índice de TODO no se puede leer mientras se indexa, y la
 * plataforma la apaga sola hasta entonces.
 */
internal class ImportTodosAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.let(TaskService::getInstance)
        e.presentation.isEnabledAndVisible = service != null
        e.presentation.isEnabled = service != null && !service.isReadOnly(service.snapshot.value.activeRepo)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = TaskService.getInstance(project)
        val scan = ProgressManager.getInstance().runProcessWithProgressSynchronously<Scan, RuntimeException>(
            { scan(project, service, ProgressManager.getInstance().progressIndicator) },
            TasklaneBundle.message("import.todos.progress"),
            true,
            project,
        )
        if (scan.found.isEmpty()) {
            notify(project, TasklaneBundle.message("import.todos.none"), NotificationType.INFORMATION)
            return
        }

        // Capturado antes del diálogo, como en los demás caminos que crean.
        val repo = service.snapshot.value.activeRepo
        val dialog = ImportTodosDialog(project, scan.found, scan.imported)
        if (!dialog.showAndGet()) return
        val chosen = dialog.selected
        if (chosen.isEmpty()) return

        val tag = dialog.tagWithKeyword
        service.apply(
            TaskCommand.CreateMany(
                repo,
                chosen.map { todo ->
                    TaskCommand.Create(
                        repo = repo,
                        body = todo.text,
                        tags = if (tag && todo.keyword.isNotEmpty()) listOf(todo.keyword) else emptyList(),
                        anchors = listOf(todo.anchor),
                    )
                },
            ),
        )
        notify(project, TasklaneBundle.message("import.todos.done", chosen.size), NotificationType.INFORMATION)
    }

    private class Scan(val found: List<TodoComments.Found>, val imported: Set<TodoComments.Found>)

    private fun scan(project: Project, service: TaskService, indicator: ProgressIndicator): Scan {
        indicator.isIndeterminate = false
        val found = TodoComments.all(project, indicator)
        val imported = found.groupBy { it.path }.flatMap { (path, todos) ->
            val lines = service.anchorsIn(path).map { it.anchor.text }.toSet()
            todos.filter { it.lineText.trim().take(com.tasklane.domain.model.CodeAnchor.MAX_TEXT) in lines }
        }.toSet()
        return Scan(found, imported)
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Tasklane")
            .createNotification(content, type)
            .notify(project)
    }
}
