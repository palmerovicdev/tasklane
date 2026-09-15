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
import com.tasklane.data.attachment.AttachmentChore
import com.tasklane.data.attachment.AttachmentQuota
import com.tasklane.data.store.StorageLayout
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
 * **Sigue yendo en segundo plano y con barra de progreso**, aunque desde la Fase 4 ya no
 * haga falta tanto: hasta la 2.0 esto pesaba el directorio de adjuntos recorriéndolo
 * —justo la operación que el §1.6 dice que no termina con diez millones de ficheros— y
 * ahora las imágenes las cuenta la tabla `blob`. Lo que queda son agregados de SQLite y
 * tres `Files.size`, pero siguen siendo disco, y el disco no se toca desde el EDT.
 */
internal class DiagnosticsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        showDiagnostics(e.project ?: return)
    }
}

/**
 * El informe, calculado en segundo plano y enseñado en su diálogo.
 *
 * Aparte de la acción porque desde la Fase 4 hay **dos** puertas al mismo informe: el
 * menú, y el aviso de cuota del §4.5 —que ofrece «qué está ocupando el sitio» y tiene
 * que llevar exactamente aquí—.
 */
internal fun showDiagnostics(project: Project) {
    val service = TaskService.getInstance(project)
    val config = service.snapshot.value.config
    val repos = service.snapshot.value.repositories.map { it.key }
    val metrics = TasklaneMetrics.getInstance(project).snapshot()
    val layout = StorageLayout.forProject(project)

    ProgressManager.getInstance().run(
        object : ProgressTask.Backgroundable(project, TasklaneBundle.message("diagnostics.progress"), true) {
            private var text: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val report = TasklaneDiagnostics.collect(
                    layout = layout,
                    // Del almacén, en agregados: siete `count(*)` por repositorio en
                    // vez de recorrer las tareas que hubiera en memoria — que además
                    // ya no las hay.
                    stats = repos.associateWith(service::statsOf),
                    // Y las imágenes, de la tabla `blob`: cinco agregados por
                    // repositorio en vez del recorrido del directorio (§4.2).
                    blobs = repos.associateWith { service.blobStatsOf(it, config.imageMaxSize) },
                    reconciled = repos.filterTo(HashSet()) {
                        service.chore(AttachmentChore.reconcile(it.value)) != null
                    },
                    quotaBytes = AttachmentQuota.bytesOf(config.imageQuotaMegabytes),
                    imageMaxSize = config.imageMaxSize,
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
