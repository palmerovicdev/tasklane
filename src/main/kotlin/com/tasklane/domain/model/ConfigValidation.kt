package com.tasklane.domain.model

/**
 * Lo que impide guardar una configuración.
 *
 * Son **datos**, no textos: el dominio detecta el problema y la UI decide cómo se
 * redacta y dónde se marca. Así la validación se testea sin bundle ni Swing, y la
 * página de ajustes puede pintar el aviso en la fila exacta que lo provoca.
 */
sealed interface ConfigProblem {

    /** Fila afectada, o `null` si el problema es de la lista entera. */
    val index: Int?

    /** La tabla de [index]. */
    val list: ConfigList

    data object NoStates : ConfigProblem {
        override val index: Int? get() = null
        override val list: ConfigList get() = ConfigList.STATES
    }

    data object NoPriorities : ConfigProblem {
        override val index: Int? get() = null
        override val list: ConfigList get() = ConfigList.PRIORITIES
    }

    data class BlankStateName(override val index: Int) : ConfigProblem {
        override val list: ConfigList get() = ConfigList.STATES
    }

    data class BlankPriorityName(override val index: Int) : ConfigProblem {
        override val list: ConfigList get() = ConfigList.PRIORITIES
    }

    /**
     * Dos filas con el mismo trigger, sin mirar mayúsculas: el parser no podría decidir. Desde
     * la 2.30.0 también entre un estado y una prioridad, que se escriben en el mismo texto.
     */
    data class DuplicateTrigger(
        override val index: Int,
        val trigger: String,
        override val list: ConfigList = ConfigList.PRIORITIES,
    ) : ConfigProblem

    /** Un trigger con espacios nunca llegaría a dispararse: es una palabra suelta del texto. */
    data class TriggerWithSpace(
        override val index: Int,
        val trigger: String,
        override val list: ConfigList = ConfigList.PRIORITIES,
    ) : ConfigProblem

    /**
     * Un trigger que empieza por `#` se lo disputaría a las etiquetas del cuerpo (2.29.0), y
     * `#` a secas es un título de Markdown.
     */
    data class TriggerLooksLikeTag(
        override val index: Int,
        val trigger: String,
        override val list: ConfigList = ConfigList.PRIORITIES,
    ) : ConfigProblem
}

/** Cuál de las dos tablas de los ajustes. */
enum class ConfigList { STATES, PRIORITIES }

object ConfigValidator {

    fun validate(config: TasklaneConfig): List<ConfigProblem> = buildList {
        // Siempre debe quedar al menos uno de cada: sin estados no hay dónde poner
        // una tarea, y sin prioridades no hay color que pintar.
        if (config.states.isEmpty()) add(ConfigProblem.NoStates)
        if (config.priorities.isEmpty()) add(ConfigProblem.NoPriorities)

        config.states.forEachIndexed { i, state ->
            if (state.name.isBlank()) add(ConfigProblem.BlankStateName(i))
        }
        config.priorities.forEachIndexed { i, priority ->
            if (priority.name.isBlank()) add(ConfigProblem.BlankPriorityName(i))
        }

        // Las prioridades primero: sus triggers son los de siempre, y un estado que choca con
        // uno es el que se acaba de escribir.
        val seen = mutableSetOf<String>()
        fun check(i: Int, raw: String?, list: ConfigList) {
            val trigger = raw?.trim().orEmpty()
            if (trigger.isEmpty()) return
            if (trigger.any(Char::isWhitespace)) add(ConfigProblem.TriggerWithSpace(i, trigger, list))
            if (trigger.startsWith('#')) add(ConfigProblem.TriggerLooksLikeTag(i, trigger, list))
            // Se marca la SEGUNDA aparición: la primera es la que el usuario ya tenía.
            if (!seen.add(trigger.lowercase())) add(ConfigProblem.DuplicateTrigger(i, trigger, list))
        }
        config.priorities.forEachIndexed { i, priority -> check(i, priority.trigger, ConfigList.PRIORITIES) }
        config.states.forEachIndexed { i, state -> check(i, state.trigger, ConfigList.STATES) }
    }
}
