package com.tasklane.domain.export

/**
 * En qué se convierte una tarea al salir al portapapeles.
 *
 * Son dos porque el destino no siempre entiende Markdown: un ticket de YouTrack o un
 * `README` sí, un correo o un chat no, y ahí `- [x]` se lee como ruido en vez de
 * como una casilla marcada.
 */
enum class ExportFormat {
    /** `- [x] título`, con casilla. El destino la pinta como una lista de tareas. */
    MARKDOWN,

    /** `- título`. Guiones y nada más. */
    PLAIN,
}
