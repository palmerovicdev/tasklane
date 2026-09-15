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
 * pinta tiene un fichero abierto y necesita la pregunta contraria.
 *
 * **Desde la Fase 3 la vuelta la da el índice, no este objeto.** `AnchorMarkers`
 * pregunta por ruta —`anchor_by_path`, un salto y un puñado de filas— en vez de
 * construir el mapa entero del proyecto en cada cambio del modelo, que es lo que hacía
 * que abrir un fichero costara lo mismo que abrir el proyecto (§3.6). Lo que queda aquí
 * es la decisión de **qué se marca** y **cuál manda**, que es lo que había que poder
 * probar sin IDE, y [byPath] sobre una lista para poder seguir probándolo.
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
    fun byPath(tasks: List<Task>, config: TasklaneConfig): Map<String, List<AnchoredTask>> {
        val byPath = HashMap<String, MutableList<AnchoredTask>>()
        for (task in tasks) {
            if (config.stateOrDefault(task.stateId).terminal) continue
            for (anchor in task.anchors) {
                byPath.getOrPut(anchor.path) { mutableListOf() } += AnchoredTask(task, anchor)
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
