package com.tasklane.ui.quickadd

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.tasklane.domain.command.TaskCommand
import com.tasklane.service.TaskService
import com.tasklane.ui.editor.TaskEditDialog

/**
 * Crear una tarea desde cualquier sitio del IDE, sin abrir la Tool Window.
 *
 * El atajo por defecto es `⌘⌥R`, que en el keymap de macOS ya es *Resume Program*.
 * Se mantiene a sabiendas —ver `docs/architecture.html` §14—: el IDE señala el
 * conflicto en *Settings → Keymap* y quien use el depurador a diario lo reasigna.
 * La alternativa, un atajo libre pero incómodo, penalizaría el caso común.
 *
 * **Abre el mismo diálogo que el botón *New Task*** y no un popup propio, que es lo
 * que hacía hasta la `0.6.7`. Eran dos formas distintas de escribir lo mismo, con dos
 * conjuntos de campos —el popup no tenía vencimiento, etiquetas ni barra de formato— y
 * dos sitios donde arreglar cada cosa. Los triggers de prioridad, que era lo único que
 * el popup sabía hacer y el diálogo no, se mudaron con él: ver [TaskEditDialog].
 *
 * La tarea nace en el **repositorio activo**, el mismo que usa *New Task*. El popup
 * proponía el del fichero abierto en el editor porque llevaba un selector con el que
 * corregirlo; sin ese selector, adivinar sería mandar tareas a un repositorio que
 * quizá ni se está mirando.
 */
internal class QuickAddAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        // Un repositorio abierto en solo lectura no acepta tareas nuevas: el servicio
        // no escribiría el fichero, así que crearla daría una fila que desaparece al
        // recargar.
        e.presentation.isEnabled = project != null &&
            TaskService.getInstance(project).let { !it.isReadOnly(it.snapshot.value.activeRepo) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = TaskService.getInstance(project)
        // El repositorio se captura **antes** de abrir el diálogo y se usa el mismo al
        // confirmar: el snapshot se reasigna mientras el diálogo está abierto, y volver
        // a leerlo después puede dar otro. La captura pegada se habría escrito en un
        // repositorio y la tarea nacería en el otro, con la imagen rota desde el primer
        // segundo. Mismo motivo que en `TasklanePanel.createTask`.
        val snapshot = service.snapshot.value
        val repo = snapshot.activeRepo

        val dialog = TaskEditDialog(project, snapshot.config, repo)
        if (!dialog.showAndGet()) return
        service.apply(
            TaskCommand.Create(repo, dialog.body, dialog.stateId, dialog.priorityId, dialog.tags, dialog.dueDate),
        )
    }
}
