package com.tasklane.domain.model

/**
 * Una tarea vista desde el código: «en este sitio hay esto pendiente».
 *
 * Es el par y no sólo la tarea porque una tarea puede apuntar a varios sitios, y lo
 * que se pinta en el editor es **un sitio**.
 */
data class AnchoredTask(val task: Task, val anchor: CodeAnchor)

/**
 * Qué tareas apuntan a cada fichero, que es lo que el editor necesita saber para
 * marcar sus líneas.
 *
 * Se da la vuelta al modelo a propósito: las tareas guardan sus anclas, pero quien
 * pinta tiene un fichero abierto y necesita la pregunta contraria. Hacerla recorriendo
 * todas las tareas en cada repintado sería recorrer la lista entera por cada línea
 * visible; así se recorre **una vez por cambio del modelo** y lo que queda es un mapa.
 *
 * **Lo terminal no se marca.** Una tarea en un estado que significa «hecho» ya no es
 * una nota sobre el código: es historia. Dejar su marca en el margen convertiría el
 * margen en un cementerio, y a la semana nadie miraría ninguna. Se mira el estado
 * **actual** y no `completedAt`, porque el estado es lo que el usuario ve en la ventana
 * y lo que acaba de cambiar cuando arrastra una tarea a *Done*.
 *
 * Kotlin puro: recibe el snapshot y devuelve datos, así que se prueba sin IDE.
 */
object AnchoredTasks {

    /**
     * Mapa `ruta → tareas ancladas ahí`, con las rutas tal y como las guarda
     * [CodeAnchor] —relativas al proyecto—. Las tareas de **todos** los repositorios:
     * el fichero abierto no sabe de repositorios, y una nota sobre él vale igual venga
     * de la lista que venga.
     */
    fun byPath(snapshot: TasklaneSnapshot): Map<String, List<AnchoredTask>> {
        val config = snapshot.config
        val byPath = HashMap<String, MutableList<AnchoredTask>>()
        for (tasks in snapshot.tasksByRepo.values) {
            for (task in tasks) {
                if (config.stateOrDefault(task.stateId).terminal) continue
                for (anchor in task.anchors) {
                    byPath.getOrPut(anchor.path) { mutableListOf() } += AnchoredTask(task, anchor)
                }
            }
        }
        val order = order(config)
        return byPath.mapValues { (_, entries) -> entries.sortedWith(order) }
    }

    /**
     * La que manda cuando varias caen en el mismo sitio: la de más prioridad, y a
     * igualdad la tocada más recientemente. Es la que decide el color de la marca y la
     * que encabeza el tooltip, así que un empate resuelto al azar haría que el margen
     * cambiara de color entre dos repintados.
     */
    fun order(config: TasklaneConfig): Comparator<AnchoredTask> =
        compareByDescending<AnchoredTask> { config.priorityOrDefault(it.task.priorityId).order }
            .thenByDescending { it.task.updatedAt.toEpochMilli() }
            .thenBy { it.task.id.value }
}
