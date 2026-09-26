package com.tasklane.ui.settings

import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TagColor
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import java.util.UUID

/**
 * Copia **mutable** de un estado o una prioridad, para las tablas de los ajustes.
 *
 * El dominio es inmutable, y `ListTableModel` edita el objeto que tiene en la fila.
 * En vez de reemplazar elementos dentro del modelo en cada pulsación —que rompería
 * la selección y el orden— se edita este espejo y se convierte al dominio una sola
 * vez, al pulsar Apply. De paso, cancelar los ajustes no deja rastro.
 *
 * El [id] es `val`: renombrar cambia el nombre, nunca la identidad. Esa es toda la
 * razón por la que renombrar un estado no puede perder tareas.
 */
internal class StateRow(
    val id: StateId,
    var name: String,
    var grouping: Grouping,
    var anchor: DateAnchor,
    var terminal: Boolean,
    var isDefault: Boolean,
    /**
     * No tiene columna: se elige en la barra de la ventana, como la agrupación rápida.
     * Viaja aquí para que aplicar los ajustes no lo devuelva al orden automático.
     */
    val manualOrder: Boolean = false,
) {
    fun toDomain(order: Int) = TaskState(id, name.trim(), order, grouping, anchor, terminal, isDefault, manualOrder)

    companion object {
        fun of(state: TaskState) =
            StateRow(state.id, state.name, state.grouping, state.anchor, state.terminal, state.isDefault, state.manualOrder)

        fun fresh(name: String) =
            StateRow(StateId("s-${UUID.randomUUID()}"), name, Grouping.NONE, DateAnchor.UPDATED, false, false)
    }
}

internal class PriorityRow(
    val id: PriorityId,
    var name: String,
    var colorLight: Int,
    var colorDark: Int,
    var trigger: String?,
    var isDefault: Boolean,
) {
    fun toDomain(order: Int) = TaskPriority(
        id = id,
        name = name.trim(),
        order = order,
        colorLight = colorLight,
        colorDark = colorDark,
        trigger = trigger?.trim()?.takeIf(String::isNotEmpty),
        isDefault = isDefault,
    )

    companion object {
        fun of(priority: TaskPriority) = PriorityRow(
            priority.id, priority.name, priority.colorLight, priority.colorDark, priority.trigger, priority.isDefault,
        )

        fun fresh(name: String) =
            PriorityRow(PriorityId("p-${UUID.randomUUID()}"), name, DEFAULT_LIGHT, DEFAULT_DARK, null, false)

        private const val DEFAULT_LIGHT = 0x6C8EBF
        private const val DEFAULT_DARK = 0x7FA8CC
    }
}

internal fun TasklaneConfig.toRows(): Pair<List<StateRow>, List<PriorityRow>> =
    states.map(StateRow::of) to priorities.map(PriorityRow::of)

internal fun buildConfig(
    states: List<StateRow>,
    priorities: List<PriorityRow>,
    triggersEnabled: Boolean,
    repoDepth: Int,
    imageQuotaMegabytes: Int,
    tagColors: Map<String, TagColor> = emptyMap(),
): TasklaneConfig = TasklaneConfig(
    states = states.mapIndexed { i, row -> row.toDomain(i) },
    priorities = priorities.mapIndexed { i, row -> row.toDomain(i) },
    triggersEnabled = triggersEnabled,
    repoDepth = repoDepth,
    imageQuotaMegabytes = imageQuotaMegabytes,
    tagColors = tagColors,
)
