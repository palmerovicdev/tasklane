package com.tasklane.ui.toolwindow

import com.intellij.ui.CheckedTreeNode
import com.tasklane.domain.model.Task

/** Nodo del árbol que envuelve una tarea. `checked` == la tarea está en un estado terminal. */
internal class TaskNode(val task: Task, done: Boolean) : CheckedTreeNode(task) {
    init {
        isChecked = done
    }
}
