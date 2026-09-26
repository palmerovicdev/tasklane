package com.tasklane.code

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.text.DateFormatUtil
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.domain.model.AnchorMarkerStyle
import com.tasklane.domain.model.AnchorResolver
import com.tasklane.domain.model.AnchoredTask
import com.tasklane.domain.model.AnchoredTasks
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.service.AttachmentService
import com.tasklane.service.TaskService
import com.tasklane.ui.toolwindow.TaskReveal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.event.MouseEvent

/**
 * Las marcas de tarea sobre el código: quién las pone, cuándo y por qué desaparecen.
 *
 * Es la mitad que faltaba del ancla. Desde la tarea ya se podía ir al código; desde el
 * código no había forma de saber que alguien había dejado una nota ahí, así que la nota
 * sólo aparecía si uno se acordaba de ir a buscarla —y las notas que hay que recordar no
 * hacen falta—.
 *
 * **Las marcas las pone el modelo, no el analizador.** Cuelgan de `RangeHighlighter`s
 * propios del editor, no de un `LineMarkerProvider`: así no hace falta PSI —un ancla
 * vale en un `.txt` igual que en un `.kt`— y, sobre todo, apagar una marca ocurre cuando
 * la tarea cambia de estado y no cuando al analizador le toque pasar. Ver [AnchorGutter].
 *
 * **Lo terminal no se marca**, que es lo que impide que el margen se convierta en un
 * cementerio; la decisión vive en [AnchoredTasks], que es dominio puro y se prueba sin
 * IDE.
 *
 * **La línea se recalcula al instalar.** Se guarda un número de línea, pero el fichero
 * cambia entre sesiones: [AnchorResolver] reencuentra la línea por su texto, igual que
 * al abrir el ancla desde la tarjeta. A partir de ahí el `RangeHighlighter` se mueve
 * solo con las ediciones, así que dentro de una sesión la marca sigue al código sin que
 * haya que volver a resolver nada.
 *
 * El índice se calcula **fuera del EDT** y sólo se vuelve al EDT a pintar, que es lo que
 * exigen el `MarkupModel` y el `InlayModel`.
 *
 * **Un ancla de varias líneas tiñe su bloque** (2.15.0): el icono o la pastilla siguen en
 * la primera línea, que es donde se pulsa, y el fondo de todo el bloque lleva un poco del
 * color de la prioridad. Ver [blocks].
 */
