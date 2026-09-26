package com.tasklane.service

import com.tasklane.domain.command.Change
import com.tasklane.domain.model.RepoKey

/**
 * Lo que `⌘Z` y `⌘⇧Z` pueden deshacer y rehacer en la lista (2.16.0).
 *
 * Hasta la 2.15 sólo se deshacía el último borrado. Ahora cada gesto sobre las tareas deja
 * un [Step] —completar, mover, la prioridad, marcar, reordenar, una casilla, editar, crear,
 * borrar—, y deshacerlo deja otro en la pila de rehacer, como en un editor.
 *
 * **Cada repositorio tiene su historia**, aunque vivan en la misma pila: `⌘Z` con un
 * repositorio abierto deshace lo último **de ése**, y lo de los demás espera a que se
 * vuelva a ellos. Deshacer a ciegas algo de otro repositorio sería cambiar tareas que no
 * se ven, y todo en Tasklane habla del repositorio activo. Un paso es de los repositorios
 * de sus tareas **y** del que estaba abierto al darlo: buscando en todos, lo que se toca
 * puede ser de otro, y `⌘Z` tiene que encontrarlo donde se hizo.
 *
 * Con **dos topes**, por pila: [maxSteps] pasos y [maxRows] filas entre todos, que es lo
 * que pesa —cada fila guarda la tarea de antes y la de después—. Pasado cualquiera se
 * olvida lo más viejo. Un paso que no cabe solo no se guarda, y con él se olvida lo
 * anterior de sus repositorios: `⌘Z` después de algo que no se puede deshacer no debe
 * deshacer, sin decirlo, lo que hubiera detrás.
 *
 * Seguro entre hilos: los lotes grandes se aplican en segundo plano y la lista pregunta
 * desde el EDT. Tiene su propio cerrojo y no el del servicio porque ése puede estar
 * segundos cogido por un lote, y preguntar si `⌘Z` tiene algo que hacer no puede esperar.
 */
internal class UndoHistory(
    private val maxSteps: Int = MAX_STEPS,
    private val maxRows: Int = TaskService.UNDO_LIMIT,
) {

    /**
     * Un gesto que se puede deshacer. [label] es cómo se dice —«move 3 tasks to Done»— y
     * se queda igual al pasar de una pila a la otra: rehacer lo deshecho sigue siendo mover
     * tres tareas a *Done*, aunque [change] sea ya el del deshacer.
     */
    class Step(val change: Change, val label: String, val repos: Set<RepoKey>)

    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()

    /**
     * Un gesto nuevo. Rehacer deja de tener sentido en sus repositorios —lo que había que
     * rehacer partía de una lista que ya no es ésta—, y sólo en los suyos.
     *
     * @return `false` si no cabe, y entonces no se guarda: ver el KDoc de la clase.
     */
    @Synchronized
    fun record(step: Step): Boolean {
        redo.removeAll { it.repos.any(step.repos::contains) }
        if (step.change.size > maxRows) {
            undo.removeAll { it.repos.any(step.repos::contains) }
            return false
        }
        push(undo, step)
        return true
    }

    @Synchronized
    fun canUndo(repo: RepoKey): Boolean = undo.any { repo in it.repos }

    @Synchronized
    fun canRedo(repo: RepoKey): Boolean = redo.any { repo in it.repos }

    /** Saca lo último que se hizo en [repo]. */
    @Synchronized
    fun popUndo(repo: RepoKey): Step? = pop(undo, repo)

    @Synchronized
    fun popRedo(repo: RepoKey): Step? = pop(redo, repo)

    /**
     * Saca [step] de donde esté, si sigue ahí. Lo usa el *Undo* del aviso de borrar, que
     * deshace **ese** borrado aunque después se hayan hecho otras cosas.
     */
    @Synchronized
    fun take(step: Step): Boolean = undo.remove(step)

    /** Lo que dejó un deshacer: se podrá rehacer. */
    @Synchronized
    fun undone(step: Step) = push(redo, step)

    /** Lo que dejó un rehacer: vuelve a la pila de deshacer, sin tocar lo que queda por rehacer. */
    @Synchronized
    fun redone(step: Step) = push(undo, step)

    /** Olvida lo de estos repositorios: se vaciaron, se quitaron, o se hizo algo sin vuelta. */
    @Synchronized
    fun forget(repos: Set<RepoKey>) {
        undo.removeAll { it.repos.any(repos::contains) }
        redo.removeAll { it.repos.any(repos::contains) }
    }

    /** Las filas que guarda la pila de deshacer. Para los tests. */
    @Synchronized
    fun undoRows(): Int = undo.sumOf { it.change.size }

    private fun pop(stack: ArrayDeque<Step>, repo: RepoKey): Step? {
        val index = stack.indexOfLast { repo in it.repos }
        return if (index < 0) null else stack.removeAt(index)
    }

    private fun push(stack: ArrayDeque<Step>, step: Step) {
        stack.addLast(step)
        var rows = stack.sumOf { it.change.size }
        while (stack.size > 1 && (stack.size > maxSteps || rows > maxRows)) {
            rows -= stack.removeFirst().change.size
        }
    }

    companion object {
        /** Los mismos que el editor del IDE trae de fábrica. */
        const val MAX_STEPS = 100
    }
}
