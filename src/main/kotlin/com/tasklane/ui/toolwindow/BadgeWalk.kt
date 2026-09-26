package com.tasklane.ui.toolwindow

/**
 * Adónde va el foco del teclado dentro de una tarjeta al pulsar `Tab` o `⇧Tab` (P34).
 *
 * La tarjeta seleccionada tiene su foco —`Enter` la abre, `Espacio` la completa— y, dentro,
 * cada distintivo pulsable: enlaces, casillas, anclas, la prioridad, las etiquetas… Hasta la
 * 2.24 sólo se alcanzaban con el ratón. `Tab` entra en ellos en el orden en que se leen y,
 * pasado el último, deja la lista como siempre; `⇧Tab` vuelve atrás hasta la tarjeta, y
 * desde la tarjeta sale hacia atrás. Es el recorrido de cualquier fila con botones dentro.
 *
 * Salir no lo hace esto: es no hacer nada y dejar que la tecla siga su camino, así que el
 * tabulador de la ventana —estados, buscador, lista— se queda como estaba.
 */
internal object BadgeWalk {

    sealed interface Step {
        /** Al distintivo [index] de la tarjeta. */
        data class To(val index: Int) : Step

        /** A la tarjeta entera: ningún distintivo con el foco. */
        data object Card : Step

        /** Fuera de la lista: la tecla sigue su camino. */
        data object Out : Step
    }

    /**
     * [current] es el distintivo con el foco, o `null` si lo tiene la tarjeta; [count],
     * cuántos tiene ahora —la tarjeta puede haber cambiado desde que se llegó ahí—.
     */
    fun step(current: Int?, count: Int, forward: Boolean): Step = when {
        forward -> {
            val next = if (current == null) 0 else current + 1
            if (next < count) Step.To(next) else Step.Out
        }

        current == null -> Step.Out
        current == 0 || count == 0 -> Step.Card
        else -> Step.To(minOf(current - 1, count - 1))
    }
}
