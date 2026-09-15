package com.tasklane.ui.toolwindow

import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.paging.Cursor
import com.tasklane.paging.EmptyPager
import com.tasklane.paging.GroupOutline
import com.tasklane.paging.PageQuery
import com.tasklane.paging.TaskPager
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * El árbol de una pestaña: **acotado** y puesto al día **por diferencias**.
 *
 * Es el corazón de la Fase 2 (`docs/plan-escala.md`) y vive aparte de
 * [TasklanePanel] porque no necesita un IDE para nada: un `JTree`, un [TaskPager] y
 * las dos reglas que hacen que la ventana no se congele.
 *
 * **La primera regla: nunca está todo.** Las cabeceras salen de un agregado
 * —`(clave, cuenta)`— y nacen sin hijos; un grupo abierto carga una página y anuncia
 * el resto con un centinela; la raíz se pagina igual cuando la pestaña no agrupa, y
 * las propias cabeceras se paginan cuando son miles. Un grupo de 800.000 tareas es una
 * fila que dice 800.000.
 *
 * **La segunda: lo que sigue igual no se vuelve a medir.** Con `rowHeight = 0`, `JTree`
 * obtiene la altura de cada fila invocando al renderer, ~0,064 ms cada una: el
 * presupuesto de 16 ms del hilo de interfaz se agota en ~250 filas. `removeAllChildren()`
 * + `reload()` tiraba esa caché entera en cada repintado —o sea en cada tecla del
 * buscador—; [TreeSync] sólo toca lo que cambió. Medido: repintar sobre un millón de
 * tareas pasa de **10,9 s a 0,12 ms**.
 *
 * Quien lo usa pone [pager], [outline] y el resto del contexto, y llama a [sync]. Lo
 * que el usuario haga después —abrir un grupo, llegar al centinela, pedir que se le
 * enseñe una tarea— entra por [toggled], [loadMore] y [reveal], y todas acaban en el
 * mismo [sync].
 */
internal class ListSync(private val tree: JTree, private val stateId: StateId) {

    /** De dónde salen las filas. Lo renueva la pestaña en cada repintado. */
    var pager: TaskPager = EmptyPager

    /** Las cabeceras con su cuenta. Vacío == esta pestaña no agrupa. */
    var outline: List<GroupOutline> = emptyList()

    /** Si el estado es terminal, que es lo que cambia el texto de un grupo vacío. */
    var terminal: Boolean = false

    /** Qué dice un grupo que existe y está vacío —«hoy», sin nada—. */
    var emptyText: String = ""

    /**
     * Verdadero mientras esto mueve el árbol. Desplegar y plegar disparan eventos, y
     * sin esta marca quien los escucha los tomaría por gestos del usuario.
     */
    var syncing: Boolean = false
        private set

    private val model: DefaultTreeModel get() = tree.model as DefaultTreeModel

    private val root: DefaultMutableTreeNode get() = model.root as DefaultMutableTreeNode

    /**
     * Cuánto se ha cargado de cada lista: la de cada grupo, y la de la raíz —clave
     * `null`— cuando la pestaña no agrupa.
     *
     * Es (por dónde empieza, cuántas filas) y no un cursor a secas porque el árbol se
     * vuelve a sincronizar en **cada** repintado: hay que poder pedir otra vez la
     * misma ventana, no la siguiente. Y cuesta siempre O(tamaño de la ventana), nunca
     * un `OFFSET`.
     */
    private class Window(val from: Cursor? = null, val size: Int = TaskPager.PAGE)

    private val windows = HashMap<GroupKey?, Window>()

    /** Cuántas cabeceras hay puestas. Agrupando por etiqueta también hay que paginarlas. */
    private var groupsShown = GROUP_PAGE

    /** Lo que el usuario decidió, que manda sobre lo que decide [GroupBudget]. */
    private val open = mutableSetOf<GroupKey>()
    private val closed = mutableSetOf<GroupKey>()

    /**
     * Otra lista empieza por el principio. Lo pide la pestaña cuando cambia la
     * consulta, el filtro, la agrupación o el repositorio: sin esto, teclear en el
     * buscador obligaría a rehacer las dos mil filas que el usuario hubiera ido
     * cargando al desplazarse.
     */
    fun rewind() {
        windows.clear()
        groupsShown = GROUP_PAGE
    }

