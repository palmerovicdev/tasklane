package com.tasklane.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.domain.export.ExportFormat
import com.tasklane.domain.export.TaskExporter
import com.tasklane.domain.model.RepositoryRef
import java.awt.datatransfer.StringSelection

/**
 * La exportación al portapapeles.
 *
 * Es delgado a propósito: el texto lo produce [TaskExporter], que es dominio puro y
 * está cubierto por tests, y aquí sólo queda hablar con el portapapeles, con los
 * diálogos y con el resto de servicios. Lo que se exporta —qué secciones, con qué
 * cabecera— lo decide la pestaña, porque es la que sabe qué se está viendo.
 */
@Service(Service.Level.PROJECT)
class ExportService(private val project: Project) {

    private val workspace = TasklaneWorkspaceService.getInstance(project)

    /**
     * Formato de salida. Es del espacio de trabajo y no de la configuración del
     * proyecto: es una preferencia de quien copia, no una decisión del equipo.
     */
    var format: ExportFormat
        get() = workspace.exportFormat
        set(value) {
            workspace.exportFormat = value
        }

    /**
     * Copia [sections] al portapapeles y avisa de cuántas tareas salieron. El aviso
     * no es adorno: el portapapeles no da ninguna señal de haber cambiado, y sin él
     * un alcance vacío se confunde con un fallo.
     */
    fun copy(sections: List<TaskExporter.Section>) {
        val config = TaskService.getInstance(project).snapshot.value.config
        val text = TaskExporter.export(sections, config, format)
        if (text.isEmpty()) {
            notify(TasklaneBundle.message("export.empty"), NotificationType.INFORMATION)
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify(
            TasklaneBundle.message("export.copied", sections.sumOf { it.tasks.size }),
            NotificationType.INFORMATION,
        )
    }

    /**
     * «Exportar y quitar» para un repositorio que ya no está en disco.
     *
     * Es la salida que hace honesto el trato del catálogo con los repositorios
     * ausentes: sus tareas nunca se ocultan ni se borran solas, pero renombrar una
     * carpeta no puede condenar a arrastrar la entrada para siempre. Se copia todo,
     * se confirma, y sólo entonces se borra.
     */
    fun exportAndRemove(ref: RepositoryRef) {
        val service = TaskService.getInstance(project)
        val snapshot = service.snapshot.value
        val tasks = snapshot.tasksOf(ref.key)
        val config = snapshot.config

        // Un bloque por estado, en el orden de la configuración: es lo que hace que
        // el texto se pueda leer como el inventario de lo que se está quitando.
        val sections = config.states.map { state ->
            TaskExporter.Section(
                heading = "${ref.displayName} · ${state.name}",
                tasks = tasks.filter { it.stateId == state.id },
            )
        }
        val text = TaskExporter.export(sections, config, format)

        val confirmed = MessageDialogBuilder
            .yesNo(
                TasklaneBundle.message("export.remove.title", ref.displayName),
                TasklaneBundle.message("export.remove.question", tasks.size),
            )
            .yesText(TasklaneBundle.message("export.remove.confirm"))
            .ask(project)
        if (!confirmed) return

        if (text.isNotEmpty()) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
        service.forgetRepo(ref.key)
        notify(TasklaneBundle.message("export.remove.done", ref.displayName, tasks.size), NotificationType.INFORMATION)
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(TaskService.NOTIFICATION_GROUP)
            .createNotification(content, type)
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): ExportService = project.service()
    }
}
