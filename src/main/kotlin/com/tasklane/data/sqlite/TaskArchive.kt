package com.tasklane.data.sqlite

import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * El **archivo de intercambio**: un `.zip` con un repositorio entero, tareas e imágenes,
 * que se puede llevar a otra máquina o guardar como copia y volver a meter con *Import*.
 *
 * ```
 * tasklane-<repo>-<fecha>.zip
 *   manifest.properties     versión del formato, cuándo, de qué repositorio, cuántas
 *   tasks.xml               el formato de siempre —el que lee TasksXmlReader—
 *   attachments/<sha256>    los bytes de cada imagen que nombra alguna tarea
 * ```
 *
 * **Por qué un zip y no el `tasks.xml` a secas.** Las capturas pegadas viven fuera del XML,
 * en `attachments/`, y el cuerpo sólo lleva `tasklane:<sha>`; exportar sólo el XML y
 * importarlo en otro sitio dejaría cada imagen como un enlace roto. Y **el XML sigue
 * siendo el formato**: si todo lo demás falla, se abre el zip con cualquier programa y el
 * texto de las tareas está ahí, legible.
 *
 * **El manifiesto va el último** porque lleva cuentas que sólo se saben al terminar; se lee
 * primero igualmente, porque [ZipFile] entra por el directorio central.
 *
 * Sin IDE, para probarlo con zips rotos de verdad.
 */
internal object TaskArchive {

    const val FORMAT = 1
    const val MANIFEST = "manifest.properties"
    const val TASKS = "tasks.xml"
    const val ATTACHMENTS = "attachments/"

    /** Un `tasks.xml` de tamaño de tareas de verdad, sin pasar de esto descomprimido. */
    const val MAX_TASKS_BYTES = 4L shl 30

    /** Una imagen suelta. Más que esto no es una captura. */
    const val MAX_ATTACHMENT_BYTES = 256L shl 20

    data class Manifest(
        val format: Int,
        val pluginVersion: String,
        val exportedAt: Instant,
        val repo: String,
        val tasks: Int,
        val attachments: Int,
    )

    /** Por qué no se pudo abrir un archivo. Lo que dice cada uno se le enseña tal cual al usuario. */
    class Invalid(val reason: Reason, message: String) : IOException(message) {
        enum class Reason { NOT_ZIP, NO_MANIFEST, NO_TASKS, FUTURE_FORMAT, UNSAFE }
    }

    // ------------------------------------------------------------------ escribir

    /**
     * Escribe el archivo en [output]. Las tareas se pasan a [tasks] **de una en una tanda**
     * por [TasksXmlWriter]; las imágenes que nombran salen por [attachment], que devuelve
     * los bytes o `null` si esa imagen no está en disco (se cuenta como ausente, no rompe).
     */
    class Writer(output: OutputStream, private val repo: String, private val pluginVersion: String) : AutoCloseable {

        private val zip = ZipOutputStream(NonClosing(output)).apply { setLevel(6) }
        private val wanted = LinkedHashSet<AttachmentId>()
        private var taskCount = 0
        var attachmentCount = 0
            private set
        val missing = ArrayList<AttachmentId>()

        /** `tasks.xml`. [block] recibe el escritor y le va dando tandas. */
        fun tasks(repoKey: RepoKey, block: (TasksXmlWriter) -> Unit) {
            zip.putNextEntry(ZipEntry(TASKS))
            // El escritor cierra su flujo al cerrarse; aquí no: el zip sigue.
            val xml = TasksXmlWriter(NonClosing(zip), repoKey)
            block(xml)
            xml.finish() // vuelca el búfer del escritor al zip
            zip.closeEntry()
            taskCount = xml.written
        }

        /** Apunta las imágenes que nombran estas tareas. Se llama con las mismas tandas. */
        fun note(tasks: List<Task>) {
            for (task in tasks) for (ref in task.attachments) wanted += ref.id
        }

        /** Las imágenes apuntadas con [note]. */
        fun attachments(bytesOf: (AttachmentId) -> InputStream?) {
            for (id in wanted) {
                val stream = bytesOf(id)
                if (stream == null) {
                    missing += id
                    continue
                }
                stream.use {
                    zip.putNextEntry(ZipEntry(ATTACHMENTS + id.value))
                    it.copyTo(zip)
                    zip.closeEntry()
                }
                attachmentCount++
            }
        }

