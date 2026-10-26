package com.tasklane.ui.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper

/**
 * El estado siguiente o el anterior en el diálogo de la tarea (2.30.0). El atajo de serie
 * —`⌘⌥→/←`— lo instala [TaskEditDialog] sobre sí mismo; declararlas en `plugin.xml` es lo que
 * las hace reasignables en el Keymap. Fuera del diálogo, apagadas.
 *
 * El diálogo se busca por el componente con el foco, que es lo que llega con la tecla: la
 * misma acción sirve al atajo local y a uno que se le asigne en el Keymap.
 */
internal abstract class StepDialogStateAction(private val delta: Int) : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = dialogOf(e)?.canStepState(delta) == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        dialogOf(e)?.stepState(delta)
    }

    private fun dialogOf(e: AnActionEvent): TaskEditDialog? =
        e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)?.let { DialogWrapper.findInstance(it) } as? TaskEditDialog
}

internal class NextDialogStateAction : StepDialogStateAction(1)

internal class PreviousDialogStateAction : StepDialogStateAction(-1)
