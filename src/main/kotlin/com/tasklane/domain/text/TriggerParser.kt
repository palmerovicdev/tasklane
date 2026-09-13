package com.tasklane.domain.text

import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.TasklaneConfig

/**
 * Resuelve el prefijo de prioridad que el usuario escribe al principio de una tarea:
 * `!!! Resolver el fallo` crea la tarea `Resolver el fallo` con prioridad *High*.
 *
 * Dos reglas, y las dos existen para evitar falsos positivos:
 *
 * - **Coincidencia más larga primero.** Con los triggers `!`, `!!` y `!!!` definidos,
 *   `!!! algo` tiene que resolver a `!!!`. Probando de más largo a más corto el
 *   primero que encaja es siempre el correcto, sin comparar candidatos entre sí.
 * - **Exige un separador detrás.** `!importante revisar` no dispara nada: el trigger
 *   tiene que ser una palabra suelta, no el principio de una. Esto es lo que hace
 *   que un signo de exclamación como parte del texto no cambie la prioridad sin que
 *   el usuario se entere.
 *
 * Kotlin puro: se testea sin arrancar un IDE. La UI decide *cuándo* llamarlo —en el
 * popup, a cada pulsación— y este objeto sólo dice qué sale.
 */
object TriggerParser {

    /** Un trigger reconocido al principio del texto. */
    data class Match(val priorityId: PriorityId, val trigger: String)

    /** El trigger con el que empieza [text], o `null` si no empieza por ninguno. */
    fun match(text: String, config: TasklaneConfig): Match? {
        if (!config.triggersEnabled) return null

        // De más largo a más corto: así la primera coincidencia ya es la más larga.
        val candidates = config.priorities
            .mapNotNull { p -> p.trigger?.takeIf(String::isNotEmpty)?.let { Match(p.id, it) } }
            .sortedByDescending { it.trigger.length }

        return candidates.firstOrNull { (_, trigger) ->
            // getOrNull y no length >= : un texto que es EXACTAMENTE el trigger no
            // dispara. Mientras se escribe `!!!` la prioridad no debe saltar todavía.
            text.startsWith(trigger) && text.getOrNull(trigger.length).isSeparator()
        }
    }

    /**
     * [text] sin el trigger ni los espacios que lo separan. Es lo que se guarda: el
     * trigger es una forma de escribir la prioridad, no parte de la tarea.
     *
     * Sólo se comen espacios y tabuladores, nunca saltos de línea: el cuerpo es
     * Markdown-lite y una línea en blanco después del título es estructura del
     * usuario, no relleno.
     */
    fun strip(text: String, config: TasklaneConfig): String {
        val match = match(text, config) ?: return text
        return text.substring(match.trigger.length).trimStart(' ', '\t')
    }

    private fun Char?.isSeparator(): Boolean = this == ' ' || this == '\t'
}