@Service(Service.Level.PROJECT)
internal class AnchorMarkers(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    private val workspace = TasklaneWorkspaceService.getInstance(project)

    private val _style = MutableStateFlow(workspace.anchorMarker)

    /**
     * Cómo se marcan las anclas. Se escribe desde los ajustes; leerlo y escribirlo aquí
     * —y no en el `WorkspaceService` directamente— es lo que hace que cambiarlo repinte
     * los editores abiertos en vez de esperar a que se reabran.
     */
    var style: AnchorMarkerStyle
        get() = _style.value
        set(value) {
            if (value == _style.value) return
            workspace.anchorMarker = value
            _style.value = value
        }

    /**
     * Lo último que llegó del modelo. Lo lee [install] cuando se abre un editor nuevo.
     *
     * `@Volatile` porque el tooltip de la pastilla se compone en un hilo de fondo —hay
     * que leer las capturas— y sin esto podría estar mirando un modelo viejo.
     */
    @Volatile
    private var model = Model(_style.value, TasklaneConfig.DEFAULT, -1L)

    /** Lo que hay puesto en cada editor, para poder quitarlo exactamente. */
    private val marks = HashMap<Editor, Marks>()

    /** Ver [anchorsIn]. Se vacía cuando cambia la revisión del almacén. */
    private val anchorCache = java.util.concurrent.ConcurrentHashMap<String, List<AnchoredTask>>()
    private var cachedRevision = -1L

    /** El hint de las pastillas. Ver [AnchorHover]: lo escondemos nosotros, no la plataforma. */
    private val hover = AnchorHover(this)

    init {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) = install(event.editor)
                override fun editorReleased(event: EditorFactoryEvent) = forget(event.editor)
            },
            this,
        )

        scope.launch {
            TaskService.getInstance(project).snapshot
                .combine(_style) { snapshot, style -> Model(style, snapshot.config, snapshot.revision) }
                // El snapshot se reemite por cosas que no cambian ni una marca —qué
                // repositorio está activo, cuál acaba de terminar de cargar—, y repintar
                // el margen de cada editor abierto por cada una de ellas se notaría.
                .distinctUntilChanged()
                .collect { next ->
                    withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                        model = next
                        EditorFactory.getInstance().allEditors.forEach(::install)
                    }
                }
        }
    }

    // ------------------------------------------------------------------ pintar

    /**
     * Deja el editor con las marcas que le tocan **ahora**: quita lo anterior y vuelve a
     * poner. Reinstalar entero y no ir buscando diferencias porque son un puñado de
     * marcas por fichero, y un `diff` aquí sería código difícil por un ahorro invisible.
     */
    private fun install(editor: Editor) {
        if (editor.project != project || editor.isDisposed) return
        forget(editor)
        if (model.style == AnchorMarkerStyle.OFF) return
        // Sólo el editor de verdad: en un diff o en una consola el ancla no significa
        // nada, y el editor del propio diálogo de tarea no tiene fichero detrás.
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return

        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        // Un documento sin líneas no tiene dónde poner nada, y preguntarle por el
        // principio de la línea 0 sería pedirle un sitio que no existe.
        if (editor.document.lineCount <= 0) return
        val entries = anchorsIn(CodeAnchors.pathOf(project, file))
        if (entries.isEmpty()) return

        val fresh = Marks()
        when (model.style) {
            AnchorMarkerStyle.GUTTER -> gutter(editor, entries, fresh)
            AnchorMarkerStyle.INLINE -> inline(editor, entries, fresh)
            AnchorMarkerStyle.OFF -> return
        }
        blocks(editor, entries, fresh)
        marks[editor] = fresh
    }

    /**
     * El fondo de los bloques anclados (2.15.0).
     *
     * **El color sale al pintar, no al instalar**: es la mezcla del fondo del esquema del
     * editor con el de la prioridad, y un `JBColor.lazy` la rehace en cada repintado. Así
     * cambiar de tema o de esquema no deja un bloque claro sobre un editor oscuro hasta
     * que algo reinstale las marcas. Mezclado y no translúcido porque lo que se pinta
     * debajo de un fondo con transparencia depende del orden en que el editor pinte cada
     * capa, y eso no es contrato de nadie.
     *
     * **Por debajo de la línea del cursor** ([BLOCK_LAYER]): dentro del bloque, la línea
     * donde se escribe tiene que seguir viéndose; y por encima de la sintaxis, que casi
     * nunca pinta fondo pero cuando lo hace —un fragmento de otro lenguaje— no debe tapar
     * el bloque.
     *
     * Dos tareas sobre el mismo bloque son **un** fondo, el de la que manda —la primera,
     * ver [AnchoredTasks.order]—, como comparten icono en el margen. Dos bloques que se
     * solapan se tiñen los dos, y en lo común gana uno: no se suman, que es lo que haría
     * que un bloque anidado en otro gritara más que el resto.
     */
    private fun blocks(editor: Editor, entries: List<AnchoredTask>, marks: Marks) {
        val document = editor.document
        val seen = HashSet<IntRange>()
        for (entry in entries) {
            if (!entry.anchor.isRange) continue
            val lines = AnchorResolver.range(entry.anchor, document.lineCount) { index -> lineAt(document, index) }
            if (!seen.add(lines)) continue
            val priority = colorOf(listOf(entry))
            val tint = JBColor.lazy { ColorUtil.mix(editor.colorsScheme.defaultBackground, priority, BLOCK_TINT) }
            marks.highlighters += editor.markupModel.addRangeHighlighter(
                document.getLineStartOffset(lines.first),
                document.getLineEndOffset(lines.last),
                BLOCK_LAYER,
                TextAttributes().apply { backgroundColor = tint },
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        }
    }

    /** Un icono por línea, con todas las tareas de esa línea detrás. */
    private fun gutter(editor: Editor, entries: List<AnchoredTask>, marks: Marks) {
        val document = editor.document
        for ((line, group) in byLine(entries, document)) {
            val highlighter = editor.markupModel.addRangeHighlighter(
                null,
                document.getLineStartOffset(line),
                document.getLineEndOffset(line),
                // Por encima de la sintaxis y por debajo de los errores: una nota no
                // puede tapar un aviso del compilador.
                HighlighterLayer.ADDITIONAL_SYNTAX,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.gutterIconRenderer = AnchorGutter(project, group, iconOf(group), tooltipOf(group))
            marks.highlighters += highlighter
        }
    }

    /**
     * Una pastilla por **posición exacta**: dos tareas ancladas al mismo carácter
     * comparten marca, como en el margen comparten icono. Dos en la misma línea pero en
     * columnas distintas son dos marcas, que es justo lo que este estilo viene a enseñar.
     */
    private fun inline(editor: Editor, entries: List<AnchoredTask>, marks: Marks) {
        val document = editor.document
        val byOffset = entries
            .groupBy { offsetOf(it, document) }
            .toSortedMap()
        for ((offset, group) in byOffset) {
            val color = colorOf(group)
            val chip = AnchorChip(
                icon = iconOf(group),
                // El estado de la que manda, que es la misma que da el color: ver
                // [AnchoredTasks.order]. Con la configuración de fábrica sale `TODO`.
                label = AnchorChip.labelOf(model.config.stateOrDefault(group.first().task.stateId).name),
                color = color,
                background = ColorUtil.withAlpha(color, CHIP_ALPHA),
            )
            // relatesToPrecedingText = false: la marca pertenece a lo que tiene delante
            // —`load(key)`, no el paréntesis de antes—, así que al borrar hacia atrás no
            // se la lleva por delante.
            val inlay = editor.inlayModel.addInlineElement(offset, false, chip) ?: continue
            marks.inlays += inlay
            marks.byInlay[inlay] = group
        }
        if (marks.inlays.isEmpty()) return

        // El ratón sólo hace falta con este estilo: el del margen lo atiende la
        // plataforma a través del `GutterIconRenderer`.
        val listeners = Disposer.newDisposable("tasklane-anchor-chips")
        Disposer.register(this, listeners)
        editor.addEditorMouseListener(
            object : EditorMouseListener {
                override fun mouseClicked(event: EditorMouseEvent) = click(editor, event)
                override fun mouseExited(event: EditorMouseEvent) = hover.hide()
            },
            listeners,
        )
        editor.addEditorMouseMotionListener(
            object : EditorMouseMotionListener {
                override fun mouseMoved(event: EditorMouseEvent) = hover(editor, event)
            },
            listeners,
        )
        // Al desplazar el editor el texto se mueve y el hint se quedaría señalando a otra
        // línea. La rueda del ratón no genera un `mouseMoved`, así que sin esto se queda.
        editor.scrollingModel.addVisibleAreaListener(VisibleAreaListener { hover.hide() }, listeners)
        marks.listeners = listeners
    }

    /**
     * Quita lo puesto. Con el editor ya destruido no se toca nada suyo: sus marcas se
     * fueron con él, y pedirle el `MarkupModel` o soltar un inlay muerto sería trabajar
     * sobre un cadáver. Lo único que sigue siendo nuestro son los oyentes.
     */
    private fun forget(editor: Editor) {
        val gone = marks.remove(editor) ?: return
        // Las marcas se van; un hint sobre una de ellas no puede sobrevivirlas.
        if (gone.inlays.isNotEmpty()) hover.hide()
        if (!editor.isDisposed) {
            gone.highlighters.forEach(editor.markupModel::removeHighlighter)
            gone.inlays.filter { it.isValid }.forEach(Disposer::dispose)
        }
        gone.listeners?.let(Disposer::dispose)
    }

    // -------------------------------------------------------------------- ratón

    private fun click(editor: Editor, event: EditorMouseEvent) {
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return
        val entries = entriesAt(editor, event) ?: return
        hover.hide()
        TaskReveal.show(project, entries.map { it.task })
        event.consume()
    }

    /**
     * El hint de la pastilla, a mano.
     *
     * El margen lo da hecho —`GutterIconRenderer.getTooltipText`—, pero un inlay es un
     * dibujo dentro del texto y para la plataforma no significa nada, así que hay que
     * decidir aquí cuándo aparece y, sobre todo, cuándo **no** desaparece: ver
     * [AnchorHover].
     */
    private fun hover(editor: Editor, event: EditorMouseEvent) {
        val inlay = editor.inlayModel.getElementAt(event.mouseEvent.point)
        val entries = inlay?.let { marks[editor]?.byInlay?.get(it) }
        if (inlay == null || entries == null) {
            hover.hide()
            return
        }
        // El texto se compone fuera del EDT: ver [AnchorHover]. Es lo que permite que
        // aquí se vaya al disco a por las capturas.
        hover.show(editor, inlay, event.mouseEvent.point) { tooltipOf(entries, previewsOf(entries)) }
    }

    private fun entriesAt(editor: Editor, event: EditorMouseEvent): List<AnchoredTask>? {
        val inlay = editor.inlayModel.getElementAt(event.mouseEvent.point) ?: return null
        return marks[editor]?.byInlay?.get(inlay)
    }

    // ------------------------------------------------------------------ cuentas

    /**
     * En qué línea cae cada ancla **ahora mismo**, contra el documento que se está
     * viendo —no contra el disco: con cambios sin guardar, el disco diría otra cosa—.
     */
    private fun byLine(entries: List<AnchoredTask>, document: Document): Map<Int, List<AnchoredTask>> =
        entries.groupBy { entry ->
            AnchorResolver.resolve(entry.anchor, document.lineCount) { index -> lineAt(document, index) }
        }

    private fun offsetOf(entry: AnchoredTask, document: Document): Int {
        val line = AnchorResolver.resolve(entry.anchor, document.lineCount) { index -> lineAt(document, index) }
        val start = document.getLineStartOffset(line)
        return start + entry.anchor.column.coerceAtMost(document.getLineEndOffset(line) - start)
    }

    private fun lineAt(document: Document, index: Int): String =
        document.getText(TextRange(document.getLineStartOffset(index), document.getLineEndOffset(index)))

    /** El color de la prioridad que manda, que es la de la primera: ver [AnchoredTasks.order]. */
    private fun colorOf(entries: List<AnchoredTask>): Color =
        model.config.priorityOrDefault(entries.first().task.priorityId)
            .let { JBColor(it.colorLight, it.colorDark) }

    private fun iconOf(entries: List<AnchoredTask>) = AnchorIcon(colorOf(entries), entries.size > 1)

    private fun tooltipOf(
        entries: List<AnchoredTask>,
        previews: Map<TaskId, List<AnchorTooltip.Preview>> = emptyMap(),
    ): String = AnchorTooltip.html(
        entries = entries,
        config = model.config,
        formatDate = { DateFormatUtil.formatPrettyDate(it.toEpochMilli()) },
        hint = TasklaneBundle.message("editor.anchor.hint"),
        more = { TasklaneBundle.message("editor.anchor.more", it) },
        previews = { previews[it.task.id].orEmpty() },
    )

    /**
     * Las capturas que enseña el tooltip de la pastilla, leídas y medidas.
     *
     * **Bloqueante**: sólo desde el hilo de fondo de [AnchorHover]. El del margen no
     * pasa por aquí a propósito — su texto se calcula al instalar las marcas, en el EDT
     * y para todas las anclas del fichero de una vez, y eso no puede ir al disco.
     *
     * El presupuesto de [MAX_PREVIEWS] es del tooltip entero y no de cada tarea: cuatro
     * bloques con dos capturas cada uno no es un tooltip, es una galería que tapa el
     * código del que habla. Se gastan en orden, así que las de la tarea que manda —la de
     * más prioridad, ver [AnchoredTasks.order]— son las que se ven seguro.
     */
    private fun previewsOf(entries: List<AnchoredTask>): Map<TaskId, List<AnchorTooltip.Preview>> {
        val service = AttachmentService.getInstance(project)
        val previews = HashMap<TaskId, List<AnchorTooltip.Preview>>()
        var budget = MAX_PREVIEWS
        for (entry in entries.take(AnchorTooltip.MAX)) {
            if (budget <= 0) break
            val task = entry.task
            if (previews.containsKey(task.id)) continue
            val shots = task.attachments
                .map { it.id }
                .distinct()
                .take(budget)
                .mapNotNull { id -> previewOf(service, task.repo, id) }
            if (shots.isEmpty()) continue
            previews[task.id] = shots
            budget -= shots.size
        }
        return previews
    }

    /**
     * Una captura como la quiere el HTML: la ruta del blob y el tamaño al que cabe.
     *
     * El tamaño se calcula aquí y no se deja a Swing porque el `JLabel` mide el bloque
     * antes de que la imagen termine de cargar; sin `width` y `height` el globo se
     * dimensiona sin contar con ella y la vista previa sale cortada. Nunca se amplía,
     * por lo mismo que en `AttachmentService.preview`: una captura pequeña estirada sólo
     * se ve peor.
     *
     * Un blob que no está no deja hueco: aquí no hay sitio para el marcador de posición
     * que sí pintan la tarjeta y el diálogo, y un recuadro roto en un tooltip se lee
     * como un fallo del plugin.
     */
    private fun previewOf(service: AttachmentService, repo: RepoKey, id: AttachmentId): AnchorTooltip.Preview? {
        // La miniatura (§4.4), que es lo que se va a enseñar a 200 px: pasar el ratón
        // por un ancla no puede costar descodificar una captura de 1600.
        val image = service.thumbnail(repo, id) ?: return null
        val file = service.thumbnailFile(repo, id) ?: return null
        val factor = minOf(
            1.0,
            JBUIScale.scale(PREVIEW_WIDTH).toDouble() / image.width,
            JBUIScale.scale(PREVIEW_HEIGHT).toDouble() / image.height,
        )
        return AnchorTooltip.Preview(
            src = file.toUri().toString(),
            width = (image.width * factor).toInt().coerceAtLeast(1),
            height = (image.height * factor).toInt().coerceAtLeast(1),
        )
    }

    override fun dispose() {
        hover.hide()
        marks.keys.toList().forEach(::forget)
    }

    /**
     * Lo que hace falta para pintar, junto: estilo, configuración y de qué versión del
     * almacén habla.
     *
     * Hasta la Fase 2 llevaba **el índice entero** del proyecto —ruta → tareas
     * ancladas—, y construirlo era recorrer todas las tareas en **cada** cambio del
     * modelo. Ahora lleva el número de revisión, que es lo único que hace falta para
     * saber que lo cacheado ya no vale: las anclas de un fichero se piden por ruta
     * cuando se abre, que es el §3.6.
     */
    private data class Model(
        val style: AnchorMarkerStyle,
        val config: TasklaneConfig,
        val revision: Long,
    )

    /**
     * Qué cuelga de un fichero, cacheado mientras el almacén no cambie.
     *
     * La consulta es un salto sobre `anchor_by_path` y un puñado de filas, pero ocurre
     * en el EDT —instalar las marcas lo exige— y un fichero se reinstala cada vez que
     * gana el foco. La caché la vacía un número de revisión distinto, que es
     * exactamente «alguien escribió».
     */
    private fun anchorsIn(path: String): List<AnchoredTask> {
        val model = this.model
        if (cachedRevision != model.revision) {
            anchorCache.clear()
            cachedRevision = model.revision
        }
        return anchorCache.computeIfAbsent(path) {
            TaskService.getInstance(project).anchorsIn(it).sortedWith(AnchoredTasks.order(model.config))
        }
    }

    private class Marks {
        val highlighters = mutableListOf<RangeHighlighter>()
        val inlays = mutableListOf<Inlay<*>>()

        /** Qué tareas hay tras cada pastilla, para resolver el clic sin volver a buscar. */
        val byInlay = HashMap<Inlay<*>, List<AnchoredTask>>()
        var listeners: Disposable? = null
    }

    companion object {
        /**
         * Lo justo para que la pastilla se separe del código sin competir con él. El
         * color es el de la prioridad, que a plena opacidad dentro de una línea de texto
         * grita más que el propio código.
         */
        private const val CHIP_ALPHA = 0.18

        /** Cuántas capturas caben en un tooltip antes de que deje de serlo. */
        private const val MAX_PREVIEWS = 2

        /**
         * Cuánto color de la prioridad lleva el fondo de un bloque (2.15.0). Lo justo para
         * ver dónde empieza y dónde acaba sin leer el código a través de un filtro: el
         * bloque se queda teñido mientras la tarea esté abierta, y un fondo que se nota
         * mucho a la media hora ya no se mira.
         */
        private const val BLOCK_TINT = 0.1

        /** Ver [blocks]: encima de la sintaxis, debajo de la línea del cursor. */
        private const val BLOCK_LAYER = HighlighterLayer.CARET_ROW - 1

        /**
         * El hueco de una captura dentro del tooltip. Más ancho que esto y el globo tapa
         * la línea de la que habla; más alto y hay que mover el ratón para leerlo
         * entero, que es justo cuando el globo se esconde.
         */
        private const val PREVIEW_WIDTH = 280
        private const val PREVIEW_HEIGHT = 180

        fun getInstance(project: Project): AnchorMarkers = project.service()
    }
}
