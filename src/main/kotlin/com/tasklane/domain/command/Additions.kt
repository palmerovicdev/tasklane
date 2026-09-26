package com.tasklane.domain.command

import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.TaskId

/**
 * Lo que un gesto **añadió** a sus tareas (P35): anclas a ficheros a los que la tarea no
 * apuntaba y tareas que ganaron alguna captura. Es lo que hace falta para decir «esto que
 * acabas de hacer, así funciona» la primera vez que alguien lo hace: ver `FirstStepTips`.
 *
 * Sale del [Change] que ya se guarda para deshacer, así que ve lo mismo que `⌘Z`: los
 * gestos de la persona, y no lo que escribe un agente por MCP ni lo que devuelve deshacer.
 *
 * **Un ancla cuenta por fichero**, no por línea: llevar el ancla a otra línea del mismo
 * fichero es moverla, no crearla.
 */
data class Additions(val anchors: List<CodeAnchor>, val withImages: Set<TaskId>) {

    val isEmpty: Boolean get() = anchors.isEmpty() && withImages.isEmpty()

    companion object {
        val NONE = Additions(emptyList(), emptySet())

        fun of(change: Change): Additions {
            val anchors = ArrayList<CodeAnchor>()
            val withImages = LinkedHashSet<TaskId>()
            for (row in change.rows) {
                val after = row.after ?: continue
                val before = row.before
                val known = before?.anchors?.mapTo(HashSet()) { it.path }.orEmpty()
                after.anchors.filterTo(anchors) { it.path !in known }
                val had = before?.attachments?.mapTo(HashSet()) { it.id }.orEmpty()
                if (after.attachments.any { it.id !in had }) withImages += after.id
            }
            return if (anchors.isEmpty() && withImages.isEmpty()) NONE else Additions(anchors, withImages)
        }
    }
}
