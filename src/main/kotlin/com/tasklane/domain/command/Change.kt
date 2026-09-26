package com.tasklane.domain.command

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId

/**
 * Lo que un gesto cambió, para poder deshacerlo (2.16.0).
 *
 * Una fila por tarea tocada, **como estaba y como quedó**: [RowChange.before] es `null` si
 * el gesto la creó, y [RowChange.after] si la borró. Aparte van los [moves], porque
 * reordenar no cambia nada que el reducer vea: el `ord` nuevo lo decide el almacén, y
 * puede reespaciar el estado entero. Por eso no se guarda el `ord` de antes sino **entre
 * qué dos tareas estaba**, que sigue siendo verdad después de reespaciar. Cada movimiento
 * ya es el que la devuelve a su sitio.
 *
 * Se guardan las dos fotos y no «el comando inverso» porque casi nada tiene inverso:
 * completar sella `completedAt` y mueve `updatedAt`, y no hay comando que los devuelva a
 * como estaban. Con las dos fotos, deshacer es volver a poner lo de antes **allí donde
 * sigue estando lo de después**: ver `TaskCommand.Revert`.
 */
data class Change(
    val rows: List<RowChange> = emptyList(),
    val moves: List<TaskCommand.Move> = emptyList(),
) {
    val isEmpty: Boolean get() = rows.isEmpty() && moves.isEmpty()

    /** Cuánto pesa guardarlo: una fila o un movimiento cuentan uno. */
    val size: Int get() = rows.size + moves.size

    /** Los repositorios de las tareas que toca. */
    val repos: Set<RepoKey> get() = rows.mapTo(LinkedHashSet()) { it.repo }.apply { moves.mapTo(this) { it.repo } }

    /** Las dos juntas, como si fueran un gesto: lo usa el borrado de un grupo, que va por tandas. */
    operator fun plus(other: Change): Change = Change(rows + other.rows, moves + other.moves)

    companion object {
        val NONE = Change()

        /**
         * El cambio que describen unas mutaciones sobre [before], las tareas que el comando
         * nombraba tal como se leyeron **dentro de la misma transacción**. Sólo cuentan las
         * que llevan filas: las de alcance de proyecto no salen de un gesto sobre la lista.
         */
        fun of(before: List<Task>, mutations: List<Mutation>, moves: List<TaskCommand.Move> = emptyList()): Change {
            val old = HashMap<TaskId, Task>(before.size * 2)
            before.associateByTo(old) { it.id }
            val rows = ArrayList<RowChange>()
            for (mutation in mutations) {
                when (mutation) {
                    is Mutation.Upsert -> mutation.tasks.forEach { rows += RowChange(old[it.id], it) }
                    is Mutation.Delete -> mutation.ids.forEach { id -> old[id]?.let { rows += RowChange(it, null) } }
                    else -> Unit
                }
            }
            return if (rows.isEmpty() && moves.isEmpty()) NONE else Change(rows, moves)
        }
    }
}

/** Una tarea antes y después de un gesto. Nunca las dos `null`. */
data class RowChange(val before: Task?, val after: Task?) {
    init {
        require(before != null || after != null) { "Un cambio sin tarea" }
    }

    val id: TaskId get() = (before ?: after)!!.id
    val repo: RepoKey get() = (before ?: after)!!.repo
}
