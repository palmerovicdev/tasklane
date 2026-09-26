package com.tasklane.mcp

import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.DueDates
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.TextNormalizer
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Lo que el agente tiene que leer para corregir su llamada: «no hay un estado *Review*;
 * los que hay son ToDo, Doing y Done». Es un error **esperado**, y el adaptador lo pasa
 * tal cual al protocolo —ver [TasklaneToolset]—; cualquier otra excepción es un fallo
 * del plugin y la plataforma la envuelve como tal.
 */
class ToolError(message: String) : Exception(message)

/**
 * Nombres que escribe un agente → lo que guarda el modelo.
 *
 * Un agente escribe lo que lee en la ventana o en la salida de otra herramienta:
 * `Doing`, `doing`, `high`. Así que se compara **como el buscador** —sin mayúsculas ni
 * acentos, con [TextNormalizer]— y se acepta además el id y un prefijo que sólo encaje
 * con uno. Lo que no encaja falla diciendo qué hay: un agente que recibe la lista se
 * corrige solo en la llamada siguiente; uno que recibe «no encontrado» se inventa otra.
 *
 * Kotlin puro: se prueba sin IDE.
 */
internal object ToolNames {

    fun state(config: TasklaneConfig, name: String): TaskState =
        pick(config.states, name, TaskState::name) { it.id.value }
            ?: throw ToolError("Unknown state \"$name\". States: ${config.states.joinToString { it.name }}.")

    fun priority(config: TasklaneConfig, name: String): TaskPriority =
        pick(config.priorities, name, TaskPriority::name) { it.id.value }
            ?: throw ToolError("Unknown priority \"$name\". Priorities: ${config.priorities.joinToString { it.name }}.")

    /**
     * El repositorio del que se habla: su clave, su nombre o su ruta —absoluta, o relativa
     * a la raíz del proyecto—. Sin nombre, el activo de la ventana, que es donde el
     * usuario está mirando.
     */
    fun repository(repositories: List<RepositoryRef>, active: RepoKey, name: String?): RepositoryRef {
        val wanted = name?.trim().orEmpty()
        if (wanted.isEmpty()) {
            return repositories.firstOrNull { it.key == active }
                ?: repositories.firstOrNull()
                ?: throw ToolError("Tasklane has no repositories in this project yet.")
        }
        val path = wanted.replace('\\', '/').trimEnd('/')
        return repositories.firstOrNull { it.key.value == wanted }
            ?: repositories.firstOrNull { it.rootPath.replace('\\', '/').trimEnd('/') == path }
            ?: pick(repositories, wanted, RepositoryRef::displayName) { it.key.value }
            ?: repositories.firstOrNull { it.rootPath.replace('\\', '/').trimEnd('/').endsWith("/$path") }
            ?: throw ToolError(
                "Unknown repository \"$name\". Repositories: ${repositories.joinToString { it.displayName }}.",
            )
    }

    /**
     * Un vencimiento escrito. `null` == quitarlo; se acepta `YYYY-MM-DD`, `today` y
     * `tomorrow`, y se guarda **al final de ese día**, como los preajustes del diálogo:
     * «para el viernes» vence cuando acaba el viernes, no cuando empieza.
     */
    fun due(text: String, today: LocalDate, zone: ZoneId): Instant? {
        val value = text.trim().lowercase()
        val date = when (value) {
            "", "none", "null", "clear" -> return null
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            else -> try {
                LocalDate.parse(value)
            } catch (_: DateTimeParseException) {
                throw ToolError("Invalid due date \"$text\". Use YYYY-MM-DD, \"today\", \"tomorrow\" or \"none\".")
            }
        }
        return DueDates.atEndOfDay(date, zone)
    }

    /** Exacto, luego el id, luego un prefijo que sólo encaje con uno. */
    private fun <T> pick(items: List<T>, name: String, label: (T) -> String, id: (T) -> String): T? {
        val wanted = TextNormalizer.normalize(name.trim())
        if (wanted.isEmpty()) return null
        items.firstOrNull { TextNormalizer.normalize(label(it)) == wanted }?.let { return it }
        items.firstOrNull { id(it) == name.trim() }?.let { return it }
        return items.filter { TextNormalizer.normalize(label(it)).startsWith(wanted) }.singleOrNull()
    }
}

/** Una casilla de la lista de comprobación, numerada como la ve el agente. */
internal data class CheckItem(val index: Int, val checked: Boolean, val text: String, val offset: Int)

/**
 * Las casillas de una tarea en orden de lectura: la del título, si lo es, y las del
 * cuerpo. Salen de lo mismo que pinta la tarjeta —[Task.titleCheck] y
 * [Task.detailTexts]—, así que lo que va entre vallas de código tampoco cuenta aquí, y
 * el agente numera lo mismo que el usuario ve.
 */
internal fun checkItems(task: Task): List<CheckItem> {
    val boxes = buildList {
        task.titleCheck?.let { add(Triple(it.checked, task.title.substring(it.text.coerceAtMost(task.title.length)), it.offset)) }
        task.detailTexts.forEach { line -> line.check?.let { add(Triple(it.checked, line.text, it.offset)) } }
    }
    return boxes.mapIndexed { i, (checked, text, offset) -> CheckItem(i + 1, checked, text.trim(), offset) }
}

/**
 * Las capturas que el agente pasa por ruta (2.18.0). Kotlin puro: guardarlas es cosa de
 * [TaskTools]; esto dice dónde están y cómo queda el cuerpo.
 */
internal object ToolImages {

