package com.tasklane.ui.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.tasklane.domain.model.StateId
import com.tasklane.ui.toolwindow.TasklanePanel

/**
 * *Move to ▸*: mandar la selección a otro estado sin abrir el diálogo de edición.
 *
 * Cambiar de estado es lo que más se hace en una lista con estados, y hasta ahora sólo
 * se podía por el diálogo: abrir un modal, tocar un desplegable y aceptar, tres pasos
 * para lo que en un tablero es arrastrar una tarjeta. El submenú lo pone a dos clics y
 * `⇧⌥←/→` a ninguno.
 *
 * El estado en el que ya está la tarea sale **apagado** y no escondido: la lista de
 * destinos es la misma siempre y en el mismo orden que las pestañas, así que la
 * posición de cada uno se aprende. Una lista que cambia de tamaño según dónde estés
 * obliga a leerla entera cada vez.
 */
internal class MoveToStateActionGroup : ActionGroup(), DumbAware {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val panel = TasklaneDataKeys.PANEL.getData(e.dataContext)
        e.presentation.isVisible = panel != null
        e.presentation.isEnabled = panel != null && panel.isSelectionEditable()
    }

    /**
     * Se recalculan en cada apertura: los estados son configuración del proyecto y
     * pueden haber cambiado de nombre, de orden o de número desde la anterior.
     */
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val panel = e?.let { TasklaneDataKeys.PANEL.getData(it.dataContext) } ?: return EMPTY_ARRAY
        return panel.states.map { MoveToStateAction(it.id, it.name) }.toTypedArray()
    }
}

private class MoveToStateAction(private val target: StateId, name: String) : PanelAction() {

    init {
        templatePresentation.text = name
    }

    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isEnabled = panel != null && panel.isSelectionEditable() && panel.stateId != target
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.moveSelectedTo(target)
    }
}

/**
 * Mover la selección una pestaña a la derecha o a la izquierda.
 *
 * Declaradas para que sean reasignables desde el keymap y salgan en *Search
 * Everywhere*; el atajo que traen de serie —`⇧⌥←/→`— lo instala [TasklanePanel] sobre
 * su propia lista, que es donde tiene sentido.
 *
 * Apagadas cuando no hay vecino en esa dirección, en vez de dar la vuelta: ver
 * [TasklanePanel.neighbourState].
 */
internal abstract class MoveByStateAction(private val delta: Int) : PanelAction() {

    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isEnabled = panel != null &&
            panel.isSelectionEditable() &&
            panel.neighbourState(delta) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = panelOf(e) ?: return
        panel.neighbourState(delta)?.let(panel::moveSelectedTo)
    }
}

internal class MoveToNextStateAction : MoveByStateAction(1)

internal class MoveToPreviousStateAction : MoveByStateAction(-1)

/**
 * Cambiar de pestaña con el teclado: el `Alt+←/→` que ponía el IDE mientras los estados
 * eran `Content`s del `ContentManager` y que se perdió al bajarlos dentro del panel.
 * Quedó anotado como deuda en `docs/plan-rediseno.md` (R3), con la forma de pagarla —un
 * atajo local en la fila— que es exactamente ésta.
 *
 * No hace falta selección: cambiar de pestaña es mirar, no editar.
 */
internal abstract class SelectStateAction(private val delta: Int) : PanelAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panelOf(e)?.neighbourState(delta) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.selectNeighbourState(delta)
    }
}

internal class SelectNextStateAction : SelectStateAction(1)

internal class SelectPreviousStateAction : SelectStateAction(-1)
