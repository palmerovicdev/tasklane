package com.tasklane.domain.model

import java.time.Instant

/**
 * El filtro de vista de la tool window: qué subconjunto de tareas se enseña, sin
 * tocar la consulta.
 *
 * Es deliberadamente **otra cosa** que la búsqueda. El campo de búsqueda tiene
 * sintaxis propia —`state:`, `p:`, `repo:`— y se escribe; esto es un desplegable con
 * cuatro respuestas y se elige. Mezclarlos haría que el filtro tuviese que
 * traducirse a texto de consulta y que borrar la búsqueda se llevase por delante una
 * elección que el usuario no había escrito.
 *
 * [accepts] recibe el instante desde fuera: [OVERDUE] es la única que mira el reloj,
 * y pasarlo permite contar y pintar con el mismo «ahora» —si cada llamada leyera el
 * suyo, el contador de la pestaña y la lista podrían discrepar por un milisegundo—.
 */
enum class TaskFilter {
    ALL,

    /** Sin cerrar: la tarea no ha entrado todavía en un estado terminal. */
    OPEN,

    OVERDUE,

    BOOKMARKED,
    ;

    fun accepts(task: Task, now: Instant): Boolean = when (this) {
        ALL -> true
        OPEN -> task.completedAt == null
        OVERDUE -> task.isOverdue(now)
        BOOKMARKED -> task.bookmarked
    }
}
