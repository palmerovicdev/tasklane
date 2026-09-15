package com.tasklane.domain.export

/**
 * En qué fichero se guardan **todas** las tareas de un repositorio.
 *
 * Es un enum aparte de [ExportFormat] y no un tercer valor suyo porque sirven a cosas
 * distintas: [ExportFormat] es cómo se escribe una tarea **para pegarla** —y es un
 * interruptor del menú, Markdown sí o no—; esto es a qué fichero se saca un repositorio
 * entero, y una de las tres salidas, CSV, no es texto para leer sino una tabla.
 */
enum class FileFormat(val extension: String) {
    /** Una fila por tarea, con cabecera. Para una hoja de cálculo o para otro programa. */
    CSV("csv"),

    /** Un apartado por estado y `- [ ] título` debajo, como la copia al portapapeles. */
    MARKDOWN("md"),

    /** Lo mismo con guiones a secas. */
    PLAIN("txt"),
}
