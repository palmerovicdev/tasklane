package com.tasklane.domain.agent

import com.tasklane.domain.model.Task

/**
 * Encargar una tarea a un agente (P26): la petición que se le hace y la orden que la lleva a
 * la terminal.
 *
 * Encargarla no la mueve de estado (2.25.1): la tarea sigue donde estaba mientras el agente
 * trabaja, y es él quien la cierra con `tasklane_complete_task`, directa al primer estado
 * cerrado. Ni *Doing* al empezar ni pasos intermedios: lo que dice cómo va es la pestaña.
 *
 * Es Kotlin puro a propósito, como el resto del dominio: lo delicado de esta función no es
 * abrir una pestaña de la terminal sino **qué se escribe en ella**. La petición lleva el título
 * de una tarea, y ese título lo puede haber escrito cualquiera —un agente por MCP, un
 * compañero en un XML importado—; si llegara a la shell sin entrecomillar, un título con
 * `$(…)` sería una orden. Aquí se decide cómo se entrecomilla para cada shell y se prueba
 * sin IDE.
 *
 * Las plantillas son dos, y son de la persona (ver `AgentSettings`):
 *
 * - **La orden**, de fábrica [DEFAULT_COMMAND]. [PROMPT] es la petición **ya entrecomillada**
 *   para la shell de la pestaña; [ID] y [TITLE], igual. Si la orden no dice dónde va la
 *   petición, va al final: `codex` a secas tiene que funcionar.
 * - **La petición**, de fábrica [DEFAULT_PROMPT]. [ID] y [TITLE] van tal cual, porque es
 *   texto para el agente y no para la shell.
 */
object AgentRequest {

    const val PROMPT = "{prompt}"
    const val ID = "{id}"
    const val TITLE = "{title}"

    /** Claude Code, que es el que habla con el servidor MCP del IDE desde la 2.12.0. */
    const val DEFAULT_COMMAND = "claude $PROMPT"

    /**
     * Qué se le pide. Nombra las herramientas de Tasklane porque el agente sólo tiene el id: el
     * cuerpo, la lista y las anclas los lee él con `tasklane_get_task`, en la línea de hoy.
     *
     * Le dice que **no toque el estado** mientras trabaja: con `tasklane_update_task` a mano
     * podría pasarla a *Doing* o a *Review* por su cuenta, y la tarea tiene que ir de donde
     * está a cerrada de un salto.
     */
    const val DEFAULT_PROMPT =
        "Work on the Tasklane task $ID: \"$TITLE\". Read it in full with tasklane_get_task, " +
            "check off its checklist with tasklane_set_checklist_item as you go, and record any " +
            "follow-up work with tasklane_create_task instead of TODO comments. Don't change its " +
            "state while you work: when it is done, close it with tasklane_complete_task."

    /**
     * El párrafo para `CLAUDE.md` o `AGENTS.md`: lo pendiente, a Tasklane y no a `// TODO`. Es
     * lo mismo que dicen las descripciones de las herramientas, pero dicho una vez en el
     * fichero que el agente lee siempre, y no sólo cuando ya ha decidido usarlas.
     */
    val INSTRUCTIONS: String = """
        |## Tasks
        |
        |This project keeps its to-do list in Tasklane, inside the IDE, and not in the code. Use the
        |tasklane_* tools of the IDE's MCP server:
        |
        |- Before starting, look for planned work with tasklane_list_tasks, and read a task in full
        |  with tasklane_get_task.
        |- When you leave something pending, record it with tasklane_create_task, anchored to the code
        |  it is about, instead of writing TODO or FIXME comments.
        |- Check off checklist items with tasklane_set_checklist_item as you finish them, and close a
        |  task you were asked to do with tasklane_complete_task when it is done.
        |
    """.trimMargin()

    /** El nombre de la pestaña, que no crezca más que esto. */
    private const val TAB_TITLE = 40

    /**
     * La petición para [task], en **una línea** y sin caracteres de control.
     *
     * Una línea porque se escribe en una terminal: un salto dentro de las comillas deja a la
     * shell pidiendo el resto, y en PowerShell o `cmd` ni eso. Y sin controles porque lo que se
     * escribe son pulsaciones: un `^C` o un `ESC` en un título los recibiría la terminal, no el
     * agente. Una plantilla en blanco es la de fábrica.
     */
    fun prompt(template: String, task: Task): String =
        oneLine(fill(template.ifBlank { DEFAULT_PROMPT }, mapOf(ID to task.id.value, TITLE to oneLine(task.title))))

