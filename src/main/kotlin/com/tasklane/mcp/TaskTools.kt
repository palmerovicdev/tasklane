package com.tasklane.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.tasklane.code.CodeAnchors
import com.tasklane.data.attachment.ImageNormalizer
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.model.AnchorReference
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.query.QueryParser
import com.tasklane.domain.text.TextNormalizer
import com.tasklane.search.SearchScope
import com.tasklane.service.AttachmentService
import com.tasklane.service.SearchService
import com.tasklane.service.TaskService
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional

/**
 * Lo que hacen las herramientas MCP de Tasklane (2.12.0), sin nada del protocolo.
 *
 * [TasklaneToolset] es sólo el adaptador: anotaciones, descripciones para el agente y el
 * paso de [ToolError] a error esperado. Aquí está el trabajo, y pasa **por los mismos
 * caminos que la ventana** —[TaskService.apply] con los comandos de siempre, la búsqueda
 * de [SearchService]—: lo que haga un agente se ve en la lista en el acto, cuenta para los
 * avisos y respeta la solo lectura de una base que se está recuperando. Lo que no hace es
 * entrar en la pila de `⌘Z`, que es del usuario: ver [write].
 *
 * **No hay borrar.** Un agente que se equivoca completando se deshace reabriendo; uno
 * que borra, no —los datos no van al VCS y el *Undo* de borrar es del usuario, en su
 * ventana—. Borrar sigue siendo cosa de quien mira la lista.
 *
 * Bloqueante: el adaptador lo llama fuera del EDT.
 */
internal class TaskTools(private val project: Project) {

    private val tasks = TaskService.getInstance(project)
    private val attachments = AttachmentService.getInstance(project)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now: Instant = Instant.now()
    private val snapshot = tasks.snapshot.value
    private val config = snapshot.config

    private val views = TaskViews(config, ::repoName, now, zone, attachments::file)

    fun repositories(): String = ToolJson.write(
        mapOf(
            "repositories" to snapshot.repositories.map { repo ->
                buildMap<String, Any?> {
                    put("name", repo.displayName)
                    put("path", repo.rootPath)
                    if (repo.key == snapshot.activeRepo) put("active", true)
                    if (!repo.available) put("missing", true)
                    repo.branch?.let { put("branch", it) }
                    put("openTasks", openCount(repo))
                }
            },
            "states" to config.states.map { if (it.terminal) "${it.name} (closed)" else it.name },
            "priorities" to config.priorities.sortedByDescending { it.order }.map { it.name },
        ),
    )

    /**
     * Sin [query], estado por estado y en el orden de la lista; con ella, los aciertos de
     * la búsqueda por relevancia. Lo cerrado sólo sale si se pide: lo que un agente
     * pregunta casi siempre es «qué queda».
     */
    fun list(repository: String?, state: String?, query: String?, includeClosed: Boolean, limit: Int): String {
        val max = limit.coerceIn(1, MAX_LIMIT)
        val stateFilter = state?.takeIf(String::isNotBlank)?.let { ToolNames.state(config, it) }
        val all = repository?.trim().equals(ALL, ignoreCase = true)
        val repo = if (all) null else ToolNames.repository(snapshot.repositories, snapshot.activeRepo, repository)

        if (!query.isNullOrBlank()) {
            var parsed = QueryParser.parse(query)
            if (stateFilter != null) parsed = parsed.copy(states = parsed.states + TextNormalizer.normalize(stateFilter.name))
            if (!includeClosed && !parsed.mentionsClosed(config) && stateFilter?.terminal != true) parsed = parsed.copy(done = false)
            val scope = repo?.let { SearchScope.Repo(it.key) } ?: SearchScope.All
            val found = SearchService.getInstance(project).find(parsed, scope)
            return ToolJson.write(
                buildMap<String, Any?> {
                    put("query", query)
                    put("repository", repo?.displayName ?: ALL)
                    put("matches", found.size)
                    put("tasks", found.take(max).map(views::summary))
                    if (found.size > max) put("more", found.size - max)
                },
            )
        }

        val repos = repo?.let(::listOf) ?: snapshot.repositories
        val states = stateFilter?.let(::listOf) ?: config.states.filter { includeClosed || !it.terminal }
        return ToolJson.write(
            mapOf(
                "repositories" to repos.map { r ->
                    mapOf(
                        "repository" to r.displayName,
                        "states" to states.map { s ->
                            val (items, total) = tasks.listed(r.key, s.id, max, now)
                            buildMap<String, Any?> {
                                put("state", s.name)
                                put("total", total)
                                put("tasks", items.map(views::summary))
                                if (total > items.size) put("more", total - items.size)
                            }
                        },
                    )
                },
            ),
        )
    }

