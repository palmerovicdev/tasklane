package com.tasklane.domain.text

import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TasklaneConfig

/**
 * Los triggers que el usuario escribe en una tarea: `Resolver el fallo !!! ` la deja en
 * *High*, y `@doing ` la crea en *Doing*. El trigger elige la prioridad o el estado y sale
 * del texto: es una forma de escribirlos, no parte de la tarea.
 *
 * Hasta la 2.29 sólo los había de prioridad y sólo valían **al principio** del texto, como
 * prefijo. Desde la 2.30.0 los estados tienen los suyos y los dos valen **en cualquier
 * sitio**, igual que una etiqueta `#api` ([InlineTag]): la UI pregunta al teclear un espacio,
 * un tabulador o un salto de línea por la palabra que acaba de cerrar, y al aceptar por la
 * que se quedó al final sin cerrar.
 *
 * Las reglas existen para no comerse texto que no lo es:
 *
 * - **La palabra entera es el trigger.** `!importante`, `¡¡ya!!` o `@doing.` no disparan
 *   nada: entre espacios, y nada más. Como la palabra entera tiene que coincidir, con `!`,
 *   `!!` y `!!!` definidos `!!!` es siempre `!!!`, sin comparar candidatos.
 * - **Sin mirar mayúsculas.** `@Doing` es `@doing`: quien lo escribe está pensando en el
 *   estado, no en cómo se teclea. Por lo mismo, dos triggers que sólo difieren en eso no
 *   pueden convivir (ver `ConfigValidator`).
 * - **Nada dentro de código**, ni en línea ni en un bloque con valla: un `!!` en un trozo de
 *   código citado es código.
 *
 * Kotlin puro: se testea sin arrancar un IDE.
 */
object TriggerParser {

    /** Lo que elige un trigger. */
    sealed interface Target {
        data class Priority(val id: PriorityId) : Target

        data class State(val id: StateId) : Target
    }

    /** El trigger [trigger], escrito en `[start, end)` del texto, que elige [target]. */
    data class Match(val target: Target, val trigger: String, val start: Int, val end: Int)

    /**
     * El trigger que acaba justo antes de [end] en [text], o `null` si la palabra que acaba
     * ahí no lo es. [end] es donde se acaba de teclear el separador.
     */
    fun endingAt(text: String, end: Int, config: TasklaneConfig): Match? {
        if (!config.triggersEnabled || end <= 0 || end > text.length) return null
        var start = end
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        if (start == end) return null
        val word = text.substring(start, end)
        val target = targetOf(word, config) ?: return null
        if (TypedWord.inCode(text, start)) return null
        return Match(target, word, start, end)
    }

    /**
     * El que se quedó escrito al final, sin separador detrás: aceptar el diálogo con
     * `Revisar el login !!!` escrito no puede dejar `!!!` en la tarea.
     */
    fun trailing(text: String, config: TasklaneConfig): Match? = endingAt(text, text.length, config)

    /** Lo que se borra del texto al aplicar [match], como `[first, last)`: ver [TypedWord.cut]. */
    fun cut(text: String, match: Match): Pair<Int, Int> = TypedWord.cut(text, match.start, match.end)

    /** [text] sin [match]: ver [cut]. */
    fun remove(text: String, match: Match): String {
        val (first, last) = cut(text, match)
        return text.removeRange(first, last)
    }

    /**
     * Las prioridades antes que los estados. Que no coincidan nunca lo asegura la validación
     * de los ajustes; esto es sólo el orden en que se pregunta.
     */
    private fun targetOf(word: String, config: TasklaneConfig): Target? {
        config.priorities.firstOrNull { it.trigger.matches(word) }?.let { return Target.Priority(it.id) }
        config.states.firstOrNull { it.trigger.matches(word) }?.let { return Target.State(it.id) }
        return null
    }

    private fun String?.matches(word: String): Boolean = !isNullOrEmpty() && equals(word, ignoreCase = true)
}
