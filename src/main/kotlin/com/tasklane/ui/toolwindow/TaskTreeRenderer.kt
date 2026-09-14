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
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.DetailBlock
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskLink
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.InlineMarkdown
import com.tasklane.ui.common.GroupLabels
import com.tasklane.ui.common.PriorityDot
import com.tasklane.ui.common.TasklaneIcons
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.RoundRectangle2D
import java.time.Instant
import java.util.IdentityHashMap
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.plaf.basic.BasicTreeUI

/**
 * Pinta una tarjeta de tarea y las cabeceras de los grupos.
 *
 * **El fondo.** Cada fila se pinta como una tarjeta redondeada con la franja de
 * prioridad dentro, recortada por la misma forma: así la franja no sobresale por las
 * esquinas. Va en [paintComponent] y no en un borde porque el fondo es de la fila
 * entera, y un borde sólo puede pintar en sus insets. Una fila **seleccionada** no
 * pinta tarjeta: ahí el fondo lo pone el árbol —selección ancha, con el color y el
 * redondeo del tema— y taparlo sería pelear con la plataforma. El **resalte del
 * ratón**, en cambio, sí es nuestro: el del árbol se pinta detrás del renderer y más
 * ancho que él, así que asomaba por fuera de la tarjeta. Se apaga en [TasklanePanel]
 * con `RenderingUtil.setHoverPaintingDisabled`.
 *
 * El ancho de la fila **no se fuerza**: el árbol ya estira el renderer hasta el borde
 * visible, descontando el margen que él mismo reserva para pintar la selección.
 * Pedirle más —que es lo que hacía este renderer al principio— hacía que el rectángulo
 * de la selección y el del ratón se calcularan sobre un ancho mayor que el hueco y
 * sobresalieran por la derecha.
 *
 * **La tarjeta.** Franja de prioridad y casilla a la izquierda, y a la derecha una
 * pila de líneas que crece con lo que la tarea tenga que decir: el título envuelto
 * en hasta [MAX_TITLE_LINES] líneas, una de descripción si hay cuerpo debajo, y la
 * de distintivos —vencimiento, etiquetas, enlaces—. Cada
 * línea que sobra se oculta, así que una tarea de una frase sigue midiendo una
 * línea. El árbol necesita para esto `rowHeight = 0`: la altura la fija cada fila.
 *
 * **Desplegada** la tarjeta deja de resumir: el título se envuelve sin tope, debajo
 * van *todas* las líneas del cuerpo —no sólo la primera recortada— y **las imágenes**,
 * escaladas y cada una detrás de la línea que la nombra, igual que el diálogo. Es un interruptor
 * por tarea —[toggleExpanded]— y el botón que lo acciona sólo se enciende cuando hay
 * algo escondido, que es lo que sabe decir [TitleWrap.Fit]. Que el número de líneas
 * deje de tener tope es lo que obliga a que [extraLines] sea un pozo que crece y no
 * una lista de dos.
 *
 * **El texto se puede seleccionar y copiar.** Lo lleva [CardTextSelection], que
 * traduce el ratón a [TextPos] con la misma aritmética del *hit testing* de los
 * enlaces; aquí sólo se pinta, y se pinta como fondo de los caracteres elegidos
 * —igual que el de los tramos de código— para que no haya geometría que se pueda
 * desalinear del texto.
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
     * De dónde salen las vistas previas de las tarjetas desplegadas. Lo pone
     * [TasklanePanel] con un [CardImages]; sin él —en un test— la tarjeta se pinta sin
     * imágenes, que es exactamente lo que hacía antes.
     */
    var images: CardPreviews? = null

    /**
     * La fila bajo el ratón, o `-1`. La mantiene [TaskRowActions] y decide si se ven
     * el marcador y el menú: enseñarlos en todas las filas llenaría la lista de
     * controles que casi nunca se usan.
     */
    var hoveredRow: Int = -1

    /**
     * El texto seleccionado a mano, o `null`. Lo mantiene [CardTextSelection]; aquí
     * sólo se pinta —ver [highlighted]— y se mide —ver [caretAt]—.
     */
    var selection: CardSelection? = null

    /**
     * Las tarjetas desplegadas. Por [TaskId] y no por fila porque cualquier cambio en
     * cualquier tarea reconstruye el árbol entero, y por índice el despliegue se
     * habría mudado a la tarjeta de al lado en el primer repintado.
     *
     * No se persiste: desplegar es mirar algo un momento, no configurar la lista.
     */
    private val expanded = mutableSetOf<TaskId>()

    /** Si la tarjeta de [task] está desplegada ahora mismo. */
    fun isExpanded(task: Task): Boolean = task.id in expanded

    fun toggleExpanded(task: Task) {
        if (!expanded.remove(task.id)) expanded += task.id
    }

    /**
     * Olvida las tarjetas que ya no están en la lista. Lo llama [TasklanePanel] en
     * cada repintado: sin esto, borrar una tarea desplegada dejaría su ID dentro para
     * siempre, y con él el despliegue esperando a una tarea que no va a volver.
     */
    fun retainExpanded(ids: Set<TaskId>) {
        expanded.retainAll(ids)
    }

    // ------------------------------------------------------------- componentes

    /**
     * El texto de la tarjeta que se está pintando, línea a línea: la primera va al
     * `textRenderer` de la plataforma y las demás al pozo de [extraLines]. Se calcula
     * entero de una vez porque cada línea se limpia al pedirle su componente, así que
     * no hay dónde ir apuntándolo por el camino.
     *
     * Es además sobre lo que se mide la selección de texto y lo que se copia:
     * [caretAt] y [cardText] preparan la fila y leen esto.
     */
    private var pendingCard: List<List<Run>> = emptyList()
    private var pendingTask: Task? = null

    /** Lo que hace falta en el momento de pintar, cuando ya no hay nodo a mano. */
    private var pendingSelected = false
    private var pendingHovered = false
    private var cardColor: Color? = null
    private var stripeColor: Color? = null

    /** Fondo de los tramos de código del cuerpo. Nulo = sin recuadro. Ver [emphasize]. */
    private var codeColor: Color? = null

    /**
     * Las líneas de texto que no son la primera, creadas según hacen falta.
     *
     * Es un pozo que crece y no una lista fija porque desde que la tarjeta se
     * despliega no hay número que valga: plegada son hasta tres de título y una de
     * resumen, desplegada son las que pida el cuerpo. Crece y no encoge —el tope es
     * [MAX_CARD_LINES]—: el renderer pinta todas las filas, y tirar los componentes
     * para volver a crearlos en la siguiente sería basura en cada repintado.
     */
    private val extraLines = mutableListOf<ColoredTreeCellRenderer>()

    /**
     * Las vistas previas, con el mismo criterio de pozo que [extraLines]: crecen según
     * hacen falta y no se devuelven. Van **después** de las líneas de texto y antes de
     * la de distintivos, que es el orden en que [RowStack] las apila.
     */
    private val imageViews = mutableListOf<CardImageView>()

    /** Si la fila que se está montando enseña sus imágenes. Ver [renderDetail]. */
    private var pendingImagesShown = false

    /** Lo que se ve de ancho la última vez que se montó una fila. Ver [getPreferredSize]. */
    private var widthLimit = 0

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

    /** Desplegar y volver a plegar la tarjeta. Ver [showExpandIcon]. */
    private val expand = icon()

    /** Si cada control se puede pulsar ahora mismo. Ver [renderActions]. */
    private var bookmarkActive = false
    private var menuActive = false
    private var expandActive = false

    private val actions = JPanel(ChipRow()).apply { isOpaque = false }

    private val meta = JPanel(ChipRow()).apply { isOpaque = false }

    private val lines = JPanel(RowStack()).apply { isOpaque = false }

    init {
        // El panel base es un BorderLayout con el checkbox al oeste y el texto al
        // centro. Se sustituye el centro por la pila de líneas.
        remove(textRenderer)
        lines.add(textRenderer)
        meta.add(detail)
        chips.forEach(meta::add)
        lines.add(meta)
        add(lines, BorderLayout.CENTER)
        actions.add(expand)
        actions.add(bookmark)
        actions.add(more)
        add(actions, BorderLayout.EAST)
    }

    /**
     * Deja el pozo con al menos [count] líneas.
     *
     * Las nuevas se insertan **antes** de la de distintivos, que es la última de la
     * pila: el orden de los componentes es el orden en que [RowStack] los apila, así
     * que añadirlas al final metería el cuerpo de la tarea por debajo de las fechas.
     */
    private fun ensureLines(count: Int) {
        while (extraLines.size < count) {
            val index = extraLines.size
            val extra = line { tree, selected ->
                appendRuns(tree, this, pendingCard.getOrNull(index + 1).orEmpty(), selected)
            }
            // Detrás de la primera línea y de las que ya hay, delante de las
            // imágenes y de los distintivos.
            lines.add(extra, 1 + extraLines.size)
            extraLines += extra
        }
    }

    /** Lo mismo para las vistas previas, que van justo antes de la de distintivos. */
    private fun ensureImages(count: Int) {
        while (imageViews.size < count) {
            val view = CardImageView()
            lines.add(view, lines.componentCount - 1)
            imageViews += view
        }
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
        pendingSelected = selected
        // La sangría cuenta: una fila de un grupo empieza a la derecha del borde, y lo
        // que le queda de sitio es lo que se ve **menos** esa sangría. Sin descontarla,
        // el tope dejaba la fila asomando por la derecha justo esos píxeles, y con ella
        // los botones — que se pintan donde acaba la fila, pero se buscaban donde el
        // *hit testing* rehace la cuenta. Pulsar el marcador plegaba la tarjeta.
        widthLimit = visibleWidth(tree) - indentOf(tree, value as? DefaultMutableTreeNode)
        when (value) {
            is GroupNode -> {
                hideExtras()
                renderGroup(value, expanded)
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
                for (extra in extraLines + detail) {
                    extra.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                }
                renderChips(tree, value.task, selected)
            }
        }
    }

    private fun hideExtras() {
        pendingTask = null
        pendingCard = emptyList()
        cardColor = null
        stripeColor = null
        codeColor = null
        pendingHovered = false
        bookmarkActive = false
        menuActive = false
        expandActive = false
        extraLines.forEach { it.isVisible = false }
        imageViews.forEach { it.isVisible = false }
        pendingImagesShown = false
        detail.isVisible = false
        chips.forEach { it.isVisible = false }
        meta.isVisible = false
        actions.isVisible = false
    }

    /**
     * La cabecera lleva **chevrón**, y no es decoración: es la única señal de que el
     * grupo se pliega. El árbol va con `showsRootHandles = false` —las manecillas de
     * la plataforma se pintan fuera de la tarjeta y desalinearían todas las filas—,
     * así que sin esto el grupo se podía plegar y nada lo decía. El clic lo atiende
     * [TasklanePanel.installGroupToggle].
     */
    private fun renderGroup(node: GroupNode, expanded: Boolean) {
        // Sin tarjeta ni franja: la prioridad es una propiedad de la tarea, no del
        // grupo. El borde sólo alinea la cabecera con el texto de las tarjetas.
        border = groupBorder
        textRenderer.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        textRenderer.append(GroupLabels.of(node.key, config), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        textRenderer.append("  ${node.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    /** El «no hay nada aquí» de un grupo que existe pero está vacío. */
    private fun renderEmpty(node: EmptyGroupNode) {
        border = emptyGroupBorder
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
        // Sobre una fila seleccionada el fondo lo pone el árbol: un recuadro propio
        // encima sería una mancha con otro color dentro de la selección.
        codeColor = if (selected) null else codeColorFor(tree)
        pendingHovered = row >= 0 && row == hoveredRow
        renderActions(task, hovered = pendingHovered)

        val titleStyle = if (state.terminal) {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY)
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        pendingTask = task
        val available = availableWidth(tree, node)
        val open = task.id in expanded
        // Desplegada el tope existe igual, sólo que muy lejos: es un seguro contra una
        // tarea pegada de un fichero entero, que crearía un componente por línea.
        val room = if (open) MAX_CARD_LINES else MAX_TITLE_LINES
        val title = TitleWrap.fit(titleRuns(task, titleStyle), available, room, ::measure)
        val body = bodyLines(task, available, room - title.lines.size, open)

        pendingCard = highlighted(tree, task, title.lines + body.lines, selected)
        ensureLines(pendingCard.size - 1)
        extraLines.forEachIndexed { index, extra -> extra.isVisible = index < pendingCard.size - 1 }
        appendRuns(tree, textRenderer, pendingCard.firstOrNull().orEmpty(), selected)

        pendingImagesShown = renderImages(task, available, open)

        // Después de envolver y no antes: hasta aquí no se sabe si sobró algo, que es
        // lo único que justifica enseñar el botón.
        showExpandIcon(
            open,
            // Una captura que sólo se ve desplegando la tarjeta es tanto «hay algo
            // escondido» como una frase que no cupo.
            hidden = title.clipped || body.clipped || task.attachments.isNotEmpty(),
            hovered = pendingHovered,
        )
    }

    /**
     * Las vistas previas de la tarjeta, y si se está enseñando alguna.
     *
     * **Sólo desplegada.** Plegada, la tarjeta mide tres o cuatro líneas y su trabajo
     * es que quepan muchas a la vista; una captura de 280 px dentro convertiría la
     * lista en una galería y dejaría dos tareas por pantalla. Plegada lo que hay es el
     * contador de la línea de distintivos, que para eso está.
     *
     * Ni lee disco ni escala: eso es cosa de [CardImages]. Aquí sólo se pregunta en qué
     * estado está cada imagen y se reparte lo que haya por el pozo de vistas.
     */
    private fun renderImages(task: Task, available: Int, open: Boolean): Boolean {
        val previews = images
        val refs = if (open && previews != null && available > 0) {
            task.detailBlocks.filterIsInstance<DetailBlock.Image>().take(MAX_CARD_IMAGES)
        } else {
            emptyList()
        }
        ensureImages(refs.size)

        imageViews.forEachIndexed { index, view ->
            val ref = refs.getOrNull(index)
            view.isVisible = ref != null
            view.id = ref?.id
            if (ref == null || previews == null) {
                view.image = null
                return@forEachIndexed
            }
            view.state = previews.stateOf(task.repo, ref.id)
            view.image = if (view.state == CardImageView.State.READY) {
                previews.preview(
                    task.repo,
                    ref.id,
                    CardImageView.contentWidth(available),
                    CardImageView.contentHeight(),
                )
            } else {
                null
            }
            // Cargó, pero el blob se fue entre medias: sin imagen no hay nada que
            // pintar, y el marcador de ausente dice lo que pasa.
            if (view.image == null) view.state = CardImageView.State.MISSING
        }
        return refs.isNotEmpty()
    }

    /**
     * Lo que va bajo el título.
     *
     * Plegada, la tarjeta **resume**: una sola línea, recortada si no cabe, y del
     * resto del cuerpo no se pinta nada. Desplegada se pintan todas las líneas del
     * cuerpo, cada una envuelta con el mismo troceado que el título.
     *
     * Devuelve también si se quedó algo fuera, y ahí entra lo que no se ve por ancho
     * **y** lo que no se ve por número de líneas: una tarea con tres párrafos cabe
     * perfectamente en una línea de resumen y aun así esconde dos.
     */
    private fun bodyLines(task: Task, available: Int, room: Int, open: Boolean): TitleWrap.Fit {
        val gray = SimpleTextAttributes.GRAYED_ATTRIBUTES
        if (!open) {
            val one = ellipsize(markdownRuns(task.description, gray), available)
            return TitleWrap.Fit(one.lines, one.clipped || task.detailLines.size > 1)
        }

        val out = mutableListOf<List<Run>>()
        var clipped = false
        for (paragraph in task.detailLines) {
            val budget = room - out.size
            if (budget <= 0) {
                clipped = true
                break
            }
            val runs = markdownRuns(paragraph, gray)
            // Con una línea de margen no hay nada que envolver: lo que no quepa se
            // recorta, que es lo que hace `wrap` cuando se queda sin líneas.
            val fit = if (budget == 1) ellipsize(runs, available) else TitleWrap.fit(runs, available, budget, ::measure)
            out += fit.lines
            clipped = clipped || fit.clipped
        }
        return TitleWrap.Fit(out, clipped)
    }

    /**
     * El título, troceado por el énfasis Markdown que lleve y por los enlaces que
     * caigan dentro.
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
        return markdownRuns(title, style, matched = true) { index ->
            links.firstOrNull { it.range.first == index }
        }
    }

    /**
     * [text] repartido en tramos con el estilo con el que se pinta cada uno.
     *
     * **Las marcas de Markdown no llegan a la fila.** Hasta la `0.7.0` sí: quien
     * escribía una tarea con el botón de negrita del diálogo la veía luego en la
     * lista como `**algo**`, con los asteriscos puestos. El troceado lo hace
     * [InlineMarkdown], que devuelve rangos del original y no texto ya cortado, y
     * eso es justo lo que permite cruzarlo aquí con los enlaces —que también vienen
     * en coordenadas del original— sin recalcular ni un desplazamiento.
     *
     * Los índices que ninguna [InlineMarkdown.Span] cubre son las marcas: se saltan,
     * y por eso desaparecen. Los tramos seguidos con el mismo énfasis se juntan en un
     * [Run], que es lo que le devuelve al resaltado de la búsqueda frases enteras
     * que casar en vez de letras sueltas.
     */
    private fun markdownRuns(
        text: String,
        base: SimpleTextAttributes,
        matched: Boolean = false,
        linkAt: (Int) -> TaskLink? = { null },
    ): List<Run> {
        if (text.isEmpty()) return emptyList()

        val emphasis = arrayOfNulls<InlineMarkdown.Emphasis>(text.length)
        for (span in InlineMarkdown.parse(text)) {
            for (index in span.range) emphasis[index] = span.emphasis
        }

        val runs = mutableListOf<Run>()
        val pending = StringBuilder()
        var current: InlineMarkdown.Emphasis? = null

        fun flush() {
            if (pending.isEmpty()) return
            runs += Run(pending.toString(), emphasize(base, current), matched = matched)
            pending.setLength(0)
        }

        var index = 0
        while (index < text.length) {
            val link = linkAt(index)
            if (link != null) {
                flush()
                current = null
                runs += Run(link.display, linkStyle(base), link)
                index = link.range.last + 1
                continue
            }
            val at = emphasis[index]
            if (at == null) {
                // Una marca: cuenta para el estilo de lo que viene, no para lo que se pinta.
                index++
                continue
            }
            if (at != current) {
                flush()
                current = at
            }
            pending.append(text[index])
            index++
        }
        flush()
        return runs
    }

    /**
     * El estilo de la fila con el énfasis encima. Se suma y no se sustituye: una
     * tarea cerrada con una palabra en negrita tiene que salir tachada **y** en
     * negrita, no una cosa o la otra.
     */
    private fun emphasize(base: SimpleTextAttributes, emphasis: InlineMarkdown.Emphasis?): SimpleTextAttributes {
        if (emphasis == null || emphasis == InlineMarkdown.Emphasis.NONE) return base
        var style = base.style
        if (emphasis.bold) style = style or SimpleTextAttributes.STYLE_BOLD
        if (emphasis.italic) style = style or SimpleTextAttributes.STYLE_ITALIC
        if (emphasis.strike) style = style or SimpleTextAttributes.STYLE_STRIKEOUT

        // El código no puede cambiar de familia: `SimpleColoredComponent` deriva la
        // fuente del estilo del atributo, y ahí no hay nombre que poner. Se distingue
        // por el fondo, que además es como se ve en cualquier visor de Markdown.
        val background = codeColor.takeIf { emphasis.code }
            ?: return if (style == base.style) base else SimpleTextAttributes(style, base.fgColor)
        return SimpleTextAttributes(background, base.fgColor, null, style or SimpleTextAttributes.STYLE_OPAQUE)
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

    // --------------------------------------------------- selección de texto: pintado

    /**
     * La tarjeta con el tramo seleccionado marcado, si la selección es de esta tarea.
     *
     * El resalte se pinta como **fondo de los caracteres**, partiendo los tramos por
     * los bordes de la selección, y no como un rectángulo encima. Es el mismo truco
     * del fondo de los tramos de código, y tiene la misma ventaja: quien coloca los
     * píxeles es el componente que pinta las letras, así que el resalte no se puede
     * desalinear del texto por mucho que cambien la fuente o el tema.
     *
     * El color sale de la **letra** y no de una constante: así contrasta tanto sobre
     * la tarjeta como sobre una fila seleccionada, que ya trae su propio fondo. Es
     * translúcido, de modo que lo que se lee debajo sigue siendo el texto.
     *
     * Los límites se recortan a lo que hay: entre el arrastre y el repintado la tarea
     * puede haber cambiado de texto, y una posición vieja no debe tirar la fila.
     */
    private fun highlighted(
        tree: JTree,
        task: Task,
        card: List<List<Run>>,
        selected: Boolean,
    ): List<List<Run>> {
        val text = selection?.takeIf { it.id == task.id && !it.isEmpty } ?: return card
        val from = text.from
        val to = text.to
        val background = ColorUtil.withAlpha(RenderingUtil.getForeground(tree, selected), SELECTION_ALPHA)
        return card.mapIndexed { index, runs ->
            if (index < from.line || index > to.line) {
                runs
            } else {
                select(
                    runs,
                    if (index == from.line) from.offset else 0,
                    if (index == to.line) to.offset else Int.MAX_VALUE,
                    background,
                )
            }
        }
    }

    /**
     * Parte [runs] por los caracteres [start] y [end] y pinta el trozo de en medio
     * sobre [background].
     *
     * El trozo seleccionado sale del resaltado de la búsqueda —`matched = false`—: el
     * resaltado de la plataforma pone su propio fondo y se comería el de la selección,
     * y dentro de lo que el usuario acaba de marcar el subrayado ya no dice nada.
     */
    private fun select(runs: List<Run>, start: Int, end: Int, background: Color): List<Run> {
        val out = mutableListOf<Run>()
        var at = 0
        for (run in runs) {
            val text = run.text
            val from = (start - at).coerceIn(0, text.length)
            val to = (end - at).coerceIn(0, text.length)
            at += text.length
            if (from >= to) {
                out += run
                continue
            }
            if (from > 0) out += run.withText(text.substring(0, from))
            out += Run(text.substring(from, to), selectedStyle(run.style, background), run.link)
            if (to < text.length) out += run.withText(text.substring(to))
        }
        return out
    }

    /** El estilo del tramo con el fondo de la selección encima. Conserva el resto. */
    private fun selectedStyle(base: SimpleTextAttributes, background: Color): SimpleTextAttributes =
        SimpleTextAttributes(
            background,
            base.fgColor,
            base.waveColor,
            base.style or SimpleTextAttributes.STYLE_OPAQUE,
        )

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
     * Vencimiento, etiquetas, repositorio y estado.
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
        fun chip(
            text: String,
            icon: javax.swing.Icon?,
            attributes: SimpleTextAttributes,
            /** Sólo los distintivos que se pueden pulsar la llevan. Ver [hotspotAt]. */
            tag: Any? = null,
        ) {
            val chip = chips.getOrNull(next++) ?: return
            chip.clear()
            chip.icon = icon
            chip.foreground = foreground
            chip.append(text, attributes, tag)
            chip.isVisible = true
        }

        // La prioridad va en **todas** las tarjetas, también en las de la de fábrica.
        // Mientras fue un distintivo de sólo lectura, la de fábrica se callaba: sería
        // la misma palabra repetida en toda la lista y la franja de la izquierda ya
        // lleva el color. Desde que **se pulsa** —abre la lista de prioridades—
        // callarla es esconder el control, y justo en las tarjetas que nadie ha tocado
        // todavía, que son las que más se cambian de prioridad.
        //
        // La etiqueta es la prioridad misma, igual que la del ancla es su
        // [CodeAnchor]; quien atiende el clic la reconoce en [hotspotAt].
        config.priorityOrDefault(task.priorityId).let { priority ->
            chip(priority.name, PriorityDot.chip(priority), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, priority)
        }

        // El sitio del código va delante de todo lo demás: es lo que dice de qué habla
        // la tarea, y de los distintivos es el único que además lleva a algún sitio. Se
        // pinta con el color de enlace por eso mismo —lo que parece pulsable, lo es—.
        for (anchor in task.anchors) {
            chip(anchor.label, AllIcons.FileTypes.Any_type, ANCHOR_STYLE, anchor)
        }

        // El vencimiento lleva calendario propio: `AllIcons` no trae ninguno, y sin él
        // hacía falta escribir «Due» delante para que una fecha suelta no se
        // confundiera con la de modificación, que va justo al lado.
        task.dueDate?.let { due ->
            val style = if (task.isOverdue(now())) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor.RED)
            } else {
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
            }
            chip(formatDate(due), TasklaneIcons.Calendar, style)
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
        // El hueco del desplegable se reserva ya, aunque quién lo enciende sea
        // [showExpandIcon] más tarde: el ancho que le queda al texto se mide con esta
        // fila puesta, y decidirlo después de envolver cambiaría el resultado de la
        // medida que lo decidió.
        expandActive = false
        expand.icon = EmptyIcon.ICON_16
        actions.isVisible = true
    }

    /**
     * El botón de desplegar la tarjeta.
     *
     * Desplegada se ve **siempre**: es estado, y esconderlo dejaría una tarjeta larga
     * sin forma evidente de volver a cerrarla. Plegada aparece sólo bajo el ratón,
     * como el menú, y sólo si [hidden] —una tarjeta que ya se ve entera no tiene nada
     * que desplegar, y un botón que no hace nada es peor que ninguno—.
     */
    private fun showExpandIcon(open: Boolean, hidden: Boolean, hovered: Boolean) {
        expandActive = open || (hidden && hovered)
        expand.icon = when {
            !expandActive -> EmptyIcon.ICON_16
            open -> AllIcons.General.ArrowUp
            else -> AllIcons.General.ArrowDown
        }
    }

    /**
     * Indicador de enlaces e imágenes. El icono de cadena con el número funciona esté
     * donde esté el enlace dentro del cuerpo, así que nunca hace falta entrar a
     * editar para abrir uno que viva en el detalle.
     */
    private fun renderDetail(task: Task) {
        val hasLinks = task.links.isNotEmpty()
        // Desplegada, las capturas están ahí abajo a la vista: repetir «3 img» justo
        // encima de ellas no cuenta nada que no se esté viendo ya.
        val images = if (pendingImagesShown) 0 else task.attachments.size
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

    // ------------------------------------------------------------------ medida de la fila

    /**
     * Nunca más ancha que el hueco visible.
     *
     * La tarjeta se mide por trozos y uno de ellos no sabe encogerse: la línea de
     * distintivos pide el ancho de **todos** los suyos aunque al colocarlos vaya a
     * dejar fuera los que no quepan. Con unas cuantas etiquetas eso devolvía una fila
     * más ancha que la tool window, y el árbol crece hasta la fila más ancha que
     * tenga: la plataforma daba entonces por recortadas todas las tarjetas y al pasar
     * el ratón sacaba su ventanita de «fila completa» por fuera del panel, con los
     * botones de la derecha dentro. Ir a pulsarlos sacaba el ratón de la fila y la
     * ventanita se cerraba antes de llegar.
     *
     * El tope se pone aquí y no en cada trozo porque la regla es una sola —la tarjeta
     * ocupa lo que se ve— y así vale también para el trozo que pida de más mañana.
     * Recortada la medida, el que sobra se queda fuera por su cuenta: [ChipRow] ya
     * deja de colocar lo que no cabe, y lo hace por orden de importancia.
     */
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return if (widthLimit in 1 until size.width) Dimension(widthLimit, size.height) else size
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
                    // El resalte del ratón va **aquí dentro**, con la forma de la
                    // tarjeta. El del árbol se apaga en el panel: lo pinta detrás del
                    // renderer y más ancho que él, así que asomaba por los bordes.
                    if (pendingHovered) {
                        g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                        g2.fill(shape)
                    }
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
     * El fondo de la tarjeta, derivado del del propio árbol: un tono más claro en los
     * temas oscuros y uno más oscuro en los claros. Calcularlo así —y no fijar dos
     * colores— es lo que hace que se vea en cualquier tema, incluidos los de terceros.
     */
    private fun cardColorFor(tree: JTree): Color {
        val background = RenderingUtil.getBackground(tree)
        return if (ColorUtil.isDark(background)) ColorUtil.brighter(background, 1) else ColorUtil.darker(background, 1)
    }

    /** Lo mismo un paso más allá: el recuadro del código tiene que verse sobre la tarjeta. */
    private fun codeColorFor(tree: JTree): Color {
        val background = RenderingUtil.getBackground(tree)
        return if (ColorUtil.isDark(background)) ColorUtil.brighter(background, 3) else ColorUtil.darker(background, 3)
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
        val total = visibleWidth(tree)
        if (total <= 0) return 0

        val indent = indentOf(tree, node)
        val insets = border?.getBorderInsets(this)
        val stripe = insets?.left ?: 0
        val box = if (checkbox.isVisible) checkbox.preferredSize.width else 0
        val right = (insets?.right ?: 0) + if (actions.isVisible) actions.preferredSize.width else 0
        return total - indent - stripe - box - right - JBUI.scale(MARGIN)
    }

    /**
     * Lo que el árbol sangra a [node] por su nivel.
     *
     * `depthOffset` de `BasicTreeUI` con raíz oculta y sin manecillas: la sangría de un
     * nodo de nivel 1 es cero, y la de una tarea dentro de una cabecera de grupo, un
     * escalón.
     */
    private fun indentOf(tree: JTree, node: DefaultMutableTreeNode?): Int {
        val ui = tree.ui as? BasicTreeUI
        val step = (ui?.leftChildIndent ?: 0) + (ui?.rightChildIndent ?: 0)
        return step * ((node?.level ?: 1) - 1).coerceAtLeast(0)
    }

    /**
     * El hueco que se ve del árbol: el del viewport si está dentro de uno, y si no el
     * suyo. Contra el viewport y no contra el árbol porque el árbol crece hasta la
     * fila más ancha, y medir contra lo que ya ha crecido nunca dejaría de crecer.
     */
    private fun visibleWidth(tree: JTree): Int =
        (tree.parent as? JViewport)?.width?.takeIf { it > 0 } ?: tree.width

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

    /**
     * Recorta con puntos suspensivos lo que no quepa en una línea.
     *
     * Trabaja sobre tramos y no sobre una cadena porque desde que la fila entiende
     * Markdown una línea puede mezclar estilos, y cada uno mide distinto: recortar
     * midiéndolo todo con la fuente base cortaría donde no toca en cuanto hubiera
     * una palabra en negrita.
     */
    private fun ellipsize(runs: List<Run>, available: Int): TitleWrap.Fit {
        if (runs.isEmpty()) return TitleWrap.Fit(emptyList(), false)
        if (available <= 0) return TitleWrap.Fit(listOf(runs), false)

        var used = 0
        val out = mutableListOf<Run>()
        for (run in runs) {
            val width = measure(run.text, run.style)
            if (used + width <= available) {
                out += run
                used += width
                continue
            }
            // Aquí es donde se sale: se recorta este tramo y se tiran los siguientes.
            val dots = measure(TitleWrap.ELLIPSIS, run.style)
            var end = run.text.length
            while (end > 0 && used + measure(run.text.substring(0, end), run.style) + dots > available) end--
            out += run.withText(run.text.substring(0, end).trimEnd() + TitleWrap.ELLIPSIS)
            return TitleWrap.Fit(listOf(out), true)
        }
        return TitleWrap.Fit(listOf(out), false)
    }

    // ------------------------------------------------------------ hit testing

    /**
     * Lo que hay bajo [point] y responde a **un** clic: el enlace —uno si el ratón
     * está sobre un fragmento del título, todos los de la tarea si está sobre el
     * indicador—, el ancla de código o el distintivo de prioridad.
     *
     * Es **una** consulta y no una por tipo porque resolverlo obliga a montar y medir
     * la fila, y esto corre en cada píxel que recorre el ratón. Medir la fila es
     * trabajo de [tagAt]; aquí sólo se traduce la etiqueta.
     *
     * Hubo aquí un descarte por modelo que se saltaba la medida en las tarjetas sin
     * nada pulsable. Ya no queda ninguna: **toda** tarea enseña su distintivo de
     * prioridad y toda tarea lo puede pulsar. Tampoco se echa de menos, porque el
     * mismo movimiento del ratón mide la fila de todas formas para el cursor de texto
     * —ver [caretAt] y [RowClicks]—.
     */
    fun hotspotAt(tree: JTree, point: Point): Hotspot? {
        val hit = taskAt(tree, point) ?: return null
        val task = hit.node.task
        return when (val tag = tagAt(tree, hit, point)) {
            LINKS -> Hotspot.Links(task.links)
            is TaskLink -> Hotspot.Links(listOf(tag))
            is CodeAnchor -> Hotspot.Anchor(tag)
            is TaskPriority -> Hotspot.Priority(task)
            is AttachmentId -> Hotspot.Image(task.repo, tag)
            else -> null
        }
    }

    /** Lo pulsable de una fila. Ver [hotspotAt]. */
    sealed interface Hotspot {
        class Links(val links: List<TaskLink>) : Hotspot
        class Anchor(val anchor: CodeAnchor) : Hotspot
        class Priority(val task: Task) : Hotspot
        class Image(val repo: RepoKey, val id: AttachmentId) : Hotspot
    }

    /** Una fila de tarea resuelta: el nodo y su fila, que hacen falta los dos para medirla. */
    private class Hit(val node: TaskNode, val row: Int)

    private fun taskAt(tree: JTree, point: Point): Hit? {
        val row = rowAtHeight(tree, point.y)
        if (row < 0) return null
        val node = tree.getPathForRow(row)?.lastPathComponent as? TaskNode ?: return null
        return Hit(node, row)
    }

    /**
     * La etiqueta del fragmento que hay bajo [point]: es lo que identifica qué parte
     * de la fila se ha pulsado sin volver a medir texto a mano.
     *
     * Hay que preparar el renderer para esa fila antes de preguntarle: es **el mismo
     * objeto** el que pinta todas, así que sus fragmentos son los de la última que se
     * pintó.
     */
    private fun tagAt(tree: JTree, hit: Hit, point: Point): Any? {
        val bounds = paintedRowBounds(tree, hit.row) ?: return null
        prepare(tree, hit.node, hit.row, bounds)

        var x = point.x - bounds.x - lines.x
        var y = point.y - bounds.y - lines.y
        var line = childAt(lines, x, y) ?: return null
        // Una vista previa no lleva fragmentos: su «etiqueta» es la imagen misma, y
        // sólo cuando de verdad se está viendo — un marcador de carga no amplía nada.
        if (line is CardImageView) return line.attachment
        // La línea de distintivos es a su vez una fila de componentes, así que hay
        // que bajar un nivel más para dar con el que está bajo el ratón.
        if (line === meta) {
            meta.doLayout()
            x -= meta.x
            y -= meta.y
            line = childAt(meta, x, y) ?: return null
        }
        val target = line as? SimpleColoredComponent ?: return null

        val local = x - target.x
        if (local < 0) return null
        // El icono no es un fragmento con etiqueta, pero es la mitad visible de un
        // distintivo pequeño, y muchas veces la mitad a la que se apunta: el punto de
        // color **es** la prioridad, y el indicador de enlaces es icono y contador.
        // Dejarlo fuera dejaba medio control sin responder.
        if (target.findFragmentAt(local) == SimpleColoredComponent.FRAGMENT_ICON) {
            return if (target === detail) LINKS else target.firstTag()
        }
        return target.getFragmentTagAt(local)
    }

    /**
     * La etiqueta del primer tramo que lleve una: es la del distintivo entero, porque
     * cada uno es un componente con un solo tramo. Ver [renderChips].
     */
    private fun SimpleColoredComponent.firstTag(): Any? =
        (0 until fragmentCount).firstNotNullOfOrNull { getFragmentTag(it) }

    /**
     * Qué control de la fila hay bajo [point], si hay alguno. Mismo truco que
     * [hotspotAt]: se prepara el renderer para esa fila y se pregunta al panel de la
     * derecha, que es donde viven los tres.
     *
     * Se pregunta por lo que está **pintado**, no por lo que podría estarlo: los
     * controles dependen de [hoveredRow] y preparar la fila los deja visibles o no
     * exactamente igual que al pintarla. Sin eso, un clic acertaría en un menú que
     * nadie está viendo.
     */
    fun targetAt(tree: JTree, point: Point): RowTarget? {
        val row = rowAtHeight(tree, point.y)
        if (row < 0) return null
        val node = tree.getPathForRow(row)?.lastPathComponent as? TaskNode ?: return null
        val bounds = paintedRowBounds(tree, row) ?: return null

        getTreeCellRendererComponent(tree, node, tree.isRowSelected(row), false, true, row, false)
        setBounds(0, 0, bounds.width, bounds.height)
        doLayout()
        if (!actions.isVisible) return null
        actions.doLayout()

        val child = childAt(actions, point.x - bounds.x - actions.x, point.y - bounds.y - actions.y)
        return when {
            child === expand && expandActive -> RowTarget.EXPAND
            child === bookmark && bookmarkActive -> RowTarget.BOOKMARK
            child === more && menuActive -> RowTarget.MENU
            else -> null
        }
    }

    /**
     * Si [point] cae en la banda de la casilla de completar. Se prepara la fila y se
     * mide igual que en [targetAt]; el componente que se busca aquí es el que pone la
     * plataforma.
     *
     * Es una **banda** —todo lo que quede a la izquierda del borde derecho de la
     * casilla— y no su rectángulo exacto, porque lo único que se decide con esto es
     * qué ignora el doble clic. `CheckboxTree` resuelve el clic simple con una cuenta
     * propia que no coincide píxel a píxel con ésta, y quedarse corto dejaría puntos
     * donde el primer clic marca la tarea y el segundo abre además el diálogo.
     */
    fun isOnCheckbox(tree: JTree, point: Point): Boolean {
        val row = rowAtHeight(tree, point.y)
        if (row < 0) return false
        val node = tree.getPathForRow(row)?.lastPathComponent as? TaskNode ?: return false
        val bounds = paintedRowBounds(tree, row) ?: return false

        getTreeCellRendererComponent(tree, node, tree.isRowSelected(row), false, true, row, false)
        setBounds(0, 0, bounds.width, bounds.height)
        doLayout()
        val box = threeStateCheckBox
        return box.isVisible && point.x - bounds.x < box.x + box.width
    }

    // ------------------------------------------- selección de texto: medida

    /**
     * El punto del texto que hay bajo [point], o `null` si ahí no hay texto de una
     * tarjeta —una cabecera, la línea de distintivos, el hueco de los controles—.
     *
     * Es el mismo procedimiento que [tagAt]: se prepara el renderer para esa fila
     * —es el mismo objeto el que pinta todas— y se mide sobre lo que acaba de quedar
     * montado. La diferencia es la resolución: aquí no basta con saber qué fragmento
     * hay debajo, hace falta el carácter.
     */
    fun caretAt(tree: JTree, point: Point): TextCaret? {
        val hit = taskAt(tree, point) ?: return null
        val bounds = paintedRowBounds(tree, hit.row) ?: return null
        prepare(tree, hit.node, hit.row, bounds)

        val x = point.x - bounds.x - lines.x
        val y = point.y - bounds.y - lines.y
        val components = textComponents()
        val index = components.indexOfFirst { y >= it.y && y < it.y + it.height }
        if (index < 0) return null
        // Los bordes de la línea cuentan: a su derecha empieza el hueco de los
        // controles, y un cursor de texto allí prometería marcar lo que no se puede.
        val component = components[index]
        if (x < component.x || x >= component.x + component.width) return null
        return TextCaret(hit.node.task.id, hit.row, TextPos(index, offsetAt(index, component, x)))
    }

    /**
     * El punto del texto de la fila [row] al que apunta [point], **pegado** a esa
     * tarjeta: por encima de su primera línea es el principio y por debajo de la
     * última es el final.
     *
     * Es lo que hace que arrastrar fuera de la tarjeta no se lleve la lista entera
     * por delante, y de paso lo que permite marcar hasta el final del cuerpo sin
     * tener que soltar justo detrás de la última letra.
     */
    fun caretTowards(tree: JTree, row: Int, point: Point): TextPos? {
        val node = tree.getPathForRow(row)?.lastPathComponent as? TaskNode ?: return null
        val bounds = paintedRowBounds(tree, row) ?: return null
        prepare(tree, node, row, bounds)

        val components = textComponents()
        if (components.isEmpty()) return null
        val last = components.size - 1
        val x = point.x - bounds.x - lines.x
        val y = point.y - bounds.y - lines.y

        if (y < components.first().y) return TextPos(0, 0)
        if (y >= components[last].y + components[last].height) return TextPos(last, lengthOf(last))

        val index = components.indexOfFirst { y >= it.y && y < it.y + it.height }.coerceAtLeast(0)
        return TextPos(index, offsetAt(index, components[index], x))
    }

    /**
     * El texto de la tarjeta [row], línea a línea y tal y como se está pintando. Es
     * lo que se copia: las líneas son las de la pantalla, así que lo que se pega es
     * exactamente lo que se marcó.
     */
    fun cardText(tree: JTree, row: Int): List<String> {
        val node = tree.getPathForRow(row)?.lastPathComponent as? TaskNode ?: return emptyList()
        val bounds = paintedRowBounds(tree, row) ?: return emptyList()
        prepare(tree, node, row, bounds)
        return pendingCard.map { runs -> runs.joinToString("") { it.text } }
    }

    /** Deja el renderer montado y medido para la fila [row], que es el único estado que tiene. */
    private fun prepare(tree: JTree, node: TaskNode, row: Int, bounds: Rectangle) {
        getTreeCellRendererComponent(tree, node, tree.isRowSelected(row), false, true, row, false)
        setBounds(0, 0, bounds.width, bounds.height)
        doLayout()
        lines.doLayout()
    }

    /** Las líneas de texto de la tarjeta montada, en el orden en que se leen. */
    private fun textComponents(): List<ColoredTreeCellRenderer> =
        (listOf(textRenderer) + extraLines).take(pendingCard.size)

    private fun lengthOf(line: Int): Int =
        pendingCard.getOrNull(line).orEmpty().sumOf { it.text.length }

    /**
     * El carácter de la línea [line] que hay a [x] píxeles del borde izquierdo de la
     * pila de líneas.
     *
     * Se mide con la misma función que envuelve el texto, así que el corte cae donde
     * cayeron las letras. Lo único que no se puede calcular es dónde empieza el texto
     * dentro del componente: eso lo pone la plataforma y lo contesta [textInset].
     */
    private fun offsetAt(line: Int, component: ColoredTreeCellRenderer, x: Int): Int {
        val runs = pendingCard.getOrNull(line).orEmpty()
        if (runs.isEmpty()) return 0
        val target = x - component.x - textInset(component)
        if (target <= 0) return 0

        var consumed = 0
        var offset = 0
        for (run in runs) {
            val width = measure(run.text, run.style)
            if (target > consumed + width) {
                consumed += width
                offset += run.text.length
                continue
            }
            return offset + nearestChar(run, target - consumed)
        }
        return offset
    }

    /** El borde entre caracteres de [run] más cercano a [x] píxeles de su principio. */
    private fun nearestChar(run: Run, x: Int): Int {
        var previous = 0
        for (index in 1..run.text.length) {
            val width = measure(run.text.substring(0, index), run.style)
            if (width >= x) return if (x - previous <= width - x) index - 1 else index
            previous = width
        }
        return run.text.length
    }

    /**
     * Dónde empieza el texto dentro de una línea: el relleno que `SimpleColoredComponent`
     * le pone por delante.
     *
     * Se **pregunta** en vez de calcularse, barriendo con `findFragmentAt` —la única
     * API pública que lo sabe— hasta dar con el primer fragmento. Reconstruirlo
     * sumando borde y relleno sería copiar las interioridades del componente, que es
     * justo lo que se rompe al actualizar la plataforma. El barrido cuesta, así que el
     * resultado se guarda por componente: sólo depende del borde y del relleno, no del
     * texto, y esos no cambian en la vida de la fila.
     */
    private fun textInset(component: SimpleColoredComponent): Int {
        insets[component]?.let { return it }
        for (x in 0..MAX_TEXT_INSET) {
            if (component.findFragmentAt(x) == 0) {
                insets[component] = x
                return x
            }
        }
        return 0
    }

    private val insets = IdentityHashMap<SimpleColoredComponent, Int>()

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
    enum class RowTarget { EXPAND, BOOKMARK, MENU }

    private companion object {
        /** Etiqueta del contador del indicador: representa *todos* los enlaces de la fila. */
        val LINKS = Any()

        /** El distintivo del ancla se pinta como enlace porque se pulsa como un enlace. */
        val ANCHOR_STYLE = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_SMALLER,
            SimpleTextAttributes.LINK_ATTRIBUTES.fgColor,
        )

        /** Apagado es el mismo icono translúcido: la plataforma no trae variante hueca. */
        val BOOKMARK_ON = AllIcons.Nodes.Bookmark
        val BOOKMARK_OFF = IconLoader.getTransparentIcon(AllIcons.Nodes.Bookmark, 0.35f)

        /** Separación entre tarjetas, redondeo y franja. El punto de color, en [PriorityDot]. */
        const val CARD_GAP = 2
        const val CARD_ARC = 10
        const val STRIPE = 3
        const val STRIPE_GAP = 5
        const val PAD_V = 3
        const val PAD_H = 4
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
         * Tope de líneas de una tarjeta desplegada. No es un límite de diseño sino un
         * seguro: una tarea con un fichero entero pegado dentro crearía un componente
         * por línea, y a partir de cierto punto lo que hace falta es abrirla, no
         * desplegarla.
         */
        const val MAX_CARD_LINES = 120

        /**
         * Tope de vistas previas por tarjeta, y el mismo seguro que [MAX_CARD_LINES]:
         * una tarea con veinte capturas dentro no se lee desplegando la tarjeta, se
         * abre.
         */
        const val MAX_CARD_IMAGES = 20

        /**
         * Cuánto tiñe el fondo de la selección de texto. Bastante para verse sobre la
         * tarjeta y sobre una fila ya seleccionada, poco para no tapar la letra.
         */
        const val SELECTION_ALPHA = 0.28

        /** Hasta dónde se busca el principio del texto de una línea. Ver [textInset]. */
        const val MAX_TEXT_INSET = 48

        /**
         * Distintivos reutilizables. Se crean una vez y se muestran u ocultan: el
         * renderer pinta todas las filas, así que crear componentes por fila sería
         * basura en cada repintado.
         *
         * Nueve desde que la prioridad se enseña siempre: con ocho, la tarjeta de
         * fábrica con cuatro etiquetas perdía el último —el estado— sólo por haber
         * ganado el distintivo que antes se callaba.
         */
        const val MAX_CHIPS = 9

        /** Holgura para la barra de desplazamiento y el borde derecho. */
        const val MARGIN = 12
    }
}
