package com.tasklane.ui.toolwindow

import com.intellij.ui.CheckedTreeNode
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Task
import javax.swing.tree.DefaultMutableTreeNode

/** Nodo del árbol que envuelve una tarea. `checked` == la tarea está en un estado terminal. */
internal class TaskNode(val task: Task, done: Boolean) : CheckedTreeNode(task) {
    init {
        isChecked = done
    }
}

/**
 * Cabecera de un grupo.
 *
 * Extiende [DefaultMutableTreeNode] y **no** `CheckedTreeNode` a propósito: el
 * renderer de `CheckboxTree` sólo pinta el checkbox cuando el nodo es un
 * `CheckedTreeNode`, así que heredar del nodo simple es lo que hace que las
 * cabeceras salgan sin casilla, sin tocar el renderer ni APIs deprecadas.
 */
internal class GroupNode(val key: GroupKey, val size: Int) : DefaultMutableTreeNode(key)

/**
 * La única fila de un grupo que existe pero está vacío.
 *
 * Hay grupos que valen la pena aunque no tengan nada —«hoy» es el caso: que esté
 * vacío es justo la respuesta que se busca—, y una cabecera sola encima de otra
 * cabecera se lee como un fallo de pintado. El texto llega hecho: quién construye la
 * sección sabe si el estado es terminal, y «no queda nada por hacer» no significa lo
 * mismo en *ToDo* que en *Done*.
 *
 * Es [DefaultMutableTreeNode] por lo mismo que [GroupNode]: sin casilla.
 */
internal class EmptyGroupNode(val text: String) : DefaultMutableTreeNode(text)
