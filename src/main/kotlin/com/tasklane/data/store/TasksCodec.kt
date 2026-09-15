package com.tasklane.data.store

import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import org.jdom.Element
import java.time.Instant

/**
 * Mapeo entre [Task] y XML, a mano sobre JDOM.
 *
 * Por qué NO `com.intellij.util.xmlb.XmlSerializer`, que era la opción obvia:
 * xmlb **descarta los atributos que no conoce**, y eso anula justo la garantía que
 * queremos — que abrir el proyecto con una versión vieja del plugin no borre datos
 * escritos por una nueva. Mapeando a mano, todo atributo desconocido sobrevive en
 * [Task.extra] y se vuelve a escribir tal cual.
 *
 * De paso desaparece la fricción de xmlb con Kotlin, que exige DTOs con `var` y
 * constructor sin argumentos.
 *
 * **Esa garantía llega hasta los atributos.** Las anclas de código son el primer dato
 * que no cabe en uno —son una lista, y una lista en un atributo obliga a inventar un
 * separador y a escaparlo—, así que van como hijos `<anchor>`. Una versión que no las
 * conozca sigue leyendo la tarea entera y sigue escribiéndola bien; lo que no hace es
 * conservarlas al reescribir el fichero. Es el precio de la lista, y se paga aquí y no
 * subiendo [CURRENT_VERSION]: subirla abriría el fichero en **solo lectura** en esa
 * versión vieja, que por un dato opcional es desproporcionado.
 */
object TasksCodec {

    const val CURRENT_VERSION = 1

    private const val ROOT = "tasks"
    private const val TASK = "task"
    private const val BODY = "body"
    private const val ANCHOR = "anchor"

    /**
     * Los atributos que este códec conoce. Público porque el lector en streaming de la
     * Fase 3 —`TasksXmlReader`— tiene que decidir lo mismo que [decodeTask]: lo que no
     * está aquí es de una versión futura y va a `Task.extra`. Dos listas mantenidas a
     * mano divergirían, y la forma de divergir sería perder datos del usuario.
     */
    val KNOWN_ATTRS = setOf(
        "id", "state", "priority", "order", "createdAt", "updatedAt", "completedAt", "tags",
        "dueDate", "bookmarked",
    )

    fun encode(repo: RepoKey, tasks: List<Task>): Element {
        val root = Element(ROOT)
        root.setAttribute("version", CURRENT_VERSION.toString())
        root.setAttribute("repoKey", repo.value)

        for (task in tasks.sortedBy { it.order }) {
            val el = Element(TASK)
            el.setAttribute("id", task.id.value)
            el.setAttribute("state", task.stateId.value)
            el.setAttribute("priority", task.priorityId.value)
            el.setAttribute("order", task.order.toString())
            el.setAttribute("createdAt", task.createdAt.toEpochMilli().toString())
            el.setAttribute("updatedAt", task.updatedAt.toEpochMilli().toString())
            task.completedAt?.let { el.setAttribute("completedAt", it.toEpochMilli().toString()) }
            if (task.tags.isNotEmpty()) el.setAttribute("tags", task.tags.joinToString(","))
            task.dueDate?.let { el.setAttribute("dueDate", it.toEpochMilli().toString()) }
            // Sólo cuando es cierto: un atributo por tarea que casi siempre vale
            // `false` engorda el fichero y el diff de cada guardado sin decir nada.
            if (task.bookmarked) el.setAttribute("bookmarked", "true")

            // Lo que escribió una versión futura vuelve a salir intacto.
            for ((k, v) in task.extra) if (k !in KNOWN_ATTRS) el.setAttribute(k, v)

            for (anchor in task.anchors) {
                val child = Element(ANCHOR)
                child.setAttribute("path", anchor.path)
                child.setAttribute("line", anchor.line.toString())
                // La columna sólo cuando dice algo. Cero es el principio de la línea,
                // que es lo que se asume al leer un ancla sin ella —y lo que escribían
                // las versiones anteriores a la marca en el editor—.
                if (anchor.column > 0) child.setAttribute("column", anchor.column.toString())
                if (anchor.text.isNotEmpty()) child.setAttribute("text", anchor.text)
                el.addContent(child)
            }

            el.addContent(Element(BODY).addContent(task.body))
            root.addContent(el)
        }
        return root
    }

    /** @throws DecodeException si el XML no es interpretable. */
    fun decode(root: Element, repo: RepoKey): Decoded {
        if (root.name != ROOT) throw DecodeException("raíz <${root.name}>, se esperaba <$ROOT>")
        val version = root.getAttributeValue("version")?.toIntOrNull()
            ?: throw DecodeException("falta el atributo version")

        val tasks = root.getChildren(TASK).mapNotNull { decodeTask(it, repo) }
        return Decoded(version = version, tasks = tasks)
    }

    private fun decodeTask(el: Element, repo: RepoKey): Task? {
        // Una tarea sin id es irrecuperable; se salta esa y se conservan las demás,
        // en vez de tumbar el fichero entero.
        val id = el.getAttributeValue("id")?.takeIf { it.isNotBlank() } ?: return null
        val created = el.longAttr("createdAt") ?: 0L
        val updated = el.longAttr("updatedAt") ?: created

        val extra = el.attributes
            .filter { it.name !in KNOWN_ATTRS }
            .associate { it.name to it.value }

        return Task(
            id = TaskId(id),
            repo = repo,
            body = el.getChildText(BODY).orEmpty(),
            stateId = StateId(el.getAttributeValue("state").orEmpty()),
            priorityId = PriorityId(el.getAttributeValue("priority").orEmpty()),
            createdAt = Instant.ofEpochMilli(created),
            updatedAt = Instant.ofEpochMilli(updated),
            completedAt = el.longAttr("completedAt")?.let(Instant::ofEpochMilli),
            order = el.longAttr("order") ?: 0L,
            tags = el.getAttributeValue("tags")
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
            dueDate = el.longAttr("dueDate")?.let(Instant::ofEpochMilli),
            anchors = decodeAnchors(el),
            bookmarked = el.getAttributeValue("bookmarked").toBoolean(),
            extra = extra,
        )
    }

    /**
     * Un ancla sin ruta no lleva a ninguna parte: se descarta esa y se conservan las
     * demás, igual que una tarea sin id no tumba el fichero entero. La línea que falta
     * o no es un número se lee como la primera, que es donde el editor abriría el
     * fichero de todos modos, y lo mismo la columna: el principio de la línea.
     */
    private fun decodeAnchors(el: Element): List<CodeAnchor> =
        el.getChildren(ANCHOR).mapNotNull { child ->
            val path = child.getAttributeValue("path")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            CodeAnchor.of(
                path = path,
                line = child.getAttributeValue("line")?.toIntOrNull() ?: 0,
                column = child.getAttributeValue("column")?.toIntOrNull() ?: 0,
                text = child.getAttributeValue("text").orEmpty(),
            )
        }

    private fun Element.longAttr(name: String): Long? = getAttributeValue(name)?.toLongOrNull()

    data class Decoded(val version: Int, val tasks: List<Task>)

    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
