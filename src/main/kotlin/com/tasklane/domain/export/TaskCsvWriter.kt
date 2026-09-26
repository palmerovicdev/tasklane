package com.tasklane.domain.export

import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TasklaneConfig
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tareas → CSV, **tarea a tarea**, igual que [TaskExporter.Stream].
 *
 * Existe para sacar un repositorio a una tabla: una fila por tarea y una columna por
 * campo, que es lo que se abre en una hoja de cálculo o lo que lee otro programa. Es
 * dominio puro por lo mismo que [TaskExporter]: el formato se fija con un test, no a ojo
 * abriendo el fichero.
 *
 * ## Las decisiones del formato
 *
 * - **RFC 4180**: comas, comillas dobles alrededor de lo que lleve coma, comilla o salto
 *   de línea, las comillas de dentro duplicadas, y `CRLF` al final de cada fila. Una
 *   descripción de varias líneas es **un** campo entre comillas, no varias filas.
 * - **Con BOM.** Sin él, Excel toma un CSV por Latin-1 y enseña «TÃ­tulo» donde pone
 *   «Título»; Numbers, Google Sheets y el `csv` de Python con `utf-8-sig` lo aceptan y
 *   lo quitan. Lo que se exporta aquí se escribe en español.
 * - **Fechas en la zona de quien exporta**, `yyyy-MM-dd HH:mm`, y el vencimiento sólo
 *   con el día: vence al final de ese día —ver `DueDates`—, y la hora de ese final no
 *   la eligió nadie. Una fecha con zona sería más exacta y ninguna hoja de cálculo la
 *   reconoce como fecha.
 * - **La descripción, sin el título ni las capturas**: el título ya tiene su columna, y
 *   una referencia `![](tasklane:<sha>)` fuera del IDE es una cadena que nadie puede
 *   resolver. Es el mismo criterio que la copia al portapapeles —[TaskExporter.detailOf]—.
 * - **Estado y prioridad por su nombre**, que es lo que se lee; el id de la tarea va al
 *   final para quien necesite cruzar filas.
 */
class TaskCsvWriter(
    private val out: Appendable,
    private val config: TasklaneConfig,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Cuántas tareas se han escrito. La cabecera no cuenta. */
    var written: Int = 0
        private set

    private var started = false

    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone)

    /**
     * El BOM y la cabecera. Se escriben aunque no llegue ninguna tarea: un CSV vacío con
     * sus columnas dice «no había nada», uno de cero bytes parece un fallo.
     */
    fun begin() {
        if (started) return
        started = true
        out.append(BOM)
        row(COLUMNS)
    }

    fun task(task: Task) {
        begin()
        val state = config.state(task.stateId)
        val priority = config.priority(task.priorityId)
        row(
            listOf(
                task.title,
                TaskExporter.detailOf(task).joinToString("\n"),
                state?.name ?: task.stateId.value,
                priority?.name ?: task.priorityId.value,
                task.tags.joinToString(", "),
                task.dueDate?.let(date::format).orEmpty(),
                if (task.bookmarked) "yes" else "no",
                task.anchors.joinToString(", ") { it.reference },
                task.links.joinToString(" ") { it.url },
                dateTime.format(task.createdAt),
                dateTime.format(task.updatedAt),
                task.completedAt?.let(dateTime::format).orEmpty(),
                task.id.value,
            ),
        )
        written++
    }

    private fun row(fields: List<String>) {
        fields.forEachIndexed { index, field ->
            if (index > 0) out.append(',')
            out.append(quote(field))
        }
        out.append("\r\n")
    }

    companion object {
        val COLUMNS = listOf(
            "Title", "Description", "State", "Priority", "Tags", "Due", "Bookmarked",
            "Code", "Links", "Created", "Updated", "Completed", "Id",
        )

        private const val BOM = '\uFEFF'

        /** Un campo, entre comillas sólo si le hacen falta. */
        fun quote(field: String): String =
            if (field.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) field
            else "\"" + field.replace("\"", "\"\"") + "\""
    }
}
