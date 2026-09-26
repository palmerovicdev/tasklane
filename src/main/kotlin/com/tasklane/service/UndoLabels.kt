package com.tasklane.service

import com.tasklane.domain.command.Change
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.model.TasklaneConfig

/**
 * Cómo se dice lo que deshace `⌘Z` (2.16.0): «move 3 tasks to Done», «complete a task»,
 * «edit a task». Es lo que sale en la barra de estado al deshacer y al rehacer.
 *
 * Devuelve la clave del texto y sus argumentos, no el texto: así se prueba sin el bundle.
 * Mira el comando **y** lo que cambió de verdad. El comando dice qué se pidió; lo que
 * cambió dice cuántas tareas se movieron —las que ya estaban en *Done* no cuentan— y
 * hacia dónde fue un *Toggle Complete*, que según la tarea completa o reabre.
 */
internal object UndoLabels {

    data class Label(val key: String, val args: List<Any> = emptyList())

    /** [command] es `null` cuando no hubo uno solo: el borrado de un grupo va por tandas. */
    fun of(command: TaskCommand?, change: Change, config: TasklaneConfig): Label {
        val rows = change.rows
        val n = rows.size
        // Soltar una tarjeta en otra columna del tablero (2.17.0) es cambiarla de estado y
        // colocarla entre dos vecinas: se dice lo primero, que es lo que se ve. Colocarla
        // sola, sin nada más, es reordenar.
        val parts = ((command as? TaskCommand.Batch)?.commands ?: listOfNotNull(command))
            .filterNot { it is TaskCommand.Move }
        if (change.moves.isNotEmpty() && (parts.isEmpty() || rows.isEmpty())) return Label("undo.what.reordered")
        if (rows.all { it.before == null }) return Label("undo.what.created", listOf(n))
        if (rows.all { it.after == null }) return Label("undo.what.deleted", listOf(n))

        val afters = rows.mapNotNull { it.after }
        val changed = Label(if (n == 1) "undo.what.edited" else "undo.what.changed", listOf(n))
        if (parts.isEmpty()) return changed

        return when {
            parts.all { it is TaskCommand.ChangeState } ->
                parts.map { (it as TaskCommand.ChangeState).stateId }.distinct().singleOrNull()
                    ?.let { Label("undo.what.state", listOf(n, config.stateOrDefault(it).name)) }
                    ?: changed

            parts.all { it is TaskCommand.ToggleComplete } -> {
                val closed = afters.count { config.stateOrDefault(it.stateId).terminal }
                when (closed) {
                    afters.size -> Label("undo.what.completed", listOf(n))
                    0 -> Label("undo.what.reopened", listOf(n))
                    else -> changed
                }
            }

            parts.all { it is TaskCommand.ChangePriority } ->
                parts.map { (it as TaskCommand.ChangePriority).priorityId }.distinct().singleOrNull()
                    ?.let { Label("undo.what.priority", listOf(n, config.priorityOrDefault(it).name)) }
                    ?: changed

            parts.all { it is TaskCommand.ToggleBookmark } -> when {
                afters.all { it.bookmarked } -> Label("undo.what.bookmarked", listOf(n))
                afters.none { it.bookmarked } -> Label("undo.what.unbookmarked", listOf(n))
                else -> changed
            }

            parts.all { it is TaskCommand.ToggleCheck } && n == 1 -> {
                val row = rows.single()
                val before = row.before?.checklist?.first ?: 0
                val after = row.after?.checklist?.first ?: 0
                Label(if (after > before) "undo.what.checked" else "undo.what.unchecked")
            }

            else -> changed
        }
    }
}