    fun get(id: String): String {
        val task = find(id)
        return ToolJson.write(views.detail(task) { CodeAnchors.currentLine(project, it) })
    }

    fun create(
        body: String,
        repository: String?,
        state: String?,
        priority: String?,
        tags: List<String>?,
        due: String?,
        code: List<String>?,
        images: List<String>?,
    ): String {
        if (body.isBlank()) throw ToolError("The task body is empty. Its first line is the title.")
        val repo = ToolNames.repository(snapshot.repositories, snapshot.activeRepo, repository)
        writable(repo.key)
        val id = TaskId.random()
        val command = TaskCommand.Create(
            repo = repo.key,
            body = body,
            stateId = state?.takeIf(String::isNotBlank)?.let { ToolNames.state(config, it).id },
            priorityId = priority?.takeIf(String::isNotBlank)?.let { ToolNames.priority(config, it).id },
            tags = tags.orEmpty(),
            dueDate = due?.takeIf(String::isNotBlank)?.let { ToolNames.due(it, today(), zone) },
            anchors = code.orEmpty().filter(String::isNotBlank).map(::anchorOf),
            id = id,
        )
        // Las capturas, lo último: si un nombre o un ancla fallan, no queda ningún blob suelto.
        write(command.copy(body = ToolImages.append(body, attach(repo.key, images))))
        return saved(id)
    }

    /**
     * Sólo cambia lo que se pasa. Un vencimiento `none` lo quita. Las [images] se añaden
     * al cuerpo que quede —el nuevo, o el de ahora—; las que ya tenía siguen.
     */
    fun update(
        id: String,
        body: String?,
        state: String?,
        priority: String?,
        tags: List<String>?,
        due: String?,
        code: List<String>?,
        bookmarked: Boolean?,
        images: List<String>?,
    ): String {
        val task = find(id)
        writable(task.repo)
        if (body != null && body.isBlank()) throw ToolError("The task body can't be empty. Its first line is the title.")
        val command = TaskCommand.UpdateTask(
            repo = task.repo,
            id = task.id,
            body = body,
            stateId = state?.takeIf(String::isNotBlank)?.let { ToolNames.state(config, it).id },
            priorityId = priority?.takeIf(String::isNotBlank)?.let { ToolNames.priority(config, it).id },
            tags = tags,
            dueDate = due?.let { Optional.ofNullable(ToolNames.due(it, today(), zone)) },
            anchors = code?.filter(String::isNotBlank)?.map(::anchorOf),
        )
        val withImages = ToolImages.append(body ?: task.body, attach(task.repo, images))
        write(command.copy(body = withImages.takeIf { body != null || it != task.body }))
        if (bookmarked != null && bookmarked != task.bookmarked) write(TaskCommand.ToggleBookmark(task.repo, task.id))
        return saved(task.id)
    }

    /**
     * Al primer estado cerrado, **no** alterna como la casilla de la tarjeta: un agente
     * que repite la llamada porque no vio la respuesta no debe reabrir lo que cerró.
     */
    fun complete(id: String): String {
        val task = find(id)
        writable(task.repo)
        val closed = config.states.firstOrNull { it.terminal }
            ?: throw ToolError("No state is marked as closed in Tasklane's settings.")
        if (!config.stateOrDefault(task.stateId).terminal) write(TaskCommand.ChangeState(task.repo, task.id, closed.id))
        return saved(task.id)
    }

    /** Marca o desmarca la casilla [index] (desde 1). Idempotente, por lo mismo que [complete]. */
    fun check(id: String, index: Int, checked: Boolean): String {
        val task = find(id)
        writable(task.repo)
        val items = checkItems(task)
        if (items.isEmpty()) throw ToolError("Task $id has no checklist items (\"- [ ] item\" lines).")
        val item = items.firstOrNull { it.index == index }
            ?: throw ToolError("Task $id has checklist items 1..${items.size}; there is no item $index.")
        if (item.checked != checked) write(TaskCommand.ToggleCheck(task.repo, task.id, item.offset))
        return saved(task.id)
    }

    // ------------------------------------------------------------------ internos

