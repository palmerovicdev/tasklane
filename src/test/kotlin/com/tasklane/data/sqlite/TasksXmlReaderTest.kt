package com.tasklane.data.sqlite

import com.intellij.openapi.util.JDOMUtil
import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * **El lector en streaming tiene que leer exactamente lo que leía el de antes.**
 *
 * `TasksXmlReader` sustituye a `JDOMUtil.load` + `TasksCodec.decode` en el único sitio
 * donde el fichero puede pesar 2,9 GB. La forma de confiar en él es la misma que con los
 * dos paginadores y los dos índices de búsqueda: **dos implementaciones sobre la misma
 * entrada, comparadas tarea a tarea**. El decodificador de JDOM se queda como
 * referencia, y es lo que este test usa de patrón.
 *
 * Lo que se comprueba además y no es evidente: que un atributo que este plugin no conoce
 * —o sea, escrito por una versión futura— **sobrevive** al viaje. Es la promesa de
 * `TasksCodec` y se rompería sin ruido.
 */
class TasksXmlReaderTest {

    private val repo = RepoKey.ROOT

    private fun <T> withXml(tasks: List<Task>, block: (Path) -> T): T {
        val dir = Files.createTempDirectory("tasklane-xml")
        return try {
            val file = dir.resolve("tasks.xml")
            val xml = JDOMUtil.writeElement(TasksCodec.encode(repo, tasks))
            Files.writeString(file, xml, StandardCharsets.UTF_8)
            block(file)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun readAll(file: Path, chunk: Int = 7, skip: Int = 0): Pair<TasksXmlReader.Result, List<Task>> {
        val out = ArrayList<Task>()
        val result = TasksXmlReader.read(file, repo, skip = skip, chunk = chunk) { out += it }
        return result to out
    }

    @Test
    fun `lee lo mismo que el decodificador de JDOM`() {
        val expected = SyntheticCorpusTasks(60)

        withXml(expected) { file ->
            val (result, actual) = readAll(file)

            assertTrue(result is TasksXmlReader.Result.Done)
            assertEquals(expected.size, actual.size)

            // La única diferencia con el decodificador viejo, y es a propósito: el
            // lector nuevo **deriva del cuerpo** los enlaces y las imágenes. Antes eso
            // lo hacía el reducer al recibir `Loaded`; ahora no hay `Loaded`, y sin
            // derivarlos aquí `has:link` no encontraría nada y el recolector de
            // adjuntos creería que no hay ninguna imagen referenciada — que es como se
            // borra una captura que sí se estaba usando.
            val reference = TasksCodec.decode(JDOMUtil.load(file), repo).tasks.map {
                it.copy(
                    links = com.tasklane.domain.text.LinkExtractor.extract(it.body),
                    attachments = com.tasklane.domain.text.ImageRefParser.parse(it.body),
                )
            }
            assertEquals(reference, actual)
            assertTrue("y lo derivado es de verdad", actual.all { it.links.isNotEmpty() && it.attachments.isNotEmpty() })
        }
    }

    @Test
    fun `un atributo de una version futura sobrevive`() {
        val task = StoreFixture.task("t1", extra = mapOf("campoFuturo" to "valor", "otro" to "2"))

        withXml(listOf(task)) { file ->
            val read = readAll(file).second.single()
            assertEquals(mapOf("campoFuturo" to "valor", "otro" to "2"), read.extra)
        }
    }

    @Test
    fun `una version futura del formato no se importa`() {
        withXml(listOf(StoreFixture.task("t1"))) { file ->
            // Se sube la versión a mano: es lo que haría un plugin más nuevo.
            val bumped = Files.readString(file).replace(
                "version=\"${TasksCodec.CURRENT_VERSION}\"",
                "version=\"${TasksCodec.CURRENT_VERSION + 1}\"",
            )
            Files.writeString(file, bumped)

            val (result, tasks) = readAll(file)

            assertEquals(TasksXmlReader.Result.FutureVersion(TasksCodec.CURRENT_VERSION + 1), result)
            assertTrue("no se toca ni una tarea", tasks.isEmpty())
        }
    }

    /**
     * Es lo que hace reanudable la migración: el fichero no cambia mientras se importa,
     * así que saltarse las primeras N es exactamente continuar por donde se quedó.
     */
    @Test
    fun `saltarse las primeras es continuar por donde iba`() {
        val expected = SyntheticCorpusTasks(40)

        withXml(expected) { file ->
            val primera = readAll(file, chunk = 10).second.take(25)
            val resto = readAll(file, chunk = 10, skip = 25).second

            assertEquals(40, primera.size + resto.size)
            assertEquals(expected, primera + resto)
        }
    }

    /** Un fichero cortado a la mitad: lo que se pudo leer se entrega, y se dice que se rompió. */
    @Test
    fun `un fichero truncado entrega lo que se pudo leer`() {
        withXml(SyntheticCorpusTasks(40)) { file ->
            val text = Files.readString(file)
            Files.writeString(file, text.substring(0, text.length / 2))

            val (result, tasks) = readAll(file, chunk = 5)

            assertTrue("tiene que decir que se rompió", result is TasksXmlReader.Result.Broken)
            assertTrue("y haber salvado algo", tasks.isNotEmpty())
        }
    }

    @Test
    fun `cancelar para y dice cuantas llevaba`() {
        withXml(SyntheticCorpusTasks(40)) { file ->
            var delivered = 0
            val result = TasksXmlReader.read(
                file,
                repo,
                chunk = 10,
                cancelled = { delivered >= 20 },
            ) { delivered += it.size }

            assertEquals(TasksXmlReader.Result.Cancelled(20), result)
            assertEquals(20, delivered)
        }
    }

    @Test
    fun `contar no construye ni una tarea`() {
        withXml(SyntheticCorpusTasks(33)) { file ->
            assertEquals(33, TasksXmlReader.count(file))
        }
    }

    /** Un corpus con de todo: etiquetas, anclas, fechas, marcas y cuerpos con saltos. */
    private fun SyntheticCorpusTasks(count: Int): List<Task> = (0 until count).map { i ->
        StoreFixture.task(
            id = "t%03d".format(i),
            body = "Tarea $i\nCon salto y https://ejemplo.test/$i\n![](tasklane:${"a".repeat(64)})",
            createdAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i.toLong()),
            updatedAt = Instant.parse("2026-02-01T00:00:00Z").plusSeconds(i.toLong()),
            completedAt = if (i % 3 == 0) Instant.parse("2026-03-01T00:00:00Z") else null,
            dueDate = if (i % 4 == 0) Instant.parse("2026-04-01T00:00:00Z") else null,
            bookmarked = i % 5 == 0,
            tags = if (i % 2 == 0) listOf("api", "lote") else emptyList(),
            anchors = listOf(CodeAnchor.of("src/File$i.kt", i, i % 7, "linea $i")),
            order = (i + 1) * 1000L,
        )
    }
}