    /** Y si además cambió la agrupación, lo que abrió o cerró ya no se refiere a nada. */
    fun forgetGroups() {
        open.clear()
        closed.clear()
    }

    /** El usuario abrió o cerró una cabecera. */
    fun toggled(key: GroupKey, expanded: Boolean) {
        if (expanded) {
            open += key
            closed -= key
        } else {
            closed += key
            open -= key
        }
    }

    /**
     * Cuántas filas tiene un trozo de la lista, **sin leerlo**: la cabecera de un grupo
     * ya lo dice, y la raíz lo cuenta la pestaña. Lo pide la exportación para decidir el
     * destino antes de leer nada. Ver `ExportService.copy`.
     */
    fun sizeOf(group: GroupKey?): Int =
        if (group == null) pager.counts()[stateId] ?: 0
        else outline.firstOrNull { it.key == group }?.size ?: 0

    // ------------------------------------------------------------------ sincronizar

    /** Devuelve cuántas filas hubo que crear, mover o remedir. */
    fun sync(): Int {
        syncing = true
        try {
            if (outline.isEmpty()) {
                val touched = TreeSync.sync(model, root, rowsOf(null))
                expandRoot()
                return touched
            }
            return syncGroups()
        } finally {
            syncing = false
        }
    }

    /**
     * Las cabeceras, y dentro sólo las de los grupos abiertos. Un grupo cerrado se
     * queda **sin hijos**: su cabecera ya dice cuántas tareas tiene, y tenerlas
     * puestas sólo serviría para que el árbol las midiera.
     */
    private fun syncGroups(): Int {
        val shown = outline.take(groupsShown)
        val rows = ArrayList<Row>(shown.size + 1)
        shown.mapTo(rows) { Row.OfGroup(it.key, it.size) }
        if (outline.size > shown.size) {
            rows += Row.OfMore(MoreNode.Direction.AFTER, outline.size - shown.size)
        }
        var touched = TreeSync.sync(model, root, rows)
        expandRoot()

        val opened = GroupBudget.open(shown, open, closed, rows.size) { windows[it]?.size ?: TaskPager.PAGE }
        for (node in groupNodes()) {
            val path = TreePath(node.path)
            if (node.key in opened) {
                // Primero el contenido y después el despliegue: una cabecera sin hijos
                // se despliega igual —para eso está [TaskTreeModel]— pero el árbol no
                // tendría nada que enseñar.
                touched += TreeSync.sync(model, node, rowsOf(node.key))
                if (!tree.isExpanded(path)) tree.expandPath(path)
            } else {
                if (tree.isExpanded(path)) tree.collapsePath(path)
                TreeSync.clear(model, node)
            }
        }
        return touched
    }

    /** Las filas de una lista: su página, y los centinelas de lo que queda fuera. */
    private fun rowsOf(group: GroupKey?): List<Row> {
        val window = windows[group] ?: Window()
        val page = pager.page(PageQuery(stateId, group, window.size), window.from)
        val rows = ArrayList<Row>(page.items.size + 2)
        page.before?.let { rows += Row.OfMore(MoreNode.Direction.BEFORE, it.remaining, it.cursor) }
        // El «no queda nada» es de un grupo que existe y está vacío —«hoy»—, no de una
        // ventana que empieza más abajo de donde hay filas.
        if (group != null && page.items.isEmpty() && page.before == null) rows += Row.OfEmpty(emptyText)
        page.items.mapTo(rows) { Row.OfTask(it, terminal) }
        if (page.after > 0) rows += Row.OfMore(MoreNode.Direction.AFTER, page.after)
        return rows
    }

    /**
     * La raíz va oculta, y `JTree` sólo la da por desplegada cuando el modelo se
     * instala con algo dentro o cuando llega un `reload()`. Desde la Fase 2 no pasa
     * ninguna de las dos cosas: la lista nace vacía y se llena por diferencias, así
     * que hay que decirlo la primera vez que tiene hijos.
     *
     * Sin esto el árbol se queda **en blanco con el modelo lleno**, que es el modo de
     * fallo más difícil de leer de los que esta fase podía introducir —y ya se vivió
     * una vez, cuando el trabajo de accesibilidad de la 1.0.0 dejó la ventana vacía con
     * las cuentas pintadas—.
     */
    private fun expandRoot() {
        if (root.childCount == 0) return
        val path = TreePath(root)
        if (!tree.isExpanded(path)) tree.expandPath(path)
    }

