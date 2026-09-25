package com.tasklane.code

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.tasklane.TasklaneBundle
import com.tasklane.domain.command.TaskCommand
import com.tasklane.service.TaskService
import com.tasklane.ui.editor.TaskEditDialog

/**
 * `Alt+Enter` sobre un comentario `TODO`: *Move TODO to Tasklane* (2.8.0).
 *
 * Abre el diálogo de tarea nueva con el texto del TODO de cuerpo, su palabra clave de
 * etiqueta y el ancla puesta, y al crear **quita el comentario** del código: la nota ya
 * vive en Tasklane, con su marca en el margen en la misma línea, y tenerla en los dos
 * sitios es tenerla dos veces para que envejezca por separado. El borrado va en su
 * propio comando, así que `⌘Z` en el editor lo devuelve.
 *
 * Si el comentario lleva **algo más** que el TODO —un KDoc con una línea de TODO
 * dentro—, borrarlo se llevaría la documentación: la entrada pasa a llamarse *Create
 * Tasklane Task from TODO* y deja el comentario donde está. Ver [TodoComments.removalOf].
 *
 * La tarea nace en el repositorio activo, igual que la de *New Task from Code*.
 */
internal class TodoToTaskIntention : BaseIntentionAction() {

    override fun getFamilyName(): String = TasklaneBundle.message("intention.todo.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        val service = TaskService.getInstance(project)
        if (service.isReadOnly(service.snapshot.value.activeRepo)) return false
        val found = TodoComments.at(project, file, editor.caretModel.offset) ?: return false
        text = TasklaneBundle.message(if (found.removable) "intention.todo.move" else "intention.todo.create")
        return true
    }

    /** Fuera de una write action: antes de tocar el código hay un diálogo que aceptar. */
    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html(TasklaneBundle.message("intention.todo.preview"))

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val found = ReadAction.computeBlocking<TodoComments.Found?, RuntimeException> {
            TodoComments.at(project, file, editor.caretModel.offset)
        } ?: return
        val service = TaskService.getInstance(project)
        // Capturado antes del diálogo por lo mismo que en `NewTaskFromCodeAction`: el
        // snapshot se reasigna mientras está abierto.
        val snapshot = service.snapshot.value
        val repo = snapshot.activeRepo
        val preview = found.anchor

        val dialog = TaskEditDialog(
            project,
            snapshot.config,
            repo,
            initialBody = found.text,
            initialTags = listOfNotNull(found.keyword.takeIf { it.isNotEmpty() }),
            initialAnchors = listOf(preview),
            isNew = true,
        )
        if (!dialog.showAndGet()) return

        var anchors = dialog.anchors
        if (found.removable) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)
            var moved: com.tasklane.domain.model.CodeAnchor? = null
            if (document != null) {
                WriteCommandAction.writeCommandAction(project, file)
                    .withName(TasklaneBundle.message("intention.todo.command"))
                    .run<RuntimeException> { moved = TodoComments.remove(document, found) }
            }
            // El ancla del diálogo apuntaba al comentario, que ya no está: pasa a la
            // línea que ha quedado en su sitio. Si el usuario la quitó, sigue quitada.
            moved?.let { fresh -> anchors = anchors.map { if (it == preview) fresh else it } }
        }
        service.apply(
            TaskCommand.Create(
                repo = repo,
                body = dialog.body,
                stateId = dialog.stateId,
                priorityId = dialog.priorityId,
                tags = dialog.tags,
                dueDate = dialog.dueDate,
                anchors = anchors,
            ),
        )
    }
}
