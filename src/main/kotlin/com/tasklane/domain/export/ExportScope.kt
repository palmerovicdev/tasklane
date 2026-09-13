package com.tasklane.domain.export

/**
 * Cuánto se exporta. Es siempre **lo que se está viendo**: si hay una búsqueda
 * activa, el alcance se aplica sobre los resultados y no sobre el estado entero.
 * Exportar algo que no está en pantalla sería imposible de revisar antes de pegarlo.
 */
enum class ExportScope {
    /** Toda la pestaña, grupo de fecha a grupo de fecha. */
    STATE,

    /** Sólo el grupo de fecha donde está la selección. */
    GROUP,

    /** Sólo las filas seleccionadas. */
    SELECTION,
}
