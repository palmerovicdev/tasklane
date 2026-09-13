package com.tasklane.ui.quickadd

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Crear una tarea desde cualquier sitio del IDE, sin abrir la Tool Window.
 *
 * El atajo por defecto es `⌘⌥R`, que en el keymap de macOS ya es *Resume Program*.
 * Se mantiene a sabiendas —ver `docs/architecture.html` §14—: el IDE señala el
 * conflicto en *Settings → Keymap* y quien use el depurador a diario lo reasigna.
 * La alternativa, un atajo libre pero incómodo, penalizaría el caso común.
 */
internal class QuickAddAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        QuickAddPopup(project).show()
    }
}
