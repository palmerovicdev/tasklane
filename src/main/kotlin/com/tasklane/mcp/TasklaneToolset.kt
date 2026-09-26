package com.tasklane.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Tasklane para agentes de IA (2.12.0): las tareas del proyecto como herramientas del
 * servidor MCP que trae el IDE.
 *
 * Con esto un agente —Claude Code, Junie, AI Assistant o cualquier cliente MCP conectado
 * al IDE— puede leer qué hay por hacer, abrir una tarea con su cuerpo y sus anclas en la
 * línea de hoy, apuntar lo que deja pendiente en vez de sembrar `// TODO`, marcar la
 * lista de comprobación según avanza y cerrar la tarea al terminar. La lista pasa a ser
 * la memoria compartida entre quien programa y el agente.
 *
 * Sólo existe si el plugin *MCP Server* está activo: se registra desde
 * `tasklane-mcp.xml`, que `plugin.xml` carga como dependencia opcional. El servidor en sí
 * lo enciende el usuario en *Settings → Tools → MCP Server*; Tasklane no abre nada.
 *
 * Este fichero es **sólo el adaptador**: nombres, descripciones —que es lo que lee el
 * agente para decidir cuándo usar cada una— y el paso de [ToolError] a un error esperado
 * del protocolo. El trabajo está en [TaskTools]. Los nombres llevan `tasklane_` delante
 * porque un agente ve a la vez las herramientas de todos los plugins, y `list_tasks` a
 * secas no dice de quién es.
 */
class TasklaneToolset : McpToolset {

