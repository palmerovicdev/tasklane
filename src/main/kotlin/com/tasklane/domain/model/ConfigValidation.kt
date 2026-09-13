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

    data object NoStates : ConfigProblem {
        override val index: Int? get() = null
    }

    data object NoPriorities : ConfigProblem {
        override val index: Int? get() = null
    }

    data class BlankStateName(override val index: Int) : ConfigProblem

    data class BlankPriorityName(override val index: Int) : ConfigProblem

    /** Dos prioridades con el mismo trigger: el parser no podría decidir. */
    data class DuplicateTrigger(override val index: Int, val trigger: String) : ConfigProblem

    /**
     * Un trigger con espacios nunca llegaría a dispararse: la resolución mira el
     * primer token del texto y exige un espacio detrás.
     */
    data class TriggerWithSpace(override val index: Int, val trigger: String) : ConfigProblem
}

object ConfigValidator {

    fun validate(config: TasklaneConfig): List<ConfigProblem> = buildList {
        // Siempre debe quedar al menos uno de cada: sin estados no hay dónde poner
        // una tarea, y sin prioridades no hay color que pintar.
        if (config.states.isEmpty()) add(ConfigProblem.NoStates)
        if (config.priorities.isEmpty()) add(ConfigProblem.NoPriorities)

        config.states.forEachIndexed { i, state ->
            if (state.name.isBlank()) add(ConfigProblem.BlankStateName(i))
        }

        val seenTriggers = mutableMapOf<String, Int>()
        config.priorities.forEachIndexed { i, priority ->
            if (priority.name.isBlank()) add(ConfigProblem.BlankPriorityName(i))

            val trigger = priority.trigger?.trim().orEmpty()
            if (trigger.isEmpty()) return@forEachIndexed
            if (trigger.any(Char::isWhitespace)) add(ConfigProblem.TriggerWithSpace(i, trigger))
            // Se marca la SEGUNDA aparición: la primera es la que el usuario ya tenía.
            if (seenTriggers.putIfAbsent(trigger, i) != null) {
                add(ConfigProblem.DuplicateTrigger(i, trigger))
            }
        }
    }
}
