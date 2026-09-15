package com.tasklane.bench

import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.LinkExtractor
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * El corpus sintético de la Fase 0 (`docs/plan-escala.md` §0.1): tareas con **la
 * especificación exacta** del §1.1, para que las cifras del banco se puedan comparar
 * con las estimaciones del plan en vez de con una intuición.
 *
 *     texto           500 caracteres
 *     enlaces          10 markdown  →  ~700 car.
 *     imágenes         10 refs      →  10 × 78 = 780 car.
 *     cuerpo total     ~2.000 car.
 *     anclas            5
 *     etiquetas         8
 *     fechas           repartidas en dos años
 *
 * **Determinista de verdad.** La semilla no se lleva de una tarea a la siguiente: cada
 * índice tiene su propio `Random(seed * 31 + i)`, así que [task] devuelve siempre lo
 * mismo para el mismo índice **sin importar en qué orden se pidan** ni cuántas se
 * hayan generado antes. Sin eso, generar 1.000 y generar 1.000.000 darían corpus
 * distintos en sus primeras mil tareas y dos ejecuciones del banco no compararían lo
 * mismo.
 *
 * **Por qué [sequence] y no una lista.** Un millón de tareas materializadas son los
 * ~5,5 GB que el §1.2 estima, y eso es justamente lo que el banco quiere *medir*, no
 * lo que el generador debe *exigir*. Escribir el fichero, contar o alimentar un
 * almacén se hacen con memoria constante; [tasks] materializa y está para los tamaños
 * en los que eso es razonable.
 */
object SyntheticCorpus {

    /** Fija para que dos ejecuciones del banco comparen lo mismo. Ver el KDoc. */
    const val SEED = 20260915L

    /** Lo que pide el §1.1, y lo que hace que el cuerpo mida ~2.000 caracteres. */
    const val TEXT_CHARS = 500
    const val LINKS = 10
    const val IMAGES = 10
    const val ANCHORS = 5
    const val TAGS = 8

    /** Dos años hacia atrás: suficiente para que la agrupación por fecha tenga grupos de verdad. */
    private val SPAN: Duration = Duration.ofDays(730)

    private val WORDS = (
        "login token cache render index query commit branch merge deploy rollback latency " +
            "timeout retry backoff schema migration fixture snapshot reducer selector viewport " +
            "gutter inlay anchor bookmark priority terminal repository attachment thumbnail " +
            "keyset cursor pagination debounce dispatcher coroutine lifecycle disposable"
        ).split(" ")

    private val TAG_VOCABULARY = listOf(
        "api", "ui", "perf", "bug", "infra", "docs", "test", "refactor", "a11y", "i18n",
        "release", "spike", "debt", "security", "db", "search",
    )

    private val HOSTS = listOf(
        "github.com", "youtrack.jetbrains.com", "plugins.jetbrains.com",
        "developer.mozilla.org", "sqlite.org", "kotlinlang.org",
    )

    private val PATHS = listOf(
        "src/main/kotlin/com/tasklane/service/TaskService.kt",
        "src/main/kotlin/com/tasklane/ui/toolwindow/TasklanePanel.kt",
        "src/main/kotlin/com/tasklane/data/store/TaskFileStore.kt",
        "src/main/kotlin/com/tasklane/search/LinearScanIndex.kt",
        "src/main/kotlin/com/tasklane/domain/command/TaskReducer.kt",
        "src/main/kotlin/com/tasklane/data/attachment/AttachmentStore.kt",
    )

    // ------------------------------------------------------------------ tareas

    /**
     * Las [count] tareas, de una en una y con memoria constante. Es lo que hay que
     * usar para escribir un fichero o alimentar un almacén.
     */
    fun sequence(
        count: Int,
        repo: RepoKey = RepoKey.ROOT,
        seed: Long = SEED,
        now: Instant = REFERENCE_NOW,
        blobPool: Int = count,
        config: TasklaneConfig = TasklaneConfig.DEFAULT,
    ): Sequence<Task> = (0 until count).asSequence().map { i ->
        task(i, repo, seed, now, blobPool.coerceAtLeast(1), config)
    }

