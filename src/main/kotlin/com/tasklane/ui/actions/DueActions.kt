package com.tasklane.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.tasklane.domain.model.DueDates
import com.tasklane.domain.model.DuePreset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * *Due ▸*: el vencimiento de la selección sin abrir el diálogo (P31).
 *
 * Prioridad y estado se cambiaban ya desde el menú y sobre una selección; el vencimiento,
 * sólo desde el diálogo y tarea a tarea. Son los preajustes del diálogo —ver [DueDates]—,
 * el mismo calendario para una fecha concreta y *Clear* para quitarla.
 *
 * Las entradas se declaran en `plugin.xml`, y no se calculan como las de *Priority*,
 * porque no dependen de la configuración: así se pueden asignar en el *Keymap* —«vence
 * hoy» con una tecla— y salen en *Search Everywhere* con su nombre largo.
 */
internal class DueActionGroup : DefaultActionGroup(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val panel = TasklaneDataKeys.PANEL.getData(e.dataContext)
        e.presentation.isVisible = panel != null
        e.presentation.isEnabled = panel != null && panel.isSelectionEditable()
    }
}

/**
 * Un preajuste. Sale apagado cuando **todas** las seleccionadas ya vencen justo ahí, como
 * la prioridad que ya tienen todas en *Priority ▸*.
 *
 * El instante se calcula al pulsar y no al construir la acción, que vive lo que el IDE:
 * «hoy» de ayer sería una tarea vencida.
 */
internal abstract class DuePresetAction(private val preset: DuePreset) : PanelAction() {

    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        val due = due()
        e.presentation.isEnabled = panel != null &&
            panel.isSelectionEditable() &&
            panel.selectedTasks().any { it.dueDate != due }
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.setDueSelected(due())
    }

    private fun due(): Instant {
        val zone = ZoneId.systemDefault()
        return DueDates.resolve(preset, LocalDate.now(zone), zone, WeekFields.of(Locale.getDefault()).firstDayOfWeek)
    }
}

internal class DueTodayAction : DuePresetAction(DuePreset.TODAY)

internal class DueTomorrowAction : DuePresetAction(DuePreset.TOMORROW)

internal class DueEndOfWeekAction : DuePresetAction(DuePreset.END_OF_WEEK)

internal class DueNextWeekAction : DuePresetAction(DuePreset.NEXT_WEEK)

internal class PickDueAction : PanelAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panelOf(e)?.isSelectionEditable() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.pickDueSelected()
    }
}

/** Apagada si ninguna de las seleccionadas tiene vencimiento: no hay nada que quitar. */
internal class ClearDueAction : PanelAction() {

    override fun update(e: AnActionEvent) {
        val panel = panelOf(e)
        e.presentation.isEnabled = panel != null &&
            panel.isSelectionEditable() &&
            panel.selectedTasks().any { it.dueDate != null }
    }

    override fun actionPerformed(e: AnActionEvent) {
        panelOf(e)?.setDueSelected(null)
    }
}
