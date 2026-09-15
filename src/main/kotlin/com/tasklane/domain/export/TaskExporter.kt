package com.tasklane.domain.export

import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import java.time.LocalDate

/**
 * Colección de tareas → texto de portapapeles.
 *
 * Vive en `domain` y no en un servicio porque no necesita nada del IDE: es una
 * función de `(tareas, configuración, formato)` a `String`, y por eso el formato de
 * salida se puede fijar con un test en vez de comprobándolo a ojo tras copiar.
 *
 * Lo que sale es **lo que se ve**: el mismo orden de la pestaña, los mismos grupos y
 * sólo lo que la búsqueda haya dejado pasar. Quien llama ya ha hecho ese trabajo
 * para pintar el árbol; aquí sólo se le da formato.
 *
 * **Un grupo de fecha en Markdown sale como un parte del día**: `## 2026-09-11` y una
 * lista numerada debajo. Es el formato que se pega en un diario de trabajo o en un
 * informe semanal, que es para lo que se exporta un estado agrupado por fecha; una
 * cabecera que dijera «Done · Hoy» sería falsa mañana, y la casilla `- [x]` es ruido
 * cuando el grupo entero ya significa «esto se hizo ese día». En texto plano no cambia
 * nada: ahí el destino es un correo, no un documento.
 *
 * ## En *streaming* desde la Fase 5
 *
 * Hasta la 2.1 esto devolvía un `String`, y quien exportaba tenía que tener **todas** las
 * tareas en una lista antes de empezar: un estado con 300.000 filas eran 3,9 GB de `Task`
 * vivas más el texto entero. [Stream] escribe tarea a tarea sobre cualquier [Appendable]
 * —un `StringBuilder` para el portapapeles, un `Writer` para un fichero— y lo que retiene
 * es la tarea que está escribiendo.
 *
 * La versión que devuelve un `String` sigue existiendo, y **está construida encima de
 * [Stream]**: hay un único sitio donde se decide el formato, y los tests que lo fijan
 * prueban a la vez las dos puertas.
 */
object TaskExporter {

    /**
     * Un bloque del texto: una cabecera opcional y sus tareas, ya ordenadas.
     *
     * [date] es el día del que habla el grupo cuando la pestaña agrupa por fecha —ver
     * [com.tasklane.domain.model.DateGroup.dayOn]—, y `null` en todo lo demás: en las
     * otras agrupaciones y en los grupos de fecha que no son un día suelto, «esta
     * semana» o «sin fecha». Es lo que decide el formato de parte del día.
     */
    data class Section(val heading: String?, val tasks: List<Task>, val date: LocalDate? = null)

    private const val INDENT = "  "

    fun export(sections: List<Section>, config: TasklaneConfig, format: ExportFormat): String = buildString {
        val stream = Stream(this, config, format)
        for (section in sections) {
            stream.section(section.heading, section.date)
            section.tasks.forEach(stream::task)
        }
    }

    fun export(heading: String?, tasks: List<Task>, config: TasklaneConfig, format: ExportFormat): String =
        export(listOf(Section(heading, tasks)), config, format)

    /**
     * El exportador tarea a tarea.
     *
     * Se abre una sección con [section] y se le echan tareas con [task]; la cabecera no
     * se escribe hasta que llega la primera. Es lo que antes hacía el filtro de
     * «secciones vacías fuera», y en *streaming* no se puede hacer de otra forma: no se
     * sabe si una sección está vacía hasta que se ha terminado de leer.
     *
     * **El separador va delante, no detrás.** El texto de antes era «los bloques unidos
     * por una línea en blanco, con un salto al final», y cada bloque acaba en su propio
     * salto de línea; así que escribir un salto **antes** de cada bloque que no sea el
     * primero da exactamente los mismos bytes sin tener que saber cuál será el último.
     */
    class Stream(
        private val out: Appendable,
        private val config: TasklaneConfig,
        private val format: ExportFormat,
    ) {
        /** Cuántas tareas se han escrito. Es lo que se le dice al usuario al terminar. */
        var written: Int = 0
            private set

        private var blocks = 0
        private var heading: String? = null
        private var day: LocalDate? = null
        private var opened = false
        private var number = 0

        fun section(heading: String?, date: LocalDate? = null) {
            this.heading = heading
            // El día manda sobre la cabecera de la pestaña, y sólo en Markdown: ver la
            // nota de la clase.
            this.day = date.takeIf { format == ExportFormat.MARKDOWN }
            opened = false
            number = 0
        }

        fun task(task: Task) {
            if (!opened) open()
            val day = day
            if (day != null) out.appendNumbered(++number, task) else out.appendTask(task, config, format)
            written++
        }

        private fun open() {
            opened = true
            if (blocks++ > 0) out.append('\n')
            (day?.toString() ?: heading)?.takeIf { it.isNotBlank() }?.let { text ->
                out.append(if (format == ExportFormat.MARKDOWN) "## $text" else text).append('\n')
                out.append('\n')
            }
        }
    }