    /**
     * La ruta que escribe el agente: absoluta, o relativa a la raíz del proyecto como las
     * del código.
     */
    fun resolve(text: String, basePath: String?): Path {
        val path = try {
            Path.of(text.trim())
        } catch (_: InvalidPathException) {
            throw ToolError("Invalid image path \"$text\".")
        }
        if (path.isAbsolute) return path.normalize()
        val base = basePath ?: throw ToolError("\"$text\" is relative and this project has no root folder. Use an absolute path.")
        return Path.of(base).resolve(path).normalize()
    }

    /**
     * [body] con las referencias de [ids] al final, una por línea, que es como la tarjeta
     * las pinta debajo del texto. Las que el cuerpo ya nombra no se repiten: un agente que
     * repite la llamada porque no vio la respuesta no debe duplicar la captura.
     */
    fun append(body: String, ids: List<AttachmentId>): String {
        val present = ImageRefParser.ids(body)
        val fresh = ids.distinct().filter { it !in present }
        if (fresh.isEmpty()) return body
        return body.trimEnd() + "\n" + fresh.joinToString("\n", transform = ImageRefParser::reference)
    }
}

/**
 * Cómo ve un agente una tarea. Mapas y listas que [ToolJson] escribe tal cual.
 *
 * Dos formas: el **resumen**, para listas —lo de la tarjeta, sin el cuerpo—, y el
 * **detalle**, con el cuerpo entero, las anclas en su línea de hoy y las casillas
 * numeradas. Las fechas van en el día local del usuario (`2026-09-30`) porque es como
 * se escriben de vuelta; las marcas de tiempo, en ISO.
 *
 * @param imageFile el fichero de una captura, o `null` si ya no está. Lo pone quien tiene
 *   el proyecto: ver `AttachmentService.file`.
 */
internal class TaskViews(
    private val config: TasklaneConfig,
    private val repoName: (RepoKey) -> String,
    private val now: Instant,
    private val zone: ZoneId,
    private val imageFile: (RepoKey, AttachmentId) -> Path? = { _, _ -> null },
) {

    fun summary(task: Task): Map<String, Any?> = buildMap<String, Any?> {
        put("id", task.id.value)
        put("title", task.title)
        put("state", config.stateOrDefault(task.stateId).name)
        put("priority", config.priorityOrDefault(task.priorityId).name)
        put("repository", repoName(task.repo))
        if (task.completedAt != null) put("closed", true)
        if (task.bookmarked) put("bookmarked", true)
        if (task.tags.isNotEmpty()) put("tags", task.tags)
        task.dueDate?.let {
            put("due", LocalDate.ofInstant(it, zone).toString())
            if (task.isOverdue(now)) put("overdue", true)
        }
        val (done, total) = task.checklist
        if (total > 0) put("checklist", "$done/$total")
        if (task.anchors.isNotEmpty()) put("code", task.anchors.map { it.reference })
        if (task.attachments.isNotEmpty()) put("images", task.attachments.map { it.id }.distinct().size)
    }

    /**
     * @param currentLine la línea de hoy de un ancla, 0-based, o `null` si su fichero ya
     *   no está. La pone quien tiene el proyecto: ver `CodeAnchors.currentLine`.
     */
    fun detail(task: Task, currentLine: (CodeAnchor) -> Int?): Map<String, Any?> = buildMap<String, Any?> {
        putAll(summary(task) - "code" - "checklist" - "images")
        put("body", task.body)
        put("created", task.createdAt.toString())
        put("updated", task.updatedAt.toString())
        task.completedAt?.let { put("completed", it.toString()) }
        if (task.anchors.isNotEmpty()) {
            put("code", task.anchors.map { anchor ->
                val line = currentLine(anchor)
                buildMap<String, Any?> {
                    put("path", anchor.path)
                    put("line", (line ?: anchor.line) + 1)
                    // Un bloque (2.15.0): su última línea, contada desde donde está hoy la
                    // primera, que es como se mueve el bloque entero.
                    if (anchor.isRange) put("endLine", (line ?: anchor.line) + anchor.span + 1)
                    if (line == null) put("missing", true)
                    else if (line != anchor.line) put("anchoredAtLine", anchor.line + 1)
                    if (anchor.text.isNotEmpty()) put("text", anchor.text)
                }
            })
        }
        val items = checkItems(task)
        if (items.isNotEmpty()) {
            put("checklist", items.map { mapOf("index" to it.index, "checked" to it.checked, "text" to it.text) })
        }
        if (task.links.isNotEmpty()) put("links", task.links.map { it.url }.distinct())
        // Media tarea es una captura, y fuera del IDE `![](tasklane:<sha>)` no dice nada
        // (2.18.0): el servidor MCP sólo devuelve texto, pero el agente abre imágenes del
        // disco. «ref» es lo que aparece en el cuerpo, para saber cuál es cuál.
        val images = task.attachments.map { it.id }.distinct()
        if (images.isNotEmpty()) {
            put("images", images.map { id ->
                val file = imageFile(task.repo, id)
                buildMap<String, Any?> {
                    put("ref", "${ImageRefParser.SCHEME}:${id.value}")
                    if (file != null) put("path", file.toString()) else put("missing", true)
                }
            })
        }
    }
}

/**
 * JSON de **sólo escritura**, lo justo para responder a un agente.
 *
 * El plugin no tiene un parser de JSON a propósito —ver `TaskRows.encodeExtra`— y aquí
 * tampoco hace falta uno: se escriben mapas, listas, cadenas, números y booleanos. Con
 * `kotlinx-serialization` habría que meter su plugin de compilación para escribir diez
 * claves.
 */
internal object ToolJson {

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    string(out, k.toString())
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    append(out, v)
                }
                out.append(']')
            }
            else -> string(out, value.toString())
        }
    }

    private fun string(out: StringBuilder, text: String) {
        out.append('"')
        for (ch in text) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch < ' ' -> out.append("\\u%04x".format(ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }
}
