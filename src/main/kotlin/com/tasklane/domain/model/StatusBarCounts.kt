package com.tasklane.domain.model

import java.time.Instant

/**
 * Qué cuenta el widget de la barra de estado (2.14.0): unos estados y, si se quiere, lo
 * vencido.
 *
 * [states] a `null` es **lo de fábrica**: los estados no terminales de la configuración que
 * haya en cada momento, o sea lo que queda por hacer. Se guarda así y no como la lista que
 * sale hoy para que un estado nuevo creado en *Settings* entre solo mientras nadie haya
 * tocado la elección. En cuanto se elige a mano, la lista es la elegida: un estado nuevo
 * no aparece hasta que se marca, y uno borrado simplemente deja de contarse.
 *
 * Es de la persona, no del proyecto —va a `workspace.xml`—, como el aviso de vencimientos:
 * lo que uno quiere tener delante mientras programa no dice nada del equipo.
 */
data class StatusBarChoice(
    val states: Set<StateId>? = null,
    val overdue: Boolean = true,
) {

    /** Los estados que se cuentan, en el orden de las pestañas. */
    fun statesIn(config: TasklaneConfig): List<TaskState> =
        config.states.filter { if (states == null) !it.terminal else it.id in states }
}

/**
 * Cuántas tareas están vencidas y cuándo vence la siguiente, o `null` si no queda ninguna
 * por vencer. Lo segundo es cuándo hay que volver a contar.
 */
data class DueCount(val overdue: Int, val next: Instant?)

/** Lo que enseña el widget: el repositorio activo, sus estados elegidos y lo vencido. */
data class StatusBarCounts(
    /** El nombre del repositorio, para el tooltip. */
    val repo: String,
    val states: List<StateCount>,
    /** `null` si no se cuenta; cero si se cuenta y no hay nada vencido. */
    val overdue: Int?,
) {
    data class StateCount(val state: TaskState, val count: Int)
}
