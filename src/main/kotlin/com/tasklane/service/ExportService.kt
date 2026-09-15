package com.tasklane.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.data.store.StorageLayout
import com.tasklane.data.store.TaskFileStore
import com.tasklane.domain.export.ExportFormat
import com.tasklane.domain.export.TaskExporter
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.ui.toolwindow.TasklanePanel
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

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
    /**
     * Resuelve una petición **en segundo plano** y copia el resultado.
     *
     * El paso caro —leer del almacén las tareas de cada sección— no puede ocurrir en el
     * hilo de interfaz: exportar es O(n) por definición, y desde la Fase 3 esa n son
     * filas leídas de disco. Ver [com.tasklane.ui.toolwindow.TasklanePanel.ExportRequest].
     */
    internal fun copy(request: TasklanePanel.ExportRequest) {
        if (request.sections.isEmpty()) {
            notify(TasklaneBundle.message("export.empty"), NotificationType.INFORMATION)
            return
        }
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("export.progress"), true) {
                private var sections: List<TaskExporter.Section> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = request.sections.size <= 1
                    sections = request.sections.mapIndexed { index, pending ->
                        indicator.checkCanceled()
                        indicator.fraction = index.toDouble() / request.sections.size
                        TaskExporter.Section(pending.heading, pending.load(), pending.date)
                    }
                }

                override fun onSuccess() = copy(sections)
            },
        )
    }

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
        val config = service.snapshot.value.config
        // O(n) a propósito y a sabiendas: quitar un repositorio es llevarse sus tareas,
        // y eso es leerlas todas. Es la operación grande que la Fase 5 pasará a escribir
        // en *streaming*.
        val tasks = service.tasksOf(ref.key)

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

    /**
     * Vuelca las tareas de un repositorio a un `tasks.xml` en el formato de siempre.
     *
     * Es la promesa del §3.3 del plan de escala: *se conserva la exportación a XML para
     * que el dato no quede secuestrado dentro de un `.db`*. Una base SQLite se puede
     * abrir con cualquier visor, pero el formato que otra versión de este plugin sabe
     * **leer** es el XML, y tener la puerta de salida a un fichero de texto es lo que
     * hace que migrar a la base no sea un viaje de ida.
     *
     * Escribe donde estaba el original —`.idea/tasklane/repos/<repo>/tasks.xml`—, que es
     * justamente donde la migración lo buscaría: renombrar el `.migrated` y borrar la
     * base es la vuelta atrás completa.
     *
     * **O(n) a propósito**, en segundo plano y con progreso: exportar un millón de
     * tareas es leer un millón de tareas. Escribirlo en *streaming* es la Fase 5.
     */
    fun exportXml(repo: RepoKey) {
        val layout = StorageLayout.forProject(project) ?: return
        val service = TaskService.getInstance(project)

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("export.xml.progress"), false) {
                private var written: Int = 0
                private var target: Path? = null
                private var failure: String? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    runCatching {
                        val tasks = service.tasksOf(repo)
                        runBlocking { TaskFileStore(layout).write(repo, tasks) }
                        written = tasks.size
                        target = layout.tasksFile(repo)
                    }.onFailure { failure = it.message.orEmpty() }
                }

                override fun onSuccess() {
                    failure?.let {
                        notify(TasklaneBundle.message("export.xml.failed", it), NotificationType.ERROR)
                        return
                    }
                    notify(
                        TasklaneBundle.message("export.xml.done", written, target?.toString().orEmpty()),
                        NotificationType.INFORMATION,
                    )
                }
            },
        )
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