    private fun groupNodes(): List<GroupNode> =
        root.children().asSequence().filterIsInstance<GroupNode>().toList()

    // ------------------------------------------------------------------ más filas

    /**
     * Trae la siguiente página del centinela [node].
     *
     * Hacia abajo es ampliar la ventana; hacia arriba es moverle el principio, que es
     * lo que dice el cursor del centinela. Y pedir más dentro de un grupo cuenta como
     * abrirlo a mano: el presupuesto del primer pintado no puede volver a cerrarlo en
     * el siguiente repintado y tirar lo que se acaba de traer.
     */
    fun loadMore(node: MoreNode): Int {
        // Un centinela suelto ya no dice de qué lista es, y sin padre la clave `null`
        // apuntaría a la raíz — que en una pestaña que agrupa es otra lista.
        val parent = node.parent ?: return 0
        val group = (parent as? GroupNode)?.key
        when {
            // Más cabeceras. Pasa agrupando por etiqueta, que es la única agrupación
            // cuyo número de grupos no lo acota la configuración.
            parent === root && outline.isNotEmpty() -> groupsShown += GROUP_PAGE

            node.direction == MoreNode.Direction.AFTER ->
                windows[group] = Window(windows[group]?.from, (windows[group]?.size ?: TaskPager.PAGE) + TaskPager.PAGE)

            else ->
                windows[group] = Window(node.cursor, (windows[group]?.size ?: TaskPager.PAGE) + TaskPager.PAGE)
        }
        group?.let { toggled(it, expanded = true) }
        return sync()
    }

    /**
     * El centinela **del final** que se esté viendo, o `null`.
     *
     * Sólo el del final: el de arriba aparece después de un salto —enseñar una tarea
     * que cae en la fila 900.000— y cargarlo solo movería la lista justo debajo de lo
     * que se acaba de enseñar. Ése se pulsa.
     */
    fun visibleSentinel(): MoreNode? {
        val rect = tree.visibleRect
        if (rect.height <= 0) return null
        val first = tree.getClosestRowForLocation(rect.x, rect.y)
        val last = tree.getClosestRowForLocation(rect.x, rect.y + rect.height - 1)
        if (first < 0 || last < 0) return null
        for (row in first..last) {
            val node = tree.getPathForRow(row)?.lastPathComponent
            if (node is MoreNode && node.direction == MoreNode.Direction.AFTER) return node
        }
        return null
    }

    // ------------------------------------------------------------------ enseñar

    /**
     * Abre lo que haga falta para que [ids] se vean, y dice si alguna era de esta
     * pestaña.
     *
     * **No se busca el nodo en el árbol**, porque el árbol ya no las contiene todas: se
     * le pregunta al pager en qué grupo cae la tarea y con qué ventana se la puede
     * enseñar sin cargar lo que tiene delante. Después de esto hace falta un [sync]
     * para que el nodo exista.
     */
    fun reveal(ids: Set<TaskId>): Boolean {
        val found = ids.mapNotNull { pager.reveal(stateId, it) }
        if (found.isEmpty()) return false

        // Varias tareas del mismo grupo comparten ventana, y sólo cabe una: gana la que
        // empieza por el principio, que es la que más contexto enseña.
        for ((group, reveals) in found.groupBy { it.group }) {
            val chosen = reveals.firstOrNull { it.from == null } ?: reveals.first()
            windows[group] = Window(chosen.from, chosen.size)
            if (group == null) continue
            // Una tarea dentro de un grupo cerrado obliga a abrirlo, y entonces el
            // pliegue deja de describir lo que se ve: se olvida a propósito, o el
            // siguiente repintado volvería a cerrarlo encima de lo que se acaba de
            // enseñar.
            toggled(group, expanded = true)
            // Y su cabecera puede no estar ni puesta: agrupando por etiqueta también
            // se paginan.
            val at = outline.indexOfFirst { it.key == group }
            if (at >= groupsShown) groupsShown = (at / GROUP_PAGE + 1) * GROUP_PAGE
        }
        return true
    }

    private companion object {
        /**
         * Cabeceras por página. Sólo agrupando por etiqueta llega a hacer falta: las
         * demás agrupaciones tienen tantos grupos como diga la configuración o el
         * calendario, pero un proyecto puede tener miles de etiquetas y cada cabecera
         * es una fila que hay que medir.
         */
        const val GROUP_PAGE = 50
    }
}