    /**
     * Materializa el corpus. Sólo para tamaños que quepan: a 1M son los ~5,5 GB del
     * §1.2, que es lo que el banco mide y no lo que el generador impone.
     */
    fun tasks(
        count: Int,
        repo: RepoKey = RepoKey.ROOT,
        seed: Long = SEED,
        now: Instant = REFERENCE_NOW,
        blobPool: Int = count,
        config: TasklaneConfig = TasklaneConfig.DEFAULT,
    ): List<Task> = sequence(count, repo, seed, now, blobPool, config).toList()

    /**
     * La tarea número [index]. Reproducible por sí sola: ver el KDoc de la clase.
     *
     * [blobPool] es cuántos blobs **distintos** puede referenciar el corpus entero, y
     * es la perilla de deduplicación que pide el §0.2: `blobPool = count * IMAGES` no
     * deduplica nada (cada tarea sus diez imágenes propias) y `blobPool = count`
     * deduplica 10:1, que es el escenario optimista que el §1.6 usa para bajar los
     * 4 TB a 400 GB.
     */
    fun task(
        index: Int,
        repo: RepoKey = RepoKey.ROOT,
        seed: Long = SEED,
        now: Instant = REFERENCE_NOW,
        blobPool: Int = 1,
        config: TasklaneConfig = TasklaneConfig.DEFAULT,
    ): Task {
        val random = Random(seed * 31 + index)

        val state = config.states[random.nextInt(config.states.size)]
        val priority = config.priorities[random.nextInt(config.priorities.size)]

        val created = now.minusMillis((random.nextDouble() * SPAN.toMillis()).toLong())
        // Actualizada entre su creación y ahora: nunca en el futuro, que es lo que
        // haría que `DateGrouper` inventara un grupo imposible.
        val updated = created.plusMillis((random.nextDouble() * Duration.between(created, now).toMillis()).toLong())

        val body = body(random, index, blobPool)

        return Task(
            id = TaskId("t-%09d".format(index)),
            repo = repo,
            body = body,
            stateId = state.id,
            priorityId = priority.id,
            createdAt = created,
            updatedAt = updated,
            completedAt = if (state.terminal) updated else null,
            // Disperso como el de producción: ver TasklaneSnapshot.ORDER_GAP.
            order = (index + 1) * 1000L,
            // Derivados, igual que los deja el reducer: un corpus con `links` vacías
            // mediría una UI que no existe —la fila no pintaría ni un enlace—.
            links = LinkExtractor.extract(body),
            attachments = ImageRefParser.parse(body),
            tags = tags(random),
            anchors = anchors(random, index),
            // Una de cada ocho vence, y la mitad de ésas ya está vencida: hace falta
            // que el filtro `overdue` y el pintado en rojo tengan sobre qué trabajar.
            dueDate = if (index % 8 == 0) now.plusMillis(random.nextLong(-30, 30) * MILLIS_PER_DAY) else null,
            bookmarked = index % 20 == 0,
        )
    }

    // ------------------------------------------------------------------ cuerpo

    /**
     * El cuerpo del §1.1: título, ~500 caracteres de texto con diez enlaces repartidos
     * por dentro y las diez referencias de imagen al final.
     *
     * Los enlaces van **dentro del texto** y no en una lista aparte porque
     * `LinkExtractor` mira los bloques de código y la puntuación de la frase: un
     * corpus con los enlaces en su propia línea no ejercitaría nada de eso.
     */
    private fun body(random: Random, index: Int, blobPool: Int): String = buildString(2048) {
        append(sentence(random, 6)).append(" #").append(index).append('\n')

        var text = 0
        var links = 0
        while (text < TEXT_CHARS) {
            val paragraph = sentence(random, random.nextInt(8, 16))
            append(paragraph)
            text += paragraph.length
            // Un enlace cada pocas frases hasta colocar los diez.
            if (links < LINKS && random.nextInt(3) == 0) {
                append(' ').append(link(random, links))
                links++
            }
            append('\n')
        }
        // Los que no hayan cabido por el camino, para que siempre sean diez exactos.
        while (links < LINKS) {
            append(link(random, links)).append('\n')
            links++
        }

        for (i in 0 until IMAGES) {
            append(ImageRefParser.reference(SyntheticBlobs.id(random.nextInt(blobPool)))).append('\n')
        }
    }

    private fun sentence(random: Random, words: Int): String =
        (0 until words).joinToString(" ") { WORDS[random.nextInt(WORDS.size)] }

