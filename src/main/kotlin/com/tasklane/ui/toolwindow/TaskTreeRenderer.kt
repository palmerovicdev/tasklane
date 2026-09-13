package com.tasklane.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.ui.CheckboxTree
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskLink
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.ui.common.GroupLabels
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.geom.RoundRectangle2D
import java.time.Instant
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.plaf.basic.BasicTreeUI

/**
 * Pinta una tarjeta de tarea y las cabeceras de los grupos.
 *
 * **El fondo.** Cada fila se pinta como una tarjeta redondeada con la franja de
 * prioridad dentro, recortada por la misma forma: así la franja no sobresale por las
 * esquinas. Va en [paintComponent] y no en un borde porque el fondo es de la fila
 * entera, y un borde sólo puede pintar en sus insets. Una fila **seleccionada** no
 * pinta tarjeta: ahí el fondo lo pone el árbol —selección ancha, con el color y el
 * redondeo del tema— y taparlo sería pelear con la plataforma.
 *
 * **La tarjeta.** Franja de prioridad y casilla a la izquierda, y a la derecha una
 * pila de líneas que crece con lo que la tarea tenga que decir: el título envuelto
 * en hasta [MAX_TITLE_LINES] líneas, una de descripción si hay cuerpo debajo, y la
 * de distintivos —vencimiento, lista de comprobación, etiquetas, enlaces—. Cada
 * línea que sobra se oculta, así que una tarea de una frase sigue midiendo una
 * línea. El árbol necesita para esto `rowHeight = 0`: la altura la fija cada fila.
 *
 * **Por qué el título se envuelve aquí y no en un `JTextArea`.** Envolviéndolo a
 * mano se conserva lo que hace falta para lo demás: los fragmentos etiquetados con
 * su [TaskLink] —que es como se resuelve el clic sin volver a medir texto—, el
 * resaltado de la búsqueda de la plataforma y el tachado de las tareas cerradas.
 * Un área de texto los perdería los tres. El troceado vive en [TitleWrap]; aquí
 * sólo se mide, porque la fuente de cada tramo la conoce el componente que pinta.
 *
 * Se construye reorganizando el panel del renderer de la plataforma en vez de
 * escribir uno desde cero: así el checkbox, los colores de selección y el foco los
 * sigue resolviendo `CheckboxTree`, y cada línea nueva se prepara con el mismo
 * `getTreeCellRendererComponent` que la primera para heredar ese tratamiento.
 *
 * El renderer no decide nada: recibe el [TasklaneConfig] vigente y traduce IDs a
 * color y nombre. Toda la lógica vive en el dominio.
 */
internal class TaskTreeRenderer : CheckboxTree.CheckboxTreeCellRenderer() {

    var config: TasklaneConfig = TasklaneConfig.DEFAULT

    /**
     * El nombre del estado sobra cuando la pestaña ya es ese estado. Se mantiene el
     * interruptor porque la vista de lista única —estados como nodos raíz— sí lo
     * necesita.
     */
    var showStateName: Boolean = false

    /** Matcher de la búsqueda en curso, o `null`. Lo construye `SearchService`. */
    var highlighter: MinusculeMatcher? = null

    /**
     * Nombres de repositorio, para etiquetar las filas que no son del activo. Sólo
     * aparecen buscando en todos los repositorios, que es cuando la pestaña puede
     * mezclar filas de varios y el título solo deja de identificar la tarea.
     */
    var repoNames: Map<RepoKey, String> = emptyMap()
    var activeRepo: RepoKey = RepoKey.ROOT

    /**
     * Cómo se escribe la fecha de la fila. Es un punto de inyección y no una llamada
     * directa porque `DateFormatUtil` necesita la `Application` del IDE: sin este
     * hueco, la única clase de la UI con aritmética de posiciones —el troceado del
     * título y el *hit testing*— no se podría medir en un test normal.
     */
    var formatDate: (Instant) -> String = { DateFormatUtil.formatPrettyDate(it.toEpochMilli()) }

    /** Reloj de «¿está vencida?». Inyectable por lo mismo que [formatDate]. */
    var now: () -> Instant = Instant::now