        override fun close() {
            val props = Properties().apply {
                setProperty("format", FORMAT.toString())
                setProperty("pluginVersion", pluginVersion)
                setProperty("exportedAt", Instant.now().toEpochMilli().toString())
                setProperty("repo", repo)
                setProperty("tasks", taskCount.toString())
                setProperty("attachments", attachmentCount.toString())
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            val text = java.io.StringWriter().also { props.store(it, "Tasklane archive") }.toString()
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.close()
        }
    }

    /** Cerrar el zip no cierra el fichero de debajo: quien lo abrió lo fuerza a disco y lo mueve. */
    private class NonClosing(out: OutputStream) : java.io.FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    // ------------------------------------------------------------------- leer

    /** Un archivo abierto y ya comprobado. Se cierra al terminar. */
    class Reader private constructor(private val zip: ZipFile, val manifest: Manifest) : AutoCloseable {

        /** Los ids de las imágenes que trae, sin abrirlas. */
        fun attachmentIds(): List<AttachmentId> =
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(ATTACHMENTS) }
                .map { it.name.removePrefix(ATTACHMENTS) }
                .filter(::safeName)
                .map(::AttachmentId)
                .toList()

        /** `tasks.xml` extraído a [target], con tope de tamaño. */
        fun extractTasks(target: Path) {
            val entry = zip.getEntry(TASKS)
            copyLimited(zip.getInputStream(entry), target, MAX_TASKS_BYTES)
        }

        /** Una imagen extraída a [target]. */
        fun extractAttachment(id: AttachmentId, target: Path) {
            val entry = zip.getEntry(ATTACHMENTS + id.value) ?: throw Invalid(Invalid.Reason.UNSAFE, "falta ${id.value}")
            copyLimited(zip.getInputStream(entry), target, MAX_ATTACHMENT_BYTES)
        }

        override fun close() = zip.close()

        companion object {
            fun open(file: Path): Reader {
                val zip = try {
                    ZipFile(file.toFile())
                } catch (e: IOException) {
                    throw Invalid(Invalid.Reason.NOT_ZIP, "no es un archivo zip legible: ${e.message}")
                }
                try {
                    val manifestEntry = zip.getEntry(MANIFEST) ?: throw Invalid(Invalid.Reason.NO_MANIFEST, "falta $MANIFEST")
                    if (zip.getEntry(TASKS) == null) throw Invalid(Invalid.Reason.NO_TASKS, "falta $TASKS")
                    val props = Properties().also { p -> zip.getInputStream(manifestEntry).use { p.load(it.reader(Charsets.UTF_8)) } }
                    val format = props.getProperty("format")?.toIntOrNull()
                        ?: throw Invalid(Invalid.Reason.NO_MANIFEST, "el manifiesto no dice su formato")
                    if (format > FORMAT) throw Invalid(Invalid.Reason.FUTURE_FORMAT, "formato $format, este plugin llega al $FORMAT")
                    return Reader(
                        zip,
                        Manifest(
                            format = format,
                            pluginVersion = props.getProperty("pluginVersion").orEmpty(),
                            exportedAt = Instant.ofEpochMilli(props.getProperty("exportedAt")?.toLongOrNull() ?: 0L),
                            repo = props.getProperty("repo").orEmpty(),
                            tasks = props.getProperty("tasks")?.toIntOrNull() ?: 0,
                            attachments = props.getProperty("attachments")?.toIntOrNull() ?: 0,
                        ),
                    )
                } catch (e: Throwable) {
                    zip.close()
                    throw e
                }
            }
        }
    }

    /** Un nombre de imagen es un SHA-256: nada de rutas. Es lo que impide un `../` en el zip. */
    fun safeName(name: String): Boolean = name.length == 64 && name.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Copia con tope. Un zip puede declarar un tamaño pequeño y descomprimir a gigas, así
     * que se cuenta lo que **de verdad** sale, no lo que dice la cabecera.
     */
    private fun copyLimited(input: InputStream, target: Path, limit: Long) {
        input.use { source ->
            Files.newOutputStream(target).use { out ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw Invalid(Invalid.Reason.UNSAFE, "una entrada del archivo pasa de $limit bytes")
                    out.write(buffer, 0, n)
                }
            }
        }
    }
}