    /**
     * La orden que se escribe en la terminal, o `null` si no hay: una orden en blanco es «no
     * quiero que abra nada», y entonces sólo queda copiar la petición.
     */
    fun command(template: String, prompt: String, task: Task, shell: Shell): String? {
        val line = oneLine(template)
        if (line.isEmpty()) return null
        val withPrompt = if (PROMPT in line) line else "$line $PROMPT"
        return fill(
            withPrompt,
            mapOf(
                PROMPT to shell.quote(prompt),
                ID to shell.quote(task.id.value),
                TITLE to shell.quote(oneLine(task.title)),
            ),
        )
    }

    /**
     * Los marcadores de [template], **de una pasada**. Encadenar `replace` volvería a mirar
     * dentro de lo ya sustituido: una tarea titulada «Quitar {title} del correo» metería el
     * título, entrecomillado aparte, en mitad de la petición entrecomillada, y lo que hubiera
     * entre las dos comillas quedaría fuera de ellas.
     */
    private fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { values[it.value] ?: it.value }

    private val PLACEHOLDER = Regex("\\{(?:prompt|id|title)}")

    /**
     * «claude · Arreglar el login»: el programa y la tarea, para distinguir varias pestañas. El
     * programa es la primera palabra de la orden que no sea una variable de entorno
     * (`FOO=1 claude …`).
     */
    fun tabName(template: String, task: Task): String {
        val program = oneLine(template).split(' ')
            .firstOrNull { it.isNotEmpty() && '=' !in it && !it.startsWith('{') }
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?: "agent"
        val title = oneLine(task.title).let { if (it.length > TAB_TITLE) it.take(TAB_TITLE - 1).trimEnd() + "…" else it }
        return if (title.isEmpty()) program else "$program · $title"
    }

    /** Todo espacio —saltos incluidos— a uno solo, y fuera los caracteres de control. */
    private fun oneLine(text: String): String =
        text.filterNot { it.isISOControl() && !it.isWhitespace() }
            .split(WHITESPACE)
            .filter(String::isNotEmpty)
            .joinToString(" ")

    private val WHITESPACE = Regex("\\s+")
}

/**
 * Cómo se entrecomilla un argumento para cada familia de shells.
 *
 * Siempre con la forma **sin expansión** de cada una: entre comillas simples no hay `$`, ni
 * `` ` ``, ni `!` de historia, ni nada que la shell mire. Lo único que queda es la propia
 * comilla, y cada shell la escapa a su manera.
 */
enum class Shell {
    /** sh, bash, zsh, ksh, dash…: la comilla sale, se escribe escapada y vuelve a entrar. */
    POSIX {
        override fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
    },

    /** fish sí admite escapes dentro de las comillas simples, y sólo esos dos. */
    FISH {
        override fun quote(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
    },

    /**
     * PowerShell dobla la comilla, y trata como comilla simple **también** las tipográficas
     * —‘ ’ ‚ ‛—: un título con un apóstrofo bonito cerraría la cadena si no se doblaran.
     */
    POWERSHELL {
        override fun quote(text: String) =
            "'" + text.replace(Regex("['‘’‚‛]")) { it.value + it.value } + "'"
    },

    /**
     * `cmd.exe` no tiene forma de citar sin expansión: entre comillas dobles `&`, `|` o `>` ya
     * no son órdenes, pero `%VAR%` se sigue expandiendo y una `"` no se puede escapar. Así que
     * la comilla doble pasa a simple; lo peor que queda es que se lea una variable de entorno.
     */
    CMD {
        override fun quote(text: String) = "\"" + text.replace('"', '\'') + "\""
    },
    ;

    abstract fun quote(text: String): String

    companion object {
        /** Por el ejecutable con el que arrancó la pestaña: `/bin/zsh`, `pwsh.exe`, `fish`… */
        fun of(executable: String): Shell {
            val name = executable.substringAfterLast('/').substringAfterLast('\\').lowercase().removeSuffix(".exe")
            return when (name) {
                "fish" -> FISH
                "pwsh", "powershell" -> POWERSHELL
                "cmd" -> CMD
                else -> POSIX
            }
        }
    }
}