    /**
     * La fila bajo el ratón, o `-1`. La mantiene [TaskRowActions] y decide si se ven
     * el marcador y el menú: enseñarlos en todas las filas llenaría la lista de
     * controles que casi nunca se usan.
     */
    var hoveredRow: Int = -1

    // ------------------------------------------------------------- componentes

    /**
     * Lo que se pinta en las líneas que no son la primera, preparado antes de que la
     * plataforma pida cada una. No se puede pintar en el momento de calcularlo
     * porque cada línea se limpia al pedirle su componente.
     */
    private var pendingTitle: List<List<Run>> = emptyList()
    private var pendingDescription: String = ""
    private var pendingTask: Task? = null

    /** Lo que hace falta en el momento de pintar, cuando ya no hay nodo a mano. */
    private var pendingSelected = false
    private var cardColor: Color? = null
    private var stripeColor: Color? = null
    private var pendingIndent = 0
    private var currentTree: JTree? = null

    /** Líneas 2 y 3 del título. La primera es el `textRenderer` de la plataforma. */
    private val titleOverflow = List(MAX_TITLE_LINES - 1) { index ->
        line { tree, selected -> appendRuns(tree, this, pendingTitle.getOrNull(index).orEmpty(), selected) }
    }

    /** Resumen de lo que hay bajo el título. Oculta cuando la tarea es sólo el título. */
    private val description = line { _, _ ->
        append(pendingDescription, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    /** Enlaces e imágenes. Es el único distintivo con contador clicable. */
    private val detail = line { _, _ -> pendingTask?.let(::renderDetail) }

    private val chips = List(MAX_CHIPS) { SimpleColoredComponent().apply { isOpaque = false } }

    /**
     * Los dos controles de la derecha. El marcador se ve siempre que la tarea lo
     * esté —es información, no sólo un botón— y apagado sólo bajo el ratón; el menú,
     * únicamente bajo el ratón.
     */
    private val bookmark = icon()
    private val more = icon()

    /** Si cada control se puede pulsar ahora mismo. Ver [renderActions]. */
    private var bookmarkActive = false
    private var menuActive = false

    private val actions = JPanel(ChipRow()).apply { isOpaque = false }

    private val meta = JPanel(ChipRow()).apply { isOpaque = false }

    private val lines = JPanel(RowStack()).apply { isOpaque = false }

    init {
        // El panel base es un BorderLayout con el checkbox al oeste y el texto al
        // centro. Se sustituye el centro por la pila de líneas.
        remove(textRenderer)
        lines.add(textRenderer)
        titleOverflow.forEach(lines::add)
        lines.add(description)
        meta.add(detail)
        chips.forEach(meta::add)
        lines.add(meta)
        add(lines, BorderLayout.CENTER)
        actions.add(bookmark)
        actions.add(more)
        add(actions, BorderLayout.EAST)
    }

    override fun customizeRenderer(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        currentTree = tree
        pendingSelected = selected
        when (value) {
            is GroupNode -> {
                hideExtras()
                renderGroup(value)
            }

            is EmptyGroupNode -> {
                hideExtras()
                renderEmpty(value)
            }

            is TaskNode -> {
                renderTask(tree, value, selected, row)
                // Por el camino de la plataforma y no llamando a los ayudantes
                // directamente: es lo que le da a cada línea los colores de selección
                // y de foco correctos, en cualquier tema.
                //
                // Se le pide el componente a **todas**, visibles o no: es esa llamada
                // la que las limpia, y saltarse las ocultas dejaría dentro los
                // fragmentos de la fila anterior esperando a que a alguna le tocara
                // volver a verse.
                for (extra in titleOverflow + description + detail) {
                    extra.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                }
                renderChips(tree, value.task, selected)
            }
        }
    }

    private fun hideExtras() {
        pendingTask = null
        cardColor = null
        stripeColor = null
        bookmarkActive = false
        menuActive = false
        titleOverflow.forEach { it.isVisible = false }
        description.isVisible = false
        detail.isVisible = false
        chips.forEach { it.isVisible = false }
        meta.isVisible = false
        actions.isVisible = false
    }

    private fun renderGroup(node: GroupNode) {
        // Sin tarjeta ni franja: la prioridad es una propiedad de la tarea, no del
        // grupo. El borde sólo alinea la cabecera con el texto de las tarjetas.
        border = groupBorder
        pendingIndent = 0
        textRenderer.append(GroupLabels.of(node.key, config), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        textRenderer.append("  ${node.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    /** El «no hay nada aquí» de un grupo que existe pero está vacío. */
    private fun renderEmpty(node: EmptyGroupNode) {
        border = emptyGroupBorder
        pendingIndent = 0
        textRenderer.icon = AllIcons.General.InspectionsOK
        textRenderer.append(node.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun renderTask(tree: JTree, node: TaskNode, selected: Boolean, row: Int) {
        val task = node.task
        val priority = config.priorityOrDefault(task.priorityId)
        val state = config.stateOrDefault(task.stateId)

        border = cardBorder
        // JBColor resuelve claro/oscuro solo; por eso el modelo guarda ambos valores.
        stripeColor = JBColor(priority.colorLight, priority.colorDark)
        cardColor = cardColorFor(tree)
        renderActions(task, hovered = row >= 0 && row == hoveredRow)

        val titleStyle = if (state.terminal) {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY)
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        pendingTask = task
        val available = availableWidth(tree, node)
        val wrapped = TitleWrap.wrap(titleRuns(task, titleStyle), available, MAX_TITLE_LINES, ::measure)

        appendRuns(tree, textRenderer, wrapped.firstOrNull().orEmpty(), selected)
        pendingTitle = wrapped.drop(1)
        titleOverflow.forEachIndexed { index, extra -> extra.isVisible = index < pendingTitle.size }

        pendingDescription = ellipsize(task.description, SimpleTextAttributes.GRAYED_ATTRIBUTES, available)
        description.isVisible = pendingDescription.isNotEmpty()
    }

    /**
     * El título, troceado en los enlaces que caigan dentro.
     *
     * Cada tramo de enlace sale como **un** [Run] con su [TaskLink]: es lo que
     * permite resolver el clic con `getFragmentTagAt` sin volver a medir texto ni
     * recalcular posiciones. Ese tramo no pasa por el resaltado de la búsqueda —la
     * API de la plataforma que resalta no admite etiqueta— y es un intercambio
     * consciente: subrayar una URL importa menos que poder abrirla.
     */
    private fun titleRuns(task: Task, style: SimpleTextAttributes): List<Run> {
        val title = task.title
        // Una tarea que es sólo una captura no tiene título que pintar: enseñar el
        // SHA sería peor que no enseñar nada. Ver `Task.titleRange`.
        if (title.isNotEmpty() && ImageRefParser.strip(title).isBlank()) {
            return listOf(
                Run(TasklaneBundle.message("editor.image.folded"), SimpleTextAttributes.GRAYED_ATTRIBUTES),
            )
        }
        val links = task.titleLinks
        if (links.isEmpty()) return listOf(Run(title, style, matched = true))

        val runs = mutableListOf<Run>()
        var cursor = 0
        for (link in links) {
            val from = link.range.first.coerceIn(cursor, title.length)
            val to = (link.range.last + 1).coerceIn(from, title.length)
            if (from > cursor) runs += Run(title.substring(cursor, from), style, matched = true)
            runs += Run(link.display, linkStyle(style), link)
            cursor = to
        }
        if (cursor < title.length) runs += Run(title.substring(cursor), style, matched = true)
        return runs
    }

    private fun appendRuns(tree: JTree, target: SimpleColoredComponent, runs: List<Run>, selected: Boolean) {
        for (run in runs) {
            when {
                run.link != null -> target.append(run.text, run.style, run.link)
                // El subrayado de coincidencias lo pone la plataforma, que es la única
                // forma de que salga correcto en ambos temas y sobre una fila
                // seleccionada.
                run.matched -> SpeedSearchUtil.appendColoredFragmentForMatcher(
                    run.text,
                    target,
                    run.style,
                    highlighter,
                    RenderingUtil.getSelectionBackground(tree),
                    selected,
                )

                else -> target.append(run.text, run.style)
            }
        }
    }

    /** Un enlace en una tarea cerrada sigue tachado: el estilo de enlace no lo reabre. */
    private fun linkStyle(base: SimpleTextAttributes): SimpleTextAttributes =
        if (base.style and SimpleTextAttributes.STYLE_STRIKEOUT == 0) {
            SimpleTextAttributes.LINK_ATTRIBUTES
        } else {
            SimpleTextAttributes(
                SimpleTextAttributes.STYLE_STRIKEOUT or SimpleTextAttributes.STYLE_UNDERLINE,
                SimpleTextAttributes.LINK_ATTRIBUTES.fgColor,
            )
        }

    // ------------------------------------------------------- línea de distintivos

    /**
     * Vencimiento, lista de comprobación, etiquetas, repositorio y estado.
     *
     * El indicador de enlaces e imágenes va aparte, en [detail], y es el único con
     * icono de la plataforma **y** contador clicable: el icono es uno solo porque un
     * [SimpleColoredComponent] sólo tiene uno, y lo gana el enlace por ser el único
     * de los dos que además se puede pulsar. El resto son distintivos de sólo
     * lectura, y cada uno es su propio componente justo para poder llevar su icono.
     */
    private fun renderChips(tree: JTree, task: Task, selected: Boolean) {
        val foreground = RenderingUtil.getForeground(tree, selected)
        var next = 0
        fun chip(text: String, icon: javax.swing.Icon?, attributes: SimpleTextAttributes) {
            val chip = chips.getOrNull(next++) ?: return
            chip.clear()
            chip.icon = icon
            chip.foreground = foreground
            chip.append(text, attributes)
            chip.isVisible = true
        }

        // La prioridad por defecto no se anuncia: sería el mismo distintivo en todas
        // las filas. La franja de la izquierda ya la lleva, y para las demás el color
        // suelto no dice cuál es, así que el nombre va al lado.
        config.priorityOrDefault(task.priorityId).takeIf { !it.isDefault }?.let { priority ->
            val color = JBColor(priority.colorLight, priority.colorDark)
            chip(priority.name, ColorIcon(JBUI.scale(DOT), color), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        // El vencimiento va con la palabra delante y no con un icono porque la
        // plataforma no trae ninguno de calendario ni de reloj, y una fecha suelta
        // junto a la de modificación no se distingue de ella.
        task.dueDate?.let { due ->
            val style = if (task.isOverdue(now())) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor.RED)
            } else {
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
            }
            chip(TasklaneBundle.message("toolwindow.row.due", formatDate(due)), null, style)
        }

        task.checklist.takeIf { it.isNotEmpty() }?.let { items ->
            val done = items.count { it.done }
            chip("$done/${items.size}", AllIcons.Actions.Checked, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        for (tag in task.tags) {
            chip("#$tag", null, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        chip(formatDate(task.completedAt ?: task.updatedAt), null, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

        if (task.repo != activeRepo) {
            repoNames[task.repo]?.let { chip(it, null, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        }
        if (showStateName) {
            chip(config.stateOrDefault(task.stateId).name, null, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        for (index in next until chips.size) chips[index].isVisible = false
        meta.isVisible = detail.isVisible || next > 0
    }

    /**
     * El marcador y el menú de la fila.
     *
     * El marcador encendido se ve siempre: es el estado de la tarea, no un botón que
     * aparezca al pasar por encima. Apagado y el menú sólo bajo el ratón, que es
     * cuando se pueden pulsar.
     *
     * Lo que **no** se hace es esconderlos: ocupan su sitio siempre, con un icono
     * vacío cuando no toca. Escondiéndolos, el título recuperaría esos píxeles y una
     * frase que cabe justa en una línea se partiría en dos al pasar el ratón por
     * encima — la fila entera dando un salto por acercarse a ella.
     */
    private fun renderActions(task: Task, hovered: Boolean) {
        bookmarkActive = task.bookmarked || hovered
        menuActive = hovered
        bookmark.icon = when {
            task.bookmarked -> BOOKMARK_ON
            hovered -> BOOKMARK_OFF
            else -> EmptyIcon.ICON_16
        }
        more.icon = if (hovered) AllIcons.Actions.More else EmptyIcon.ICON_16
        actions.isVisible = true
    }

    /**
     * Indicador de enlaces e imágenes. El icono de cadena con el número funciona esté
     * donde esté el enlace dentro del cuerpo, así que nunca hace falta entrar a
     * editar para abrir uno que viva en el detalle.
     */
    private fun renderDetail(task: Task) {
        val hasLinks = task.links.isNotEmpty()
        val images = task.attachments.size
        detail.isVisible = hasLinks || images > 0
        if (!detail.isVisible) return

        // `detail` ya pasó por `getTreeCellRendererComponent`, que lo dejó limpio.
        if (hasLinks) {
            detail.icon = AllIcons.Ide.Link
            detail.append(task.links.size.toString(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, LINKS)
        } else {
            detail.icon = AllIcons.FileTypes.Image
        }
        if (images > 0) {
            val text = if (hasLinks) {
                "  ${TasklaneBundle.message("toolwindow.row.images", images)}"
            } else {
                images.toString()
            }
            detail.append(text, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    // ------------------------------------------------------------------ pintado

    /**
     * La tarjeta: fondo redondeado y, recortada por él, la franja de prioridad.
     *
     * Seleccionada no se pinta fondo —lo pone el árbol— pero sí la franja: es lo que
     * identifica la prioridad y desaparecería justo en la fila que se está mirando.
     */
    override fun paintComponent(g: Graphics) {
        val card = cardColor
        if (card != null) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                val gap = JBUI.scale(CARD_GAP)
                val arc = JBUI.scale(CARD_ARC).toFloat()
                val shape = RoundRectangle2D.Float(
                    0f,
                    gap.toFloat(),
                    width.toFloat(),
                    (height - gap * 2).coerceAtLeast(1).toFloat(),
                    arc,
                    arc,
                )
                if (!pendingSelected) {
                    g2.color = card
                    g2.fill(shape)
                }
                stripeColor?.let { color ->
                    g2.clip(shape)
                    g2.color = color
                    g2.fillRect(0, gap, JBUI.scale(STRIPE), (height - gap * 2).coerceAtLeast(1))
                }
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }

    /**
     * Una fila ocupa **todo el ancho visible**, no lo que mida su texto.
     *
     * Sin esto cada tarjeta sería tan ancha como su contenido y la lista quedaría
     * dentada. El árbol da a cada fila el tamaño que pida el renderer, así que se
     * pide el del viewport menos la sangría del nivel. No provoca barra horizontal:
     * el título ya se envuelve contra ese mismo ancho.
     */
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val tree = currentTree ?: return size
        val visible = (tree.parent as? JViewport)?.width?.takeIf { it > 0 } ?: tree.width
        if (visible <= 0) return size
        size.width = maxOf(size.width, visible - pendingIndent)
        return size
    }

    /**
     * El fondo de la tarjeta, derivado del del propio árbol: un tono más claro en los
     * temas oscuros y uno más oscuro en los claros. Calcularlo así —y no fijar dos
     * colores— es lo que hace que se vea en cualquier tema, incluidos los de terceros.
     */
    private fun cardColorFor(tree: JTree): Color {
        val background = RenderingUtil.getBackground(tree)
        return if (ColorUtil.isDark(background)) ColorUtil.brighter(background, 1) else ColorUtil.darker(background, 1)
    }

    // ------------------------------------------------------------------- medida

    /**
     * Los píxeles que le quedan al texto de una fila: el ancho visible del árbol
     * menos la sangría de su nivel, la franja de prioridad y la casilla.
     *
     * Se mide contra el *viewport* y no contra el árbol porque el árbol crece hasta
     * la fila más ancha; usar su ancho haría que una fila larga ensanchara el árbol,
     * lo que a su vez le daría más sitio para no envolverse. La sangría se calcula
     * desde el nivel del nodo y no con `getRowBounds`, que durante la medida de las
     * filas devuelve el resultado anterior o ninguno.
     */
    private fun availableWidth(tree: JTree, node: TaskNode): Int {
        val viewport = (tree.parent as? javax.swing.JViewport)?.width?.takeIf { it > 0 }
        val total = viewport ?: tree.width
        if (total <= 0) return 0

        val ui = tree.ui as? BasicTreeUI
        val step = (ui?.leftChildIndent ?: 0) + (ui?.rightChildIndent ?: 0)
        // `depthOffset` de `BasicTreeUI` con raíz oculta y sin manecillas: la sangría
        // de un nodo de nivel 1 es cero.
        val indent = step * (node.level - 1).coerceAtLeast(0)
        val insets = border?.getBorderInsets(this)
        val stripe = insets?.left ?: 0
        val box = if (checkbox.isVisible) checkbox.preferredSize.width else 0
        val right = (insets?.right ?: 0) + if (actions.isVisible) actions.preferredSize.width else 0
        pendingIndent = indent
        return total - indent - stripe - box - right - JBUI.scale(MARGIN)
    }

    /**
     * El ancho de un tramo con la fuente que le tocará al pintarlo.
     *
     * Repite la derivación de fuente de `SimpleColoredComponent` —estilo del atributo
     * y tamaño reducido si es de los pequeños— porque es la única forma de que la
     * medida y el pintado coincidan; medirlo todo con la fuente base cortaría las
     * líneas donde no toca en cuanto la fila mezcla tamaños.
     */
    private fun measure(text: String, style: SimpleTextAttributes): Int {
        val base = textRenderer.font ?: return 0
        val size = if (style.isSmaller) UIUtil.getFontSize(UIUtil.FontSize.SMALL) else base.size.toFloat()
        val font = base.deriveFont(style.fontStyle and (Font.BOLD or Font.ITALIC), size)
        return textRenderer.getFontMetrics(font).stringWidth(text)
    }

    /** Recorta con puntos suspensivos lo que no quepa en una línea. */
    private fun ellipsize(text: String, style: SimpleTextAttributes, available: Int): String {
        if (text.isEmpty() || available <= 0 || measure(text, style) <= available) return text
        val dots = measure(TitleWrap.ELLIPSIS, style)
        var end = text.length
        while (end > 0 && measure(text.substring(0, end), style) + dots > available) end--
        return text.substring(0, end).trimEnd() + TitleWrap.ELLIPSIS
    }

    // ------------------------------------------------------------ hit testing

    /**
     * Qué enlaces hay bajo [point]: uno si el ratón está sobre un fragmento del
     * título, todos los de la tarea si está sobre el indicador, y ninguno en
     * cualquier otro sitio.
     *
     * Hay que preparar el renderer para esa fila antes de preguntarle: es **el
     * mismo objeto** el que pinta todas, así que sus fragmentos son los de la última
     * que se pintó. Primero se descarta por modelo —una tarea sin enlaces no llega a
     * medirse—, que es lo que permite llamar a esto en cada movimiento del ratón.
     */
    fun linksAt(tree: JTree, point: Point): List<TaskLink> {
        val row = tree.getRowForLocation(point.x, point.y)
        if (row < 0) return emptyList()
        val node = tree.getPathForRow(row)?.lastPathComponent as? TaskNode ?: return emptyList()
        if (node.task.links.isEmpty()) return emptyList()

        val bounds = tree.getRowBounds(row) ?: return emptyList()
        getTreeCellRendererComponent(tree, node, tree.isRowSelected(row), false, true, row, false)
        setBounds(0, 0, bounds.width, bounds.height)
        doLayout()
        lines.doLayout()

        var x = point.x - bounds.x - lines.x
        var y = point.y - bounds.y - lines.y
        var line = childAt(lines, x, y) ?: return emptyList()
        // La línea de distintivos es a su vez una fila de componentes, así que hay
        // que bajar un nivel más para dar con el que está bajo el ratón.
        if (line === meta) {
            meta.doLayout()
            x -= meta.x
            y -= meta.y
            line = childAt(meta, x, y) ?: return emptyList()
        }
        val target = line as? SimpleColoredComponent ?: return emptyList()

        val local = x - target.x
        return when {
            local < 0 -> emptyList()
            target.getFragmentTagAt(local) === LINKS -> node.task.links
            // El icono no es un fragmento con etiqueta, pero es la mitad visible del
            // indicador: dejarlo fuera haría que medio control no respondiera.
            target === detail && target.findFragmentAt(local) == SimpleColoredComponent.FRAGMENT_ICON ->
                node.task.links

            else -> listOfNotNull(target.getFragmentTagAt(local) as? TaskLink)
        }
    }

    /**
     * Qué control de la fila hay bajo [point], si hay alguno. Mismo truco que
     * [linksAt]: se prepara el renderer para esa fila y se pregunta al panel de la
     * derecha, que es donde viven los dos.
     *
     * Se pregunta por lo que está **pintado**, no por lo que podría estarlo: los dos
     * controles dependen de [hoveredRow] y preparar la fila los deja visibles o no
     * exactamente igual que al pintarla. Sin eso, un clic acertaría en un menú que
     * nadie está viendo.
     */
    fun targetAt(tree: JTree, point: Point): RowTarget? {
        val row = tree.getRowForLocation(point.x, point.y)
        if (row < 0) return null
        val node = tree.getPathForRow(row)?.lastPathComponent as? TaskNode ?: return null
        val bounds = tree.getRowBounds(row) ?: return null

        getTreeCellRendererComponent(tree, node, tree.isRowSelected(row), false, true, row, false)
        setBounds(0, 0, bounds.width, bounds.height)
        doLayout()
        if (!actions.isVisible) return null
        actions.doLayout()

        val child = childAt(actions, point.x - bounds.x - actions.x, point.y - bounds.y - actions.y)
        return when {
            child === bookmark && bookmarkActive -> RowTarget.BOOKMARK
            child === more && menuActive -> RowTarget.MENU
            else -> null
        }
    }

    private fun childAt(parent: Container, x: Int, y: Int): java.awt.Component? =
        parent.components.firstOrNull {
            it.isVisible && it.width > 0 &&
                y >= it.y && y < it.y + it.height && x >= it.x && x < it.x + it.width
        }

    /**
     * Una línea de la tarjeta: un renderer de la plataforma que se rellena con
     * [fill] cuando le piden su componente. Pasa por `ColoredTreeCellRenderer` y no
     * por un `SimpleColoredComponent` a secas porque es lo que le da los colores de
     * selección y de foco del tema.
     */
    private fun line(fill: SimpleColoredComponent.(JTree, Boolean) -> Unit) = object : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            fill(tree, selected)
        }
    }

    /** Un componente que sólo lleva icono: el marcador y el menú de la derecha. */
    private fun icon() = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.insets(0, ICON_PAD)
    }

    /** Los controles de una fila, para quien resuelve el clic. */
    enum class RowTarget { BOOKMARK, MENU }

    private companion object {
        /** Etiqueta del contador del indicador: representa *todos* los enlaces de la fila. */
        val LINKS = Any()

        /** Apagado es el mismo icono translúcido: la plataforma no trae variante hueca. */
        val BOOKMARK_ON = AllIcons.Nodes.Bookmark
        val BOOKMARK_OFF = IconLoader.getTransparentIcon(AllIcons.Nodes.Bookmark, 0.35f)

        /** Separación entre tarjetas, redondeo, franja y punto de color del distintivo. */
        const val CARD_GAP = 2
        const val CARD_ARC = 10
        const val STRIPE = 3
        const val STRIPE_GAP = 5
        const val PAD_V = 3
        const val PAD_H = 4
        const val DOT = 8
        const val ICON_PAD = 2

        /** Deja sitio a la franja y separa las tarjetas. El fondo lo pinta el renderer. */
        val cardBorder = JBUI.Borders.empty(CARD_GAP + PAD_V, STRIPE + STRIPE_GAP, CARD_GAP + PAD_V, PAD_H)

        /** Alinea la cabecera del grupo con el texto de las tarjetas de debajo. */
        val groupBorder = JBUI.Borders.empty(PAD_V, STRIPE + STRIPE_GAP, PAD_V, PAD_H)
        val emptyGroupBorder = JBUI.Borders.empty(PAD_V, STRIPE + STRIPE_GAP + INDENT, PAD_V, PAD_H)

        /** La fila de «no hay nada» va sangrada bajo su cabecera. */
        const val INDENT = 10

        /** Cuántas líneas puede ocupar el título antes de recortarse. */
        const val MAX_TITLE_LINES = 3

        /**
         * Distintivos reutilizables. Se crean una vez y se muestran u ocultan: el
         * renderer pinta todas las filas, así que crear componentes por fila sería
         * basura en cada repintado.
         */
        const val MAX_CHIPS = 8

        /** Holgura para la barra de desplazamiento y el borde derecho. */
        const val MARGIN = 12
    }
}