    private fun link(random: Random, n: Int): String {
        val host = HOSTS[random.nextInt(HOSTS.size)]
        val slug = WORDS[random.nextInt(WORDS.size)]
        return "[${WORDS[random.nextInt(WORDS.size)]} $n](https://$host/$slug/${random.nextInt(100_000)})"
    }

    private fun tags(random: Random): List<String> {
        val picked = LinkedHashSet<String>(TAGS)
        while (picked.size < TAGS) picked += TAG_VOCABULARY[random.nextInt(TAG_VOCABULARY.size)]
        return picked.toList()
    }

    private fun anchors(random: Random, index: Int): List<CodeAnchor> = (0 until ANCHORS).map { i ->
        CodeAnchor.of(
            path = PATHS[(index + i) % PATHS.size],
            line = random.nextInt(2_000),
            column = random.nextInt(80),
            text = sentence(random, 5),
        )
    }

    // -------------------------------------------------------------- fichero XML

    /**
     * Escribe un `tasks.xml` legible por [com.tasklane.data.store.TasksCodec], **en
     * streaming**.
     *
     * A mano y no con `JDOMUtil.writeElement` por la misma razón que el §3.3 parsea
     * con StAX: un fichero de 2,9 GB no cabe en un DOM, y construirlo para generar el
     * corpus haría que el banco muriera antes de empezar a medir justo lo que se
     * quería medir.
     *
     * @return los bytes escritos.
     */
    fun writeTasksXml(
        file: Path,
        count: Int,
        repo: RepoKey = RepoKey.ROOT,
        seed: Long = SEED,
        blobPool: Int = count,
    ): Long {
        Files.createDirectories(file.parent)
        Files.newBufferedWriter(file, StandardCharsets.UTF_8).use { out ->
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            out.write("<tasks version=\"1\" repoKey=\"")
            out.writeEscaped(repo.value)
            out.write("\">\n")
            for (task in sequence(count, repo, seed, blobPool = blobPool)) out.writeTask(task)
            out.write("</tasks>\n")
        }
        return Files.size(file)
    }

    private fun BufferedWriter.writeTask(task: Task) {
        write("  <task")
        attr("id", task.id.value)
        attr("state", task.stateId.value)
        attr("priority", task.priorityId.value)
        attr("order", task.order.toString())
        attr("createdAt", task.createdAt.toEpochMilli().toString())
        attr("updatedAt", task.updatedAt.toEpochMilli().toString())
        task.completedAt?.let { attr("completedAt", it.toEpochMilli().toString()) }
        if (task.tags.isNotEmpty()) attr("tags", task.tags.joinToString(","))
        task.dueDate?.let { attr("dueDate", it.toEpochMilli().toString()) }
        if (task.bookmarked) attr("bookmarked", "true")
        write(">\n")

        for (anchor in task.anchors) {
            write("    <anchor")
            attr("path", anchor.path)
            attr("line", anchor.line.toString())
            if (anchor.column > 0) attr("column", anchor.column.toString())
            if (anchor.text.isNotEmpty()) attr("text", anchor.text)
            write(" />\n")
        }

        write("    <body>")
        writeEscaped(task.body)
        write("</body>\n  </task>\n")
    }

    private fun BufferedWriter.attr(name: String, value: String) {
        write(" ")
        write(name)
        write("=\"")
        writeEscaped(value)
        write("\"")
    }

    /**
     * Escapa al vuelo. Carácter a carácter y no con `replace` encadenados porque esto
     * corre 1.000.000 × 2.000 veces: cinco pasadas de `String.replace` sobre cada
     * cuerpo serían diez cadenas intermedias por tarea.
     */
    private fun BufferedWriter.writeEscaped(text: String) {
        for (c in text) {
            when (c) {
                '&' -> write("&amp;")
                '<' -> write("&lt;")
                '>' -> write("&gt;")
                '"' -> write("&quot;")
                '\n' -> write("&#10;")
                '\r' -> write("&#13;")
                else -> append(c)
            }
        }
    }

    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * Un «ahora» fijo. Con `Instant.now()` el corpus dejaría de ser reproducible: las
     * mismas semillas darían otras fechas mañana y el banco compararía dos cosas
     * distintas.
     */
    val REFERENCE_NOW: Instant = Instant.parse("2026-09-15T12:00:00Z")

    /** Lo que ocupa el corpus en XML, sin escribirlo. Útil para dimensionar el disco antes. */
    fun estimatedXmlBytes(count: Int): Long = count * 2_900L
}
