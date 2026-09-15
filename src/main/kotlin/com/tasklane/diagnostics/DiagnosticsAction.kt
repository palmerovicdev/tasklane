package com.tasklane.diagnostics

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.command.TaskReducer
import com.tasklane.service.TaskService
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JComponent

/**
 * *Tasklane: Diagnostics* — el §0.4 del `docs/plan-escala.md`.
 *
 * Enseña cuántas tareas hay, cuánto ocupan, cuántas imágenes y de qué peso, y las
 * latencias de la última hora. Existe para que «va lento» deje de ser una impresión:
 * el banco mide en un portátil con un corpus inventado, y esto mide el proyecto de
 * quien se está quejando.
 *
 * **El recorrido va en segundo plano y con barra de progreso.** Pesar el directorio de
 * adjuntos es justo la operación que el §1.6 dice que no termina con diez millones de
 * ficheros; abrirlo desde el EDT convertiría la acción de diagnosticar cuelgues en una
 * forma de provocarlos.
 */
internal class DiagnosticsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val snapshot = TaskService.getInstance(project).snapshot.value
        val metrics = TasklaneMetrics.getInstance(project).snapshot()
        val layout = StorageLayout.forProject(project)

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("diagnostics.progress"), true) {
                private var text: String? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val report = TasklaneDiagnostics.collect(
                        layout = layout,
                        tasksByRepo = snapshot.tasksByRepo,
                        orphansOf = { tasks -> TaskReducer.orphans(tasks).size },
                        metrics = metrics,
                    )
                    text = DiagnosticsReport.render(report)
                }

                override fun onSuccess() {
                    text?.let { DiagnosticsDialog(project, it).show() }
                }
            },
        )
    }
}

/**
 * El informe, en monoespaciado y con un botón para copiarlo.
 *
 * Copiar no es un adorno: el destino natural de este texto es un issue, y un informe
 * que hay que transcribir a mano es un informe que llega a medias.
 */
private class DiagnosticsDialog(project: Project, private val text: String) : DialogWrapper(project) {

    init {
        title = TasklaneBundle.message("diagnostics.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val area = JBTextArea(text).apply {
            isEditable = false
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
            // Sin ajuste de línea: la tabla de latencias está alineada por columnas y
            // partirla la deja ilegible justo donde hay que leerla.
            lineWrap = false
            caretPosition = 0
        }
        return ScrollPaneFactory.createScrollPane(area, true).apply {
            preferredSize = Dimension(JBUI.scale(680), JBUI.scale(520))
        }
    }

    override fun createActions(): Array<Action> = arrayOf(copyAction(), okAction)

    private fun copyAction(): Action = object : DialogWrapperAction(TasklaneBundle.message("diagnostics.copy")) {
        override fun doAction(e: java.awt.event.ActionEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
    }
}