    @McpTool(name = "tasklane_list_repositories", title = "List Tasklane repositories")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Lists the repositories that have a Tasklane task list in this project, with how many open
        |tasks each one has, plus the configured task states and priorities (highest first).
        |Tasklane is the developer's to-do list inside the IDE; each task belongs to one repository.
        """,
    )
    suspend fun listRepositories(): String = tools { repositories() }

    @McpTool(name = "tasklane_list_tasks", title = "List Tasklane tasks")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Lists the developer's Tasklane tasks (their to-do list inside the IDE). Without a query,
        |returns open tasks state by state, in the same order the developer sees them (bookmarked
        |first, then by priority). With a query, returns the best matches by relevance.
        |Each task comes as a summary; call tasklane_get_task with its id to read the full body.
        """,
    )
    suspend fun listTasks(
        @McpDescription("Free text and operators, as in Tasklane's search box: state:Doing p:high #tag file:Auth.kt has:code has:image is:done. Empty to list without searching.")
        query: String? = null,
        @McpDescription("Only tasks in this state, e.g. \"Doing\".")
        state: String? = null,
        @McpDescription("Repository name or path. Empty for the one active in the Tasklane window, \"all\" for every repository.")
        repository: String? = null,
        @McpDescription("Also return closed tasks.")
        includeClosed: Boolean = false,
        @McpDescription("Maximum number of tasks per state (or of matches), 1-200.")
        limit: Int = 30,
    ): String = tools { list(repository, state, query, includeClosed, limit) }

    @McpTool(name = "tasklane_get_task", title = "Get a Tasklane task")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Returns one Tasklane task in full: its Markdown body (the first line is the title), state,
        |priority, tags, due date, links, numbered checklist items, and the code locations it is
        |anchored to. Code locations give the line where the anchored code is now, which may differ
        |from where it was anchored, and "endLine" when they cover a block of lines; "missing": true
        |means the file no longer exists. Images (screenshots, diagrams) appear in the body as
        |![](tasklane:<id>); "images" gives the path of each image file on disk, so you can open it
        |and see it. Keep those references if you rewrite the body.
        """,
    )
    suspend fun getTask(
        @McpDescription("The task id, as returned by tasklane_list_tasks.")
        id: String,
    ): String = tools { get(id) }

    @McpTool(name = "tasklane_create_task", title = "Create a Tasklane task")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        """
        |Adds a task to the developer's Tasklane list. Use it to record follow-up work you leave
        |pending instead of writing TODO comments in the code. The body is Markdown; its first
        |line is the title, and "- [ ] step" lines become a checklist. Anchor it to the code it is
        |about with "code", and attach screenshots or diagrams with "images". Returns the created task.
        """,
    )
    suspend fun createTask(
        @McpDescription("Markdown body. The first line is the title.")
        body: String,
        @McpDescription("Repository name or path. Empty for the one active in the Tasklane window.")
        repository: String? = null,
        @McpDescription("State name. Empty for the default state (usually \"ToDo\").")
        state: String? = null,
        @McpDescription("Priority name, e.g. \"High\". Empty for the default one.")
        priority: String? = null,
        @McpDescription("Tags, without #.")
        tags: List<String>? = null,
        @McpDescription("Due date: YYYY-MM-DD, \"today\" or \"tomorrow\".")
        dueDate: String? = null,
        @McpDescription("Code locations as path:line or path:line-endLine for a block, relative to the project root, e.g. src/main/kotlin/Auth.kt:42 or src/main/kotlin/Auth.kt:42-58.")
        code: List<String>? = null,
        @McpDescription("Image files to attach, such as a screenshot of the result or a diagram you generated: absolute paths or relative to the project root. PNG, JPEG, GIF or BMP. They are added at the end of the body.")
        images: List<String>? = null,
    ): String = tools { create(body, repository, state, priority, tags, dueDate, code, images) }

    @McpTool(name = "tasklane_update_task", title = "Update a Tasklane task")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        """
        |Changes a Tasklane task. Only the fields you pass change. Passing "body" replaces the whole
        |Markdown body, so read the task first and keep what should stay, including its
        |![](tasklane:<id>) image references. Moving it to a closed state closes it; to reopen, move
        |it to an open one. Returns the updated task.
        """,
    )
    suspend fun updateTask(
        @McpDescription("The task id.")
        id: String,
        @McpDescription("New Markdown body; replaces the current one. The first line is the title.")
        body: String? = null,
        @McpDescription("New state name.")
        state: String? = null,
        @McpDescription("New priority name.")
        priority: String? = null,
        @McpDescription("New tags, replacing the current ones. An empty list removes them.")
        tags: List<String>? = null,
        @McpDescription("New due date: YYYY-MM-DD, \"today\", \"tomorrow\", or \"none\" to remove it.")
        dueDate: String? = null,
        @McpDescription("New code locations as path:line or path:line-endLine, replacing the current ones. An empty list removes them.")
        code: List<String>? = null,
        @McpDescription("Bookmark or unbookmark it. Bookmarked tasks stay at the top of their list.")
        bookmarked: Boolean? = null,
        @McpDescription("Image files to add at the end of the body: absolute paths or relative to the project root. PNG, JPEG, GIF or BMP. The images the task already has stay; to remove one, pass a body without its ![](tasklane:<id>) reference.")
        images: List<String>? = null,
    ): String = tools { update(id, body, state, priority, tags, dueDate, code, bookmarked, images) }

    @McpTool(name = "tasklane_complete_task", title = "Complete a Tasklane task")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE, idempotentHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Marks a Tasklane task as done by moving it to the closed state. Call it when you have
        |finished the work the task describes. Completing an already closed task changes nothing.
        |Returns the task.
        """,
    )
    suspend fun completeTask(
        @McpDescription("The task id.")
        id: String,
    ): String = tools { complete(id) }

    @McpTool(name = "tasklane_set_checklist_item", title = "Check a Tasklane checklist item")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE, idempotentHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Checks or unchecks one checklist item ("- [ ] step" line) of a Tasklane task, by the index
        |tasklane_get_task shows. Use it to record progress on a multi-step task. Returns the task.
        """,
    )
    suspend fun setChecklistItem(
        @McpDescription("The task id.")
        id: String,
        @McpDescription("The item number, starting at 1.")
        index: Int,
        @McpDescription("true to check it, false to uncheck it.")
        checked: Boolean = true,
    ): String = tools { check(id, index, checked) }

    /**
     * El proyecto de la llamada —el cliente lo dice, o es el único abierto; si no, la
     * plataforma ya responde con la lista de proyectos— y el trabajo fuera del EDT: leer
     * la base y escribir son bloqueantes.
     */
    private suspend fun tools(block: TaskTools.() -> String): String {
        val project = currentCoroutineContext().project
        return withContext(Dispatchers.IO) {
            try {
                TaskTools(project).block()
            } catch (e: ToolError) {
                mcpFail(e.message ?: "Tasklane error")
            }
        }
    }
}