    private fun Appendable.appendLine(text: CharSequence = ""): Appendable = append(text).append('\n')

    private fun Appendable.appendTask(task: Task, config: TasklaneConfig, format: ExportFormat) {
        val done = config.stateOrDefault(task.stateId).terminal
        append(
            when (format) {
                ExportFormat.MARKDOWN -> if (done) "- [x] " else "- [ ] "
                ExportFormat.PLAIN -> "- "
            },
        )
        append(task.title)
        // Las etiquetas van en la misma línea y no en una propia: `#api` detrás del
        // título es como se escriben, y así el texto pegado se puede volver a leer.
        task.tags.forEach { append(" #").append(it) }
        appendLine()

        // El detalle se sangra bajo su tarea. Es lo que hace que una lista pegada en
        // Markdown siga siendo una lista y no un montón de párrafos sueltos.
        for (line in detailOf(task)) {
            if (line.isEmpty()) appendLine() else appendLine("$INDENT$line")
        }
    }

    /**
     * Una tarea dentro del parte de un día: `1. título`, sin casilla.
     *
     * Sin casilla porque la cabecera del grupo ya dice cuándo pasó esto; marcar además
     * cada línea sería decirlo dos veces. Y numerada porque un parte se lee contando: es
     * la diferencia entre «hice cosas» y «hice cuatro cosas».
     *
     * El detalle se sangra hasta donde empieza el texto de su línea —tres espacios con
     * `1.`, cuatro a partir de `10.`— para que Markdown lo siga leyendo como parte del
     * mismo punto y no como un párrafo suelto que rompe la numeración.
     */
    private fun Appendable.appendNumbered(number: Int, task: Task) {
        val marker = "$number. "
        append(marker).append(task.title)
        task.tags.forEach { append(" #").append(it) }
        appendLine()

        val indent = " ".repeat(marker.length)
        for (line in detailOf(task)) {
            if (line.isEmpty()) appendLine() else appendLine("$indent$line")
        }
    }

    /**
     * El cuerpo menos el título, sin las líneas en blanco de los extremos. Las de en
     * medio se conservan: separan párrafos que el usuario escribió aparte.
     *
     * Las referencias a imágenes se quitan. Fuera del IDE un `![](tasklane:<sha>)` no
     * es una imagen ni un enlace: es una cadena de 64 caracteres que nadie puede
     * resolver, y lo que se exporta se pega en un ticket o en un correo. Una línea
     * que sólo contenía la referencia desaparece con ella en vez de quedarse en
     * blanco; una que la llevaba junto a texto conserva el texto.
     *
     * Lo usa también [TaskCsvWriter] para su columna de descripción: el criterio de qué
     * es «el detalle» de una tarea tiene que ser uno solo.
     */
    internal fun detailOf(task: Task): List<String> {
        val title = task.titleRange
        if (title.isEmpty()) return emptyList()
        val eol = task.body.indexOf('\n', title.last + 1)
        if (eol < 0) return emptyList()
        val detail = task.body.substring(eol + 1)
        return detail
            .lines()
            .mapNotNull { line ->
                val stripped = ImageRefParser.strip(line)
                if (stripped == line) line else stripped.trim().takeIf { it.isNotEmpty() }
            }
            .map { it.trimEnd() }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
    }
}