    /**
     * Por [TaskService.apply], pero **fuera de la pila de `⌘Z`** del usuario (2.16.0): si lo
     * del agente entrara, deshacer lo que uno acaba de mover en la lista desharía lo último
     * que tocó el agente por detrás. Y deshacer lo propio respeta lo del agente: vuelve
     * campo a campo y sólo donde nadie ha escrito después.
     */
    private fun write(command: TaskCommand) = tasks.apply(command, undoable = false)

    private fun find(id: String): Task =
        tasks.task(TaskId(id.trim())) ?: throw ToolError("No task with id \"$id\". List tasks to get their ids.")

    /** La tarea tal y como quedó, leída de la base: si no está, no se guardó. */
    private fun saved(id: TaskId): String {
        val task = tasks.task(id) ?: throw ToolError("Tasklane could not save the task. See the IDE log.")
        return ToolJson.write(views.detail(task) { CodeAnchors.currentLine(project, it) })
    }

    private fun writable(repo: RepoKey) {
        if (repo in tasks.snapshot.value.loading) throw ToolError("Tasklane is still importing this repository. Try again in a moment.")
        if (tasks.isReadOnly(repo)) throw ToolError("This repository's tasks are read-only right now. See Tasklane: Diagnostics.")
    }

    /**
     * `src/Auth.kt:42`, o el bloque `src/Auth.kt:42-58` (2.15.0), como en el diálogo: con el
     * texto de la línea para poder reencontrarla.
     */
    private fun anchorOf(text: String): CodeAnchor {
        val reference = AnchorReference.parse(text)
            ?: throw ToolError("Invalid code location \"$text\". Use path:line or path:line-endLine, relative to the project root.")
        refresh(reference.path)
        return when (val lookup = CodeAnchors.fromReference(project, reference)) {
            is CodeAnchors.Lookup.Found -> lookup.anchor
            is CodeAnchors.Lookup.Missing -> throw ToolError("No file at \"${lookup.path}\".")
            is CodeAnchors.Lookup.Folder -> throw ToolError("\"${lookup.path}\" is a folder, not a file.")
            is CodeAnchors.Lookup.OutOfRange ->
                throw ToolError("\"${lookup.path}\" has ${lookup.lines} lines; there is no line ${lookup.line + 1}.")
        }
    }

    /**
     * Las capturas que pasa el agente (2.18.0), guardadas como un fichero que se suelta en
     * el diálogo —tal cual, ver [AttachmentService.attachFile]— en el repositorio de la
     * tarea. Lo que no se puede leer como imagen falla antes de leerlo entero.
     */
    private fun attach(repo: RepoKey, paths: List<String>?): List<AttachmentId> =
        paths.orEmpty().filter(String::isNotBlank).map { text ->
            val file = ToolImages.resolve(text, project.basePath)
            when {
                Files.isDirectory(file) -> throw ToolError("\"$text\" is a folder, not an image.")
                !Files.isRegularFile(file) -> throw ToolError("No file at \"$text\".")
                Files.newInputStream(file).use { ImageNormalizer.sizeOf(it) } == null ->
                    throw ToolError("\"$text\" is not an image Tasklane can read. Use PNG, JPEG, GIF or BMP.")
            }
            attachments.attachFile(repo, file) ?: throw ToolError("Tasklane could not save \"$text\". See the IDE log.")
        }

    /**
     * El agente acaba de escribir el fichero desde fuera del IDE, y el vigilante de disco
     * tarda unos segundos: sin esto el ancla guardaría el texto de la línea de antes de
     * la edición, y [AnchorResolver][com.tasklane.domain.model.AnchorResolver] buscaría
     * una línea que ya no existe. Ver `CodeAnchors.currentLine`.
     */
    private fun refresh(path: String) {
        val absolute = if (path.startsWith("/") || WINDOWS_ROOT.containsMatchIn(path)) path
        else project.basePath?.let { "${it.trimEnd('/')}/$path" } ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolute) ?: return
        VfsUtil.markDirtyAndRefresh(false, false, false, file)
    }

    private fun openCount(repo: RepositoryRef): Int =
        config.states.filter { !it.terminal }.sumOf { tasks.listed(repo.key, it.id, 0, now).second }

    private fun repoName(key: RepoKey): String =
        snapshot.repositories.firstOrNull { it.key == key }?.displayName ?: key.value

    private fun today(): LocalDate = LocalDate.now(zone)

    private companion object {
        const val ALL = "all"
        const val MAX_LIMIT = 200
        val WINDOWS_ROOT = Regex("""^[A-Za-z]:/""")
    }
}
