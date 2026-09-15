package com.tasklane.data.sqlite

import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.LinkExtractor
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Lee un `tasks.xml` **en streaming**, con memoria constante.
 *
 * Es la regla entera de la migración del §3.3: `TaskFileStore.readFile` llamaba a
 * `JDOMUtil.load`, que construye el DOM completo antes de que exista una sola `Task`
 * —~4,7 KB de objetos por tarea, o sea 4,7 GB a un millón, sólo para el árbol—. Un
 * fichero de 2,9 GB no se puede meter en un DOM, pero sí se puede recorrer con StAX
 * entregando las tareas a tandas.
 *
 * **Lee exactamente el formato que escribe [TasksCodec]**, incluidos los dos detalles
 * que no son obvios: los atributos desconocidos sobreviven en `Task.extra` —es la
 * promesa de que abrir el proyecto con una versión vieja no borra datos escritos por una
 * nueva— y una tarea sin `id` se descarta sin tumbar el fichero entero.
 *
 * **Sin DTD ni entidades externas.** Un `tasks.xml` es un fichero del usuario que puede
 * venir de cualquier sitio —un repositorio compartido, un backup—, y un parser de XML
 * con entidades externas activadas es un lector de ficheros arbitrarios.
 */
internal object TasksXmlReader {

    /** Cuántas tareas se entregan de una vez. Es también el tamaño de la transacción. */
    const val CHUNK = 2_000

    /** Cómo acabó la lectura. */
    sealed interface Result {
        /** Se leyó entero. [tasks] es cuántas se entregaron **en esta pasada**. */
        data class Done(val version: Int, val tasks: Int) : Result

        /** La escribió una versión posterior del plugin: no se toca. */
        data class FutureVersion(val version: Int) : Result

        /** El fichero se rompió por el camino. Lo entregado hasta ahí es válido. */
        data class Broken(val tasks: Int, val cause: Exception) : Result

        /** Quien llamaba pidió parar. */
        data class Cancelled(val tasks: Int) : Result
    }

    /**
     * @param skip cuántas tareas saltarse antes de empezar a entregar. Es lo que hace
     *   reanudable la migración: el fichero no cambia mientras se importa, así que
     *   saltarse las primeras N es exactamente continuar por donde se quedó.
     * @param onChunk recibe cada tanda. Si lanza, la lectura para.
     * @param cancelled se consulta una vez por tanda.
     */
    fun read(
        file: Path,
        repo: RepoKey,
        skip: Int = 0,
        chunk: Int = CHUNK,
        cancelled: () -> Boolean = { false },
        onChunk: (List<Task>) -> Unit,
    ): Result {
        var version = TasksCodec.CURRENT_VERSION
        var seen = 0
        var delivered = 0
        val batch = ArrayList<Task>(chunk)

        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            // El cuerpo de una tarea llega en un solo evento en vez de en trozos.
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }

        val input = BufferedInputStream(Files.newInputStream(file), 1 shl 16)
        var reader: XMLStreamReader? = null
        try {
            reader = factory.createXMLStreamReader(input)
            var builder: Builder? = null

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "tasks" -> {
                            version = reader.getAttributeValue(null, "version")?.toIntOrNull()
                                ?: return Result.Broken(delivered, IllegalStateException("falta el atributo version"))
                            if (version > TasksCodec.CURRENT_VERSION) return Result.FutureVersion(version)
                        }

                        "task" -> builder = Builder(reader)
                        "anchor" -> builder?.anchor(reader)
                        "body" -> builder?.body = reader.elementText
                    }

                    XMLStreamConstants.END_ELEMENT -> if (reader.localName == "task") {
                        val task = builder?.build(repo)
                        builder = null
                        if (task != null) {
                            seen++
                            if (seen > skip) batch += task
                        }
                        if (batch.size >= chunk) {
                            if (cancelled()) return Result.Cancelled(delivered)
                            delivered += batch.size
                            onChunk(ArrayList(batch))
                            batch.clear()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return Result.Broken(delivered, e)
        } finally {
            runCatching { reader?.close() }
            runCatching { input.close() }
        }

        if (batch.isNotEmpty()) {
            delivered += batch.size
            onChunk(batch)
        }
        return Result.Done(version, delivered)
    }

    /** Cuenta las tareas del fichero sin construir ni una. Es la barra de progreso. */
    fun count(file: Path): Int {
        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val input = BufferedInputStream(Files.newInputStream(file), 1 shl 16)
        var reader: XMLStreamReader? = null
        var n = 0
        try {
            reader = factory.createXMLStreamReader(input)
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "task") n++
            }
        } catch (_: Exception) {
            return n
        } finally {
            runCatching { reader?.close() }
            runCatching { input.close() }
        }
        return n
    }

    /**
     * Una tarea a medio leer.
     *
     * Los atributos se copian al empezar el elemento porque el `XMLStreamReader` es un
     * cursor: en cuanto avanza al `<body>`, los atributos del `<task>` ya no están.
     */
    private class Builder(reader: XMLStreamReader) {
        val attributes = HashMap<String, String>(reader.attributeCount)
        val anchors = ArrayList<CodeAnchor>(0)
        var body: String = ""

        init {
            for (i in 0 until reader.attributeCount) {
                attributes[reader.getAttributeLocalName(i)] = reader.getAttributeValue(i)
            }
        }

        fun anchor(reader: XMLStreamReader) {
            // Un ancla sin ruta no lleva a ninguna parte: se descarta esa y se conservan
            // las demás, igual que en `TasksCodec`.
            val path = reader.getAttributeValue(null, "path")?.takeIf(String::isNotBlank) ?: return
            anchors += CodeAnchor.of(
                path = path,
                line = reader.getAttributeValue(null, "line")?.toIntOrNull() ?: 0,
                column = reader.getAttributeValue(null, "column")?.toIntOrNull() ?: 0,
                text = reader.getAttributeValue(null, "text").orEmpty(),
            )
        }

        fun build(repo: RepoKey): Task? {
            val id = attributes["id"]?.takeIf { it.isNotBlank() } ?: return null
            val created = attributes["createdAt"]?.toLongOrNull() ?: 0L
            val updated = attributes["updatedAt"]?.toLongOrNull() ?: created
            return Task(
                id = TaskId(id),
                repo = repo,
                body = body,
                stateId = StateId(attributes["state"].orEmpty()),
                priorityId = PriorityId(attributes["priority"].orEmpty()),
                createdAt = Instant.ofEpochMilli(created),
                updatedAt = Instant.ofEpochMilli(updated),
                completedAt = attributes["completedAt"]?.toLongOrNull()?.let(Instant::ofEpochMilli),
                order = attributes["order"]?.toLongOrNull() ?: 0L,
                links = LinkExtractor.extract(body),
                attachments = ImageRefParser.parse(body),
                tags = attributes["tags"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                anchors = anchors,
                dueDate = attributes["dueDate"]?.toLongOrNull()?.let(Instant::ofEpochMilli),
                bookmarked = attributes["bookmarked"].toBoolean(),
                // Lo que escribió una versión futura vuelve a salir intacto.
                extra = attributes.filterKeys { it !in TasksCodec.KNOWN_ATTRS },
            )
        }
    }
}
