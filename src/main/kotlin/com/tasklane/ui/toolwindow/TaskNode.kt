package com.tasklane.ui.toolwindow

import com.intellij.ui.CheckedTreeNode
import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.Task
import javax.swing.tree.DefaultMutableTreeNode

/** Nodo del árbol que envuelve una tarea. `checked` == la tarea está en un estado terminal. */
internal class TaskNode(val task: Task, done: Boolean) : CheckedTreeNode(task) {
    init {
        isChecked = done
    }
}

/**
 * Cabecera de un grupo de fecha.
 *
 * Extiende [DefaultMutableTreeNode] y **no** `CheckedTreeNode` a propósito: el
 * renderer de `CheckboxTree` sólo pinta el checkbox cuando el nodo es un
 * `CheckedTreeNode`, así que heredar del nodo simple es lo que hace que las
 * cabeceras salgan sin casilla, sin tocar el renderer ni APIs deprecadas.
 */
internal class GroupNode(val group: DateGroup, val size: Int) : DefaultMutableTreeNode(group)
