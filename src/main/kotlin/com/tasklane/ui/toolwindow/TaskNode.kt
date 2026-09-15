package com.tasklane.ui.toolwindow

import com.intellij.ui.CheckedTreeNode
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Task
import com.tasklane.paging.Cursor
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/**
 * Nodo del árbol que envuelve una tarea. `checked` == la tarea está en un estado
 * terminal.
 *
 * **Su contenido es mutable desde la Fase 2**, y no por comodidad. El árbol ya no se
 * reconstruye en cada repintado: se sincroniza por diferencias, y eso sólo sirve de
 * algo si el nodo de una tarea que sigue estando **es el mismo objeto**. Un nodo que
 * sobrevive conserva su altura ya medida en la caché del árbol, que es exactamente lo
 * que el §0-bis.4 del plan de escala señala como la pieza que sostiene la fase: medir
 * una fila cuesta ~0,064 ms de EDT, así que lo caro no es crear nodos, es volver a
 * medirlos.
 *
 * `allowsChildren = false` porque el modelo va con `asksAllowsChildren`: es lo que
 * permite que un [GroupNode] vacío se pueda desplegar —y cargar entonces su primera
 * página— sin que el árbol lo tome por una hoja.
 */
internal class TaskNode(task: Task, done: Boolean) : CheckedTreeNode(task) {

    var task: Task = task
        private set

    init {
        isChecked = done
        allowsChildren = false
    }

    /**
     * Pone al día el nodo. Devuelve `true` si algo cambió, que es la señal de que hay
     * que remedir esa fila —y sólo esa—.
     */
    fun update(task: Task, done: Boolean): Boolean {
        if (this.task === task && isChecked == done) return false
        this.task = task
        userObject = task
        isChecked = done
        return true
    }
}

/**
 * Cabecera de un grupo.
 *
 * Extiende [DefaultMutableTreeNode] y **no** `CheckedTreeNode` a propósito: el
 * renderer de `CheckboxTree` sólo pinta el checkbox cuando el nodo es un
 * `CheckedTreeNode`, así que heredar del nodo simple es lo que hace que las
 * cabeceras salgan sin casilla, sin tocar el renderer ni APIs deprecadas.
 *
 * **Nace sin hijos** (§2.5.1 del plan): la cuenta viene del agregado, no de contar lo
 * que cuelga. Un grupo de 800.000 tareas es una cabecera que dice 800.000 y ni una
 * fila más hasta que alguien lo abre.
 */
internal class GroupNode(val key: GroupKey, size: Int) : DefaultMutableTreeNode(key) {

    /** Cuántas tareas hay debajo. Lo dice [com.tasklane.paging.GroupOutline], no los hijos. */
    var size: Int = size
        private set

    fun update(size: Int): Boolean {
        if (this.size == size) return false
        this.size = size
        return true
    }
}

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
internal class EmptyGroupNode(val text: String) : DefaultMutableTreeNode(text, false)

/**
 * El centinela de una página: «… y 4.213 más».
 *
 * Es la otra mitad de la lista acotada. Una página carga [TaskPager.PAGE] filas y
 * deja esto al final; llegar hasta él desplazándose —o pulsarlo— pide la siguiente.
 * Es el mismo gesto que ya existe en *Find in Files*, y el motivo de que el árbol
 * nunca tenga que contener el grupo entero.
 *
 * [Direction.BEFORE] sólo aparece después de un salto: enseñar una tarea que cae en
 * la fila 900.000 abre la ventana a su altura, y entonces hay lista **por encima** de
 * lo cargado. Lleva el cursor con el que se pide ese trozo; el de abajo no lo
 * necesita, porque ampliar hacia abajo es pedir más límite desde el mismo sitio.
 */
internal class MoreNode(
    val direction: Direction,
    remaining: Int,
    cursor: Cursor? = null,
) : DefaultMutableTreeNode(direction, false) {

    var remaining: Int = remaining
        private set

    var cursor: Cursor? = cursor
        private set

    fun update(remaining: Int, cursor: Cursor?): Boolean {
        if (this.remaining == remaining && this.cursor == cursor) return false
        this.remaining = remaining
        this.cursor = cursor
        return true
    }

    enum class Direction { BEFORE, AFTER }
}

/**
 * El modelo del árbol de la lista.
 *
 * Cambia **una** cosa de [DefaultTreeModel]: una cabecera de grupo nunca es una hoja,
 * tenga hijos o no. Desde la Fase 2 un [GroupNode] nace vacío y es al desplegarlo
 * cuando se le pide su primera página; con la regla de fábrica —hoja == sin hijos— el
 * árbol se negaría a desplegar una cabecera cerrada y no habría gesto que disparase
 * esa carga.
 *
 * `DefaultTreeModel` trae para esto el interruptor `asksAllowsChildren`, y no se usa
 * por la misma razón que `SimpleColoredComponent.accessibleContext` obligó a construir
 * el suyo a mano en [StateTabRow]: el campo protegido y el accesor comparten nombre, y
 * en Kotlin `asksAllowsChildren = true` resuelve al **campo** —que desde fuera no se
 * puede tocar— en vez de al `setter`. Decirlo con un `isLeaf` propio es más corto que
 * explicar por qué la otra vía no compila.
 */
internal class TaskTreeModel(root: TreeNode) : DefaultTreeModel(root) {
    override fun isLeaf(node: Any?): Boolean = node !is GroupNode && super.isLeaf(node)
}
