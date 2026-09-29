package com.tasklane.domain.agent

/**
 * Un agente al que encargar tareas (2.27.0): cómo se llama en el menú y qué orden escribe en la
 * terminal. Hasta la 2.26 había uno solo, que era la orden sin más.
 *
 * El nombre es opcional: sin él, el menú y la pestaña enseñan el programa de la orden
 * —`claude`, `codex`—, que es como se llamaba la pestaña antes de que hubiera nombres.
 */
data class Agent(val name: String, val command: String) {

    /** Lo que sale en el menú y en la pestaña. Ver [AgentRequest.program]. */
    val label: String
        get() = name.trim().ifEmpty { AgentRequest.program(command) }

    /** Sin espacios alrededor, que es como se guarda y como se compara con lo de fábrica. */
    fun trimmed(): Agent = Agent(name.trim(), command.trim())
}
