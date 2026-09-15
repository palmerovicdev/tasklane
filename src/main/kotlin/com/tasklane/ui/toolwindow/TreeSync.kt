package com.tasklane.ui.toolwindow

import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Task
import com.tasklane.paging.Cursor
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.MutableTreeNode
import javax.swing.tree.TreeNode

/**
 * Qué fila tiene que haber en cada sitio. Lo compone [TasklanePanel]; lo aplica
 * [TreeSync].
 *
 * Es una descripción, no un nodo: [TreeSync] decide si hace falta crear uno o si el
 * que ya está sirve. La [key] es la identidad de la fila —lo que hace que «la misma
 * tarea» siga siendo el mismo nodo aunque su contenido cambie o se mueva de sitio—.
 */
internal sealed interface Row {

    val key: Any

    class OfGroup(val group: GroupKey, val size: Int) : Row {
        override val key: Any get() = group
    }

    class OfTask(val task: Task, val done: Boolean) : Row {
        override val key: Any get() = task.id
    }

    class OfEmpty(val text: String) : Row {
        override val key: Any get() = EMPTY_ROW
    }

    class OfMore(val direction: MoreNode.Direction, val remaining: Int, val cursor: Cursor? = null) : Row {
        override val key: Any get() = direction
    }
}

/**
 * Deja un nodo del árbol con exactamente las filas que se le piden, **moviendo y
 * actualizando** en vez de rehacer.
 *
 * Es la pieza que sostiene la Fase 2, y no una mejora de estilo. `JTree` con
 * `rowHeight = 0` guarda la altura de cada fila en un `VariableHeightLayoutCache` que
 * la obtiene **invocando al renderer**, y eso cuesta ~0,064 ms por fila: el
 * presupuesto de 16 ms del hilo de interfaz se agota en ~250 filas (§0-bis.4 del plan
 * de escala). `removeAllChildren()` + `reload()` tira ese caché entero y vuelve a
 * medirlo todo; por eso repintar 100.000 tareas costaba 2,1 s de EDT.
 *
 * Aquí sólo se remide lo que de verdad cambió: los nodos que siguen en su sitio no
 * pasan por el renderer, y con ellos se conservan gratis el despliegue de los grupos y
 * la selección —que antes había que restaurar a mano después de cada recarga—.
 *
 * Es deliberadamente **tonto**: ni LCS ni ventanas deslizantes. Los hijos de un nodo
 * nunca pasan de una página más un puñado de centinelas, así que un par de bucles
 * cuadráticos sobre ~100 elementos es más barato —y mucho más fácil de creer— que un
 * diff fino.
 */
internal object TreeSync {

    /**
     * Sincroniza los hijos de [parent] con [desired] y devuelve **cuántas filas hubo
     * que crear, mover o remedir**: el número que dice lo que este repintado le costó
     * de verdad al EDT.
     */
    fun sync(model: DefaultTreeModel, parent: MutableTreeNode, desired: List<Row>): Int {
        var touched = 0

        // 1. Fuera lo que ya no está. De atrás hacia delante, que es lo único que
        //    permite quitar por índice sin recalcular nada por el camino.
        val wanted = desired.mapTo(HashSet(desired.size)) { it.key }
        for (index in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(index)
            if (keyOf(child) !in wanted) model.removeNodeFromParent(child as MutableTreeNode)
        }

        // 2. Cada fila en su sitio. Lo que sobrevivió se mueve o se pone al día; lo
        //    que no estaba, se crea.
        val surviving = HashMap<Any, DefaultMutableTreeNode>(parent.childCount)
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index) as DefaultMutableTreeNode
            surviving[keyOf(child)] = child
        }
        for ((index, row) in desired.withIndex()) {
            val node = surviving[row.key]
            if (node == null) {
                model.insertNodeInto(create(row), parent, index)
                touched++
                continue
            }
            // Mover es quitar y volver a poner, y eso ya invalida la medida de esa
            // fila: actualizar después no tiene a quién avisar.
            val moved = parent.getIndex(node) != index
            if (moved) {
                model.removeNodeFromParent(node)
                model.insertNodeInto(node, parent, index)
            }
            val changed = update(node, row)
            when {
                moved -> touched++
                changed -> {
                    model.nodeChanged(node)
                    touched++
                }
            }
        }
        return touched
    }

    /**
     * Vacía un grupo plegado. Un grupo cerrado no tiene por qué seguir cargando sus
     * filas: es justo lo que le permite anunciar 800.000 tareas sin tenerlas.
     */
    fun clear(model: DefaultTreeModel, parent: DefaultMutableTreeNode) {
        if (parent.childCount == 0) return
        parent.removeAllChildren()
        model.nodeStructureChanged(parent)
    }

    private fun keyOf(node: TreeNode): Any = when (node) {
        is GroupNode -> node.key
        is TaskNode -> node.task.id
        is MoreNode -> node.direction
        is EmptyGroupNode -> EMPTY_ROW
        else -> node
    }

    private fun create(row: Row): MutableTreeNode = when (row) {
        is Row.OfGroup -> GroupNode(row.group, row.size)
        is Row.OfTask -> TaskNode(row.task, row.done)
        is Row.OfEmpty -> EmptyGroupNode(row.text)
        is Row.OfMore -> MoreNode(row.direction, row.remaining, row.cursor)
    }

    private fun update(node: DefaultMutableTreeNode, row: Row): Boolean = when {
        node is GroupNode && row is Row.OfGroup -> node.update(row.size)
        node is TaskNode && row is Row.OfTask -> node.update(row.task, row.done)
        node is MoreNode && row is Row.OfMore -> node.update(row.remaining, row.cursor)
        else -> false
    }
}

/**
 * La identidad de una fila de «grupo vacío»: sólo puede haber una por grupo, así que
 * basta con que sea siempre la misma. Está fuera de las dos clases porque las dos
 * tienen que dar la **misma** clave: [Row.OfEmpty] mirando la fila que se pide y
 * [TreeSync] mirando el nodo que ya está puesto.
 */
private val EMPTY_ROW = Any()
