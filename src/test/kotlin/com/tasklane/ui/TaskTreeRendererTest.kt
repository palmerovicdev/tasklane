package com.tasklane.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.CheckedTreeNode
import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.GroupKey
import com.intellij.util.ui.EmptyIcon
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.domain.text.LinkExtractor
import com.tasklane.ui.toolwindow.CardImageView
import com.tasklane.ui.toolwindow.CardPreviews
import com.tasklane.ui.toolwindow.CardSelection
import com.tasklane.ui.toolwindow.CardTextSelection
import com.tasklane.ui.toolwindow.GroupNode
import com.tasklane.ui.toolwindow.TaskNode
import com.tasklane.ui.toolwindow.TaskTreeRenderer
import com.tasklane.ui.toolwindow.TextPos
import com.tasklane.ui.toolwindow.paintedRowBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.time.Instant
import javax.swing.JTree
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.DefaultTreeModel

/**
 * El renderer de dos líneas y su *hit testing*, sin arrancar un IDE.
 *
 * Lo que se comprueba no es dónde cae cada píxel —eso depende de la fuente— sino la
 * propiedad que importa: que **existe** una zona clicable para el enlace del título y
 * otra para el indicador, y que el checkbox no es ninguna de las dos. Es justo lo que
 * se rompería al reorganizar el panel del renderer de la plataforma.
 */
class TaskTreeRendererTest {

    private val renderer = TaskTreeRenderer().apply {
        config = TasklaneConfig.DEFAULT
        // Sin IDE no hay `DateFormatUtil`; la fecha es texto para medir, nada más.
        formatDate = { "13/09/2026" }
    }

    private fun task(
        body: String,
        anchors: List<CodeAnchor> = emptyList(),
        priority: PriorityId = TasklaneConfig.NORMAL,
        tags: List<String> = emptyList(),
    ) = Task(
        id = TaskId.random(),
        repo = RepoKey.ROOT,
        body = body,
        stateId = TasklaneConfig.TODO,
        priorityId = priority,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        links = LinkExtractor.extract(body),
        attachments = ImageRefParser.parse(body),
        anchors = anchors,
        tags = tags,
    )

    /**
     * Un árbol con una sola fila, medido como lo mediría la tool window.
     *
     * `JTree` a secas y no `CheckboxTree`: el constructor de la de la plataforma
     * instala su speed search y eso sí necesita la `Application`. Para lo que se
     * mide aquí —posiciones y fragmentos— da igual: el renderer es el mismo.
     */
    private fun treeWith(task: Task, width: Int = 600): JTree {
        val root = CheckedTreeNode("root")
        root.add(TaskNode(task, false))
        val tree = JTree(DefaultTreeModel(root))
        tree.cellRenderer = renderer
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.rowHeight = 0
        tree.setSize(width, 400)
        tree.doLayout()
        // `JTree` mide sus filas en cuanto tiene modelo, y ahí todavía no tiene ancho:
        // lo mediría todo contra cero. En la tool window es el cambio de tamaño el que
        // le tira la caché de medidas —ver `TasklanePanel`—, y esto es lo mismo que
        // hace la plataforma en `TreeUtil.invalidateCacheAndRepaint`.
        remeasure(tree)
        return tree
    }

    /**
     * Le tira al árbol las medidas que tenía guardadas, que es lo que hace la ventana
     * cada vez que cambia algo que cambia el alto de una fila (`TreeUtil
     * .invalidateCacheAndRepaint`, ver `TasklanePanel`).
     */
    private fun remeasure(tree: JTree) {
        val ui = tree.ui as BasicTreeUI
        ui.leftChildIndent = ui.leftChildIndent
    }

    /** Los enlaces que devuelve el renderer barriendo una fila de izquierda a derecha. */
    private fun sweep(tree: JTree, row: Int): Map<Int, List<String>> {
        val bounds = painted(tree, row)
        val y = bounds.y + bounds.height / 2
        return (0 until bounds.width)
            .associateWith { x -> linksAt(tree, Point(bounds.x + x, y)) }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Lo pulsable se pregunta por donde lo pregunta la lista —[TaskTreeRenderer.hotspotAt]—
     * y no por un atajo de test: si el camino real dejara de reconocer un enlace, un
     * ayudante propio lo seguiría reconociendo y el test pasaría con el clic roto.
     */
    private fun linksAt(tree: JTree, point: Point): List<String> =
        (renderer.hotspotAt(tree, point) as? TaskTreeRenderer.Hotspot.Links)?.links?.map { it.url }.orEmpty()

    private fun anchorAt(tree: JTree, point: Point): CodeAnchor? =
        (renderer.hotspotAt(tree, point) as? TaskTreeRenderer.Hotspot.Anchor)?.anchor

    @Test
    fun `el enlace del titulo es clicable y el checkbox no`() {
        val tree = treeWith(task("Revisar https://ejemplo.com/a antes del viernes"))
        val bounds = painted(tree, 0)

        val hits = sweep(tree, 0)
        assertTrue("el enlace del titulo tiene que tener zona clicable", hits.isNotEmpty())
        assertEquals(setOf(listOf("https://ejemplo.com/a")), hits.values.toSet())

        // El borde izquierdo de la fila es la franja de prioridad y el checkbox.
        assertTrue(
            "el checkbox no puede abrir el navegador",
            linksAt(tree, Point(bounds.x + 1, bounds.y + bounds.height / 2)).isEmpty(),
        )
    }

    /**
     * El indicador sigue siendo la puerta a **todos** los enlaces: el enlace no está en el
     * título, y sin él habría que entrar a editar para abrir uno que no se ve.
     */
    @Test
    fun `un enlace en el detalle tambien se alcanza por el indicador`() {
        val tree = treeWith(task("Migrar el indice\nla guia esta en https://ejemplo.com/guia"))
        val bounds = painted(tree, 0)

        // Fuera de las líneas de texto: ahí sólo está la línea de distintivos.
        val hit = scan(tree, bounds) { point ->
            linksAt(tree, point).takeIf { it.isNotEmpty() && renderer.caretAt(tree, point) == null }
        }

        assertEquals(listOf("https://ejemplo.com/guia"), hit)
    }

    /**
     * **Y donde está** (2.3.0). Con la tarjeta desplegada y la URL delante de los ojos,
     * pulsarla no hacía nada: el cuerpo se pintaba como texto gris a secas. Se busca el
     * enlace sobre la propia línea de texto —la que devuelve el cursor de texto—, plegada
     * en la de resumen y desplegada en un párrafo que plegada ni se ve.
     */
    @Test
    fun `un enlace del cuerpo se pulsa sobre su propia linea`() {
        val task = task("Migrar el indice\nla guia esta en https://ejemplo.com/guia\nlos pasos en https://ejemplo.com/pasos")
        val tree = treeWith(task)

        fun linkOnLine(line: Int): List<String>? = scan(tree, painted(tree, 0)) { point ->
            linksAt(tree, point).takeIf { it.isNotEmpty() && renderer.caretAt(tree, point)?.pos?.line == line }
        }

        assertEquals(listOf("https://ejemplo.com/guia"), linkOnLine(1))

        expand(tree, task)

        assertEquals(listOf("https://ejemplo.com/guia"), linkOnLine(1))
        assertEquals(listOf("https://ejemplo.com/pasos"), linkOnLine(2))
    }

    /**
     * Lo que pidió la 2.3.0 para la tool window estrecha: **el ancla no se cae nunca** de
     * la línea de distintivos. Si no cabe `Fichero.kt:42` entero se queda el icono, y el
     * icono lleva al mismo sitio. Lo mismo la prioridad, con su punto de color.
     *
     * Al ancho mínimo de la ventana, dentro de un grupo —la sangría es ancho que la
     * tarjeta no tiene—, con enlaces, cuatro etiquetas, una prioridad de nombre largo y un
     * fichero de nombre largo: todo lo que compite por esa línea.
     */
    @Test
    fun `el ancla y la prioridad se pulsan aunque la tarjeta vaya al minimo`() {
        renderer.config = TasklaneConfig.DEFAULT.let { base ->
            base.copy(priorities = base.priorities.map { it.copy(name = "Muy importante de verdad") })
        }
        val anchor = CodeAnchor.of("src/main/kotlin/com/ejemplo/ServicioDeAutenticacionConNombreLargo.kt", 41)
        val task = task(
            "Arreglar https://ejemplo.com/a",
            anchors = listOf(anchor),
            tags = listOf("planificacion", "pendiente", "compras", "semana"),
        )
        val tree = treeWithGroup(task, width = MIN_WIDTH)
        val bounds = painted(tree, 1)

        assertEquals(anchor, scan(tree, bounds) { anchorAt(tree, it) })
        assertEquals(
            task.id,
            scan(tree, bounds) { (renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Priority)?.task?.id },
        )
    }

    /**
     * Y que al ancho mínimo lo que queda **es el icono**, no el nombre cortado por la
     * mitad: la zona del ancla mide lo que mide un icono. A lo ancho vuelve el nombre.
     */
    @Test
    fun `sin sitio el ancla se queda en su icono y con sitio ensena el nombre`() {
        val anchor = CodeAnchor.of("src/main/kotlin/com/ejemplo/ServicioDeAutenticacionConNombreLargo.kt", 41)
        val task = task("Arreglar el login", anchors = listOf(anchor), tags = listOf("planificacion", "pendiente"))

        fun anchorWidth(width: Int): Int {
            val tree = treeWithGroup(task, width = width)
            val bounds = painted(tree, 1)
            val columns = (0 until bounds.width).filter { x ->
                (0 until bounds.height step 2).any { y -> anchorAt(tree, Point(bounds.x + x, bounds.y + y)) != null }
            }
            return if (columns.isEmpty()) 0 else columns.last() - columns.first() + 1
        }

        val narrow = anchorWidth(MIN_WIDTH - 60)
        val wide = anchorWidth(900)

        assertTrue("al minimo tiene que quedar el icono, y midio $narrow", narrow in 1..ICON_ONLY)
        assertTrue("a lo ancho tiene que verse el nombre, y midio $wide", wide > ICON_ONLY * 2)
    }

    @Test
    fun `el marcador y el menu se pueden pulsar en la fila del raton`() {
        val tree = treeWith(task("Comprar pan"))
        renderer.hoveredRow = 0
        val bounds = painted(tree, 0)

        val targets = scanAll(tree, bounds) { point -> renderer.targetAt(tree, point) }

        assertTrue("el marcador tiene que tener zona clicable", TaskTreeRenderer.RowTarget.BOOKMARK in targets)
        assertTrue("el menu tiene que tener zona clicable", TaskTreeRenderer.RowTarget.MENU in targets)
    }

    /**
     * El hueco de la tarjeta es tarjeta. Es la regresión que dejaba el marcador y el
     * menú fuera del alcance del ratón salvo justo encima de las letras: el árbol
     * pinta la fila hasta el borde pero la daba por acabada donde acaba el texto.
     */
    @Test
    fun `los controles responden mas alla de donde acaba el texto`() {
        val tree = treeWith(task("Comprar pan"))
        renderer.hoveredRow = 0
        val text = tree.getRowBounds(0)
        val card = painted(tree, 0)
        assertTrue("la tarjeta se pinta mas ancha que su texto", card.width > text.width)

        val blank = Rectangle(text.x + text.width, card.y, card.width - text.width, card.height)
        val targets = scanAll(tree, blank) { point -> renderer.targetAt(tree, point) }

        assertTrue("el marcador vive a la derecha del texto", TaskTreeRenderer.RowTarget.BOOKMARK in targets)
        assertTrue("el menu vive a la derecha del texto", TaskTreeRenderer.RowTarget.MENU in targets)
    }

    /** Las marcas de Markdown se cocinan en el renderer: a la fila llegan ya como estilo. */
    @Test
    fun `el titulo se pinta sin las marcas de Markdown`() {
        val tree = treeWith(task("**Bold text** y `codigo`"))
        renderer.getTreeCellRendererComponent(tree, tree.getPathForRow(0).lastPathComponent, false, false, true, 0, false)

        assertEquals("Bold text y codigo", renderer.textRenderer.getCharSequence(false).toString())
    }

    @Test
    fun `sin el raton encima no hay controles que pulsar`() {
        val tree = treeWith(task("Comprar pan"))
        renderer.hoveredRow = -1
        val bounds = painted(tree, 0)

        assertTrue(scanAll(tree, bounds) { point -> renderer.targetAt(tree, point) }.isEmpty())
    }

    /**
     * La banda que el doble clic ignora. No se comprueba dónde acaba —eso depende del
     * tema— sino lo que hace falta para que el gesto no se pise con el de marcar: que
     * el borde izquierdo de la fila sea casilla y el centro no.
     */
    @Test
    fun `la casilla ocupa el borde izquierdo y nada mas`() {
        val tree = treeWith(task("Comprar pan"))
        val bounds = painted(tree, 0)
        val y = bounds.y + bounds.height / 2

        assertTrue(
            "el borde izquierdo de la fila es la casilla",
            renderer.isOnCheckbox(tree, Point(bounds.x + 1, y)),
        )
        assertTrue(
            "el centro de la tarjeta tiene que abrir la tarea, no marcarla",
            !renderer.isOnCheckbox(tree, Point(bounds.x + bounds.width / 2, y)),
        )
    }

    /**
     * El distintivo del ancla es lo que convierte la nota en un salto al código: si no
     * tiene zona clicable, la función no existe. Se barre la fila entera por lo mismo
     * que con el indicador de enlaces: se afirma que **hay** zona, no en qué píxel.
     */
    @Test
    fun `el ancla de codigo tiene zona clicable en la fila`() {
        val anchor = CodeAnchor.of("src/main/kotlin/AuthService.kt", 41, text = "fun login() {")
        val tree = treeWith(task("Arreglar el login", anchors = listOf(anchor)))
        val bounds = painted(tree, 0)

        val hit = scan(tree, bounds) { point -> anchorAt(tree, point) }

        assertEquals(anchor, hit)
    }

    @Test
    fun `una tarea sin ancla no navega desde ningun punto`() {
        val tree = treeWith(task("Comprar pan"))
        val bounds = painted(tree, 0)

        assertTrue(scanAll(tree, bounds) { point -> anchorAt(tree, point) }.isEmpty())
    }

    /** El ancla y el enlace son distintivos distintos: uno no puede contestar por el otro. */
    @Test
    fun `el ancla no se confunde con el enlace del titulo`() {
        val anchor = CodeAnchor.of("a/Auth.kt", 4, text = "login()")
        val tree = treeWith(task("Revisar https://ejemplo.com/a", anchors = listOf(anchor)))
        val bounds = painted(tree, 0)

        val anchors = scanAll(tree, bounds) { point -> anchorAt(tree, point) }
        val links = scanAll(tree, bounds) { point -> linksAt(tree, point).takeIf { it.isNotEmpty() } }

        assertEquals(setOf(anchor), anchors.toSet())
        assertTrue("el enlace del titulo sigue teniendo su zona", links.isNotEmpty())
    }

    @Test
    fun `una tarea sin enlaces no responde en ningun punto`() {
        val tree = treeWith(task("Comprar pan"))
        assertTrue(sweep(tree, 0).isEmpty())
    }

    // ----------------------------------------------------------- la prioridad

    /**
     * Cambiar la prioridad desde donde se lee. Como con el ancla y el indicador, lo
     * que se afirma es que **existe** zona pulsable, no en qué píxel está.
     */
    @Test
    fun `el distintivo de prioridad se puede pulsar`() {
        val task = task("Comprar pan", priority = TasklaneConfig.HIGH)
        val tree = treeWith(task)

        val hit = scan(tree, painted(tree, 0)) {
            renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Priority
        }

        assertEquals(task.id, hit?.task?.id)
    }

    @Test
    fun `el boton de copiar esta en la linea de metadatos`() {
        val task = task("Comprar pan")
        val tree = treeWith(task)

        val hit = scan(tree, painted(tree, 0)) {
            renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Copy
        }

        assertEquals(task.id, hit?.task?.id)
    }

    /**
     * La de fábrica también, y es el caso que importa: mientras se callaba, las
     * tarjetas que nadie había tocado —las más— eran justo las que no tenían por dónde
     * cambiar de prioridad sin abrir el menú.
     */
    @Test
    fun `la prioridad de fabrica tambien se puede pulsar`() {
        val task = task("Comprar pan")
        val tree = treeWith(task)

        val hit = scan(tree, painted(tree, 0)) {
            renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Priority
        }

        assertEquals(task.id, hit?.task?.id)
    }

    /**
     * El **punto de color** cuenta como distintivo: es más pequeño que la palabra y es
     * justo a donde se apunta —el color es lo que identifica una prioridad—, así que
     * dejarlo fuera dejaba medio control sin responder.
     *
     * No se comprueba en qué píxel está el punto, sino que la zona pulsable empieza
     * donde empieza la tarjeta: el distintivo es el primero de su línea y el icono va
     * delante de su texto, así que sin el punto la zona arrancaría un icono más allá.
     */
    @Test
    fun `el punto de color de la prioridad tambien se pulsa`() {
        val tree = treeWith(task("Comprar pan", priority = TasklaneConfig.HIGH))
        val bounds = painted(tree, 0)

        val chip = firstColumn(tree, bounds) {
            renderer.hotspotAt(tree, it) is TaskTreeRenderer.Hotspot.Priority
        }
        val text = firstColumn(tree, bounds) { renderer.caretAt(tree, it) != null }

        assertNotNull("el distintivo de prioridad tiene que tener zona", chip)
        assertNotNull("la tarjeta tiene que tener texto", text)
        assertTrue(
            "la zona del distintivo empieza en $chip y el texto de la tarjeta en $text",
            chip!! <= text!! + ICON_SLACK,
        )
    }

    /** Lo pulsable se resuelve de una vez, y cada cosa sigue siendo la suya. */
    @Test
    fun `el enlace y la prioridad no se confunden entre si`() {
        val tree = treeWith(task("Revisar https://ejemplo.com/a", priority = TasklaneConfig.HIGH))
        val bounds = painted(tree, 0)

        val links = scanAll(tree, bounds) { renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Links }
        val priorities = scanAll(tree, bounds) { renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Priority }

        assertEquals(setOf("https://ejemplo.com/a"), links.flatMap { it.links }.map { it.url }.toSet())
        assertTrue("el distintivo de prioridad tiene que seguir teniendo su zona", priorities.isNotEmpty())
    }

    // ------------------------------------------------------ desplegar la tarjeta

    /**
     * Lo que justifica el botón: plegada la tarjeta resume el cuerpo en una línea, y
     * lo que no cabe ahí no hay forma de leerlo sin abrir la tarea.
     */
    @Test
    fun `desplegar la tarjeta ensena el cuerpo entero`() {
        val task = task("Comprar pan\nen la panaderia de la esquina\ny pagar en efectivo")
        val tree = treeWith(task)

        assertEquals(listOf("Comprar pan", "en la panaderia de la esquina"), renderer.cardText(tree, 0))
        val folded = cardHeight(tree, 0)

        expand(tree, task)

        assertEquals(
            listOf("Comprar pan", "en la panaderia de la esquina", "y pagar en efectivo"),
            renderer.cardText(tree, 0),
        )
        assertTrue("la tarjeta desplegada tiene que crecer", cardHeight(tree, 0) > folded)
    }

    /** Y vuelve a ser la de antes: el botón es un interruptor, no un camino de ida. */
    @Test
    fun `volver a pulsar deja la tarjeta como estaba`() {
        val task = task("Comprar pan\nen la panaderia\ny pagar en efectivo")
        val tree = treeWith(task)
        val folded = renderer.cardText(tree, 0)

        expand(tree, task)
        expand(tree, task)

        assertEquals(folded, renderer.cardText(tree, 0))
    }

    /**
     * Un botón que no hace nada es peor que ninguno: una tarjeta que ya se ve entera
     * no lo enseña, y una desplegada lo enseña aunque el ratón no esté encima —si no,
     * no habría forma evidente de volver a plegarla—.
     */
    @Test
    fun `el boton de desplegar solo aparece si hay algo escondido`() {
        val short = task("Comprar pan")
        val long = task("Comprar pan\nen la panaderia\ny pagar en efectivo")

        val plain = treeWith(short)
        renderer.hoveredRow = 0
        assertFalse(
            "una tarjeta que cabe entera no tiene nada que desplegar",
            TaskTreeRenderer.RowTarget.EXPAND in scanAll(plain, painted(plain, 0)) { renderer.targetAt(plain, it) },
        )

        val deep = treeWith(long)
        renderer.hoveredRow = 0
        assertTrue(
            "con cuerpo escondido tiene que haber donde pulsar",
            TaskTreeRenderer.RowTarget.EXPAND in scanAll(deep, painted(deep, 0)) { renderer.targetAt(deep, it) },
        )

        renderer.hoveredRow = -1
        expand(deep, long)
        assertTrue(
            "desplegada, el boton de plegar se ve sin el raton encima",
            TaskTreeRenderer.RowTarget.EXPAND in scanAll(deep, painted(deep, 0)) { renderer.targetAt(deep, it) },
        )
    }

    /** El hueco del botón se reserva antes de saber si se usa: los tres iconos miden igual. */
    @Test
    fun `los controles de la fila ocupan todos lo mismo`() {
        assertEquals(EmptyIcon.ICON_16.iconWidth, AllIcons.General.ArrowDown.iconWidth)
        assertEquals(EmptyIcon.ICON_16.iconWidth, AllIcons.General.ArrowUp.iconWidth)
    }

    // -------------------------------------------------------- imagenes de la tarjeta

    /**
     * Desplegar enseña también las capturas, que es lo que el contador «1 img» sólo
     * podía anunciar. Se mide por la altura y no por píxeles concretos: lo que importa
     * es que la imagen **ocupa sitio** en la tarjeta.
     */
    @Test
    fun `la tarjeta desplegada pinta sus imagenes`() {
        val task = task("Comprar pan\n![](tasklane:$SHA)")
        renderer.images = previewsOf(AttachmentId(SHA) to square(90))
        val tree = treeWith(task)

        val folded = cardHeight(tree, 0)
        expand(tree, task)

        assertTrue("la imagen tiene que crecer la tarjeta", cardHeight(tree, 0) >= folded + 90)
    }

    /**
     * Plegada no. La tarjeta mide tres o cuatro líneas para que quepan muchas a la
     * vista; una captura dentro dejaría dos tareas por pantalla.
     */
    @Test
    fun `plegada la tarjeta no pinta imagenes`() {
        val withImage = task("Comprar pan\n![](tasklane:$SHA)")
        renderer.images = previewsOf(AttachmentId(SHA) to square(90))

        val tree = treeWith(withImage)
        val plain = treeWith(task("Comprar pan"))

        assertEquals(cardHeight(plain, 0), cardHeight(tree, 0))
    }

    /** Una captura encogida no se lee: tiene que poder ampliarse de un clic. */
    @Test
    fun `la imagen de la tarjeta se amplia de un clic`() {
        val task = task("Comprar pan\n![](tasklane:$SHA)")
        renderer.images = previewsOf(AttachmentId(SHA) to square(90))
        val tree = treeWith(task)
        expand(tree, task)

        val hit = scan(tree, painted(tree, 0)) {
            renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Image
        }

        assertEquals(AttachmentId(SHA), hit?.id)
    }

    /** Un marcador de carga no lleva a ningún sitio, así que ahí no hay nada que pulsar. */
    @Test
    fun `una imagen que no esta no se puede ampliar`() {
        val task = task("Comprar pan\n![](tasklane:$SHA)")
        renderer.images = previewsOf()
        val tree = treeWith(task)
        expand(tree, task)

        assertTrue(
            scanAll(tree, painted(tree, 0)) {
                renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Image
            }.isEmpty(),
        )
    }

    /** Una tarea con capturas esconde algo aunque su texto quepa entero. */
    @Test
    fun `una tarea con imagenes se puede desplegar`() {
        val task = task("Comprar pan\n![](tasklane:$SHA)")
        renderer.images = previewsOf(AttachmentId(SHA) to square(90))
        val tree = treeWith(task)
        renderer.hoveredRow = 0

        assertTrue(
            TaskTreeRenderer.RowTarget.EXPAND in
                scanAll(tree, painted(tree, 0)) { renderer.targetAt(tree, it) },
        )
    }

    /**
     * **La imagen no se va porque la fila se haya medido a otro ancho** (2.6.0).
     *
     * Era el fallo de «a veces se ve y a veces no»: el árbol guarda el alto de cada
     * fila y el contenido se monta contra el ancho de cada momento, así que aparecer o
     * desaparecer la barra de desplazamiento dejaba la tarjeta pidiendo más alto del
     * que tenía, y la línea que sobraba —la vista previa— se caía entera. Quedaba una
     * tarjeta alta y vacía con los distintivos pegados al fondo. Ver [RowStack].
     */
    @Test
    fun `la imagen se ve aunque la fila se haya medido a otro ancho`() {
        val task = task("ver lo de los endpoints de configuracion de brand de company\n![](tasklane:$SHA)")
        renderer.images = previewsOf(AttachmentId(SHA) to square(90))
        val tree = treeWith(task)
        expand(tree, task)

        // Medida a 600 y pintada a 300: el título pide una línea más de las que se
        // midieron, y lo que sobraba era justo la captura.
        tree.setSize(MIN_WIDTH, 400)
        tree.doLayout()

        val hit = scan(tree, painted(tree, 0)) {
            renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Image
        }

        assertEquals(AttachmentId(SHA), hit?.id)
    }

    /**
     * **El contador de capturas se pulsa** (2.6.0): amplía las imágenes de la tarea sin
     * desplegar la tarjeta ni abrir el diálogo. Hasta la 2.5 ese icono caía en la
     * etiqueta de los enlaces y, sin enlaces, no hacía nada.
     */
    @Test
    fun `el contador de capturas amplia las imagenes de la tarea`() {
        val tree = treeWith(task("Comprar pan\n![](tasklane:$SHA)"))

        val hit = scan(tree, painted(tree, 0)) {
            renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Images
        }

        assertEquals(listOf(AttachmentId(SHA)), hit?.ids)
    }

    /**
     * Con enlaces **y** capturas, el mismo distintivo lleva a los dos sitios: el icono
     * de cadena y su número abren los enlaces, y el `1 img` de al lado, las capturas.
     */
    @Test
    fun `el indicador con enlaces y capturas lleva a los dos`() {
        val tree = treeWith(task("Migrar el indice https://ejemplo.com/guia\n![](tasklane:$SHA)"))
        val bounds = painted(tree, 0)

        val hits = scanAll(tree, bounds) { point ->
            renderer.hotspotAt(tree, point)?.takeIf { renderer.caretAt(tree, point) == null }
        }

        assertTrue(
            "los enlaces se siguen abriendo desde el icono",
            hits.filterIsInstance<TaskTreeRenderer.Hotspot.Links>().any { it.links.isNotEmpty() },
        )
        assertEquals(
            listOf(AttachmentId(SHA)),
            hits.filterIsInstance<TaskTreeRenderer.Hotspot.Images>().firstOrNull()?.ids,
        )
    }

    /** Sin capturas no hay nada que ampliar, y el indicador no finge que sí. */
    @Test
    fun `una tarea sin capturas no ofrece ampliar nada`() {
        val tree = treeWith(task("Migrar el indice https://ejemplo.com/guia"))

        assertTrue(
            scanAll(tree, painted(tree, 0)) {
                renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Images
            }.isEmpty(),
        )
    }

    /**
     * Las imágenes que se le dan por listas; el resto, ausentes. Sin esto haría falta
     * la `Application` del IDE, que es lo que este test evita.
     */
    private fun previewsOf(vararg ready: Pair<AttachmentId, BufferedImage>) = object : CardPreviews {
        private val images = ready.toMap()

        override fun stateOf(repo: RepoKey, id: AttachmentId): CardImageView.State =
            if (id in images) CardImageView.State.READY else CardImageView.State.MISSING

        override fun preview(repo: RepoKey, id: AttachmentId, maxWidth: Int, maxHeight: Int): BufferedImage? =
            images[id]

    }

    private fun square(side: Int) = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)

    // ------------------------------------------------------- seleccionar el texto

    /**
     * Que el texto de la tarjeta se pueda marcar con el ratón. Lo que se comprueba no
     * es en qué píxel cae cada letra —eso depende de la fuente— sino que barriendo la
     * tarjeta de izquierda a derecha el cursor recorre el título de punta a punta.
     */
    @Test
    fun `el raton recorre el texto de la tarjeta de principio a fin`() {
        val task = task("Comprar pan")
        val tree = treeWith(task)

        val carets = scanAll(tree, painted(tree, 0)) { renderer.caretAt(tree, it)?.pos }

        assertTrue("tiene que haber texto que marcar", carets.isNotEmpty())
        assertEquals("el titulo es la primera linea", setOf(0), carets.map { it.line }.toSet())
        assertEquals(0, carets.minOf { it.offset })
        assertEquals("Comprar pan".length, carets.maxOf { it.offset })
    }

    /**
     * Que el cursor caiga **donde caen las letras**, y no unos píxeles a un lado.
     *
     * Se cruza con el *hit testing* de los enlaces, que es la única referencia exacta
     * que hay a mano: la plataforma sabe decir en qué píxeles se pintó el fragmento
     * del enlace, así que al principio de ese tramo el cursor tiene que valer cero y
     * justo detrás, el largo del texto. Es lo que se rompería si el relleno que
     * `SimpleColoredComponent` pone por delante dejara de ser el que se cree.
     */
    @Test
    fun `el cursor de texto cae donde caen las letras`() {
        val tree = treeWith(task("https://ejemplo.com/a"))
        val bounds = painted(tree, 0)
        // La altura del título, no la del centro de la tarjeta: debajo está la línea
        // de distintivos, y su indicador contesta por todos los enlaces de la tarea.
        val y = bounds.y + (0 until bounds.height)
            .first { renderer.caretAt(tree, Point(bounds.x + bounds.width / 2, bounds.y + it)) != null }

        val xs = (0 until bounds.width)
            .filter { linksAt(tree, Point(bounds.x + it, y)).isNotEmpty() }
        assertTrue("sin enlace no hay con que cruzar", xs.isNotEmpty())
        val painted = renderer.cardText(tree, 0).first()

        assertEquals(0, renderer.caretAt(tree, Point(bounds.x + xs.min(), y))?.pos?.offset)
        assertEquals(painted.length, renderer.caretAt(tree, Point(bounds.x + xs.max() + 1, y))?.pos?.offset)
    }

    /** Fuera del texto no hay cursor: ni la línea de distintivos ni el hueco cuentan. */
    @Test
    fun `la cabecera de la fila no tiene texto que marcar`() {
        val tree = treeWith(task("Comprar pan"))
        val bounds = painted(tree, 0)

        assertNotNull(scan(tree, bounds) { renderer.caretAt(tree, it) })
        assertEquals(null, renderer.caretAt(tree, Point(bounds.x + 1, bounds.y - 100)))
    }

    /** Lo que se copia es lo que se marcó, contado sobre las líneas de la pantalla. */
    @Test
    fun `se copia el tramo marcado y nada mas`() {
        val task = task("Comprar pan\nen la panaderia")
        val tree = treeWith(task)

        renderer.selection = CardSelection(task.id, TextPos(0, 0), TextPos(0, 7))
        assertEquals("Comprar", CardTextSelection.selectedText(tree, renderer))

        // Cruzando líneas se copia con el salto de línea puesto.
        renderer.selection = CardSelection(task.id, TextPos(0, 8), TextPos(1, 2))
        assertEquals("pan\nen", CardTextSelection.selectedText(tree, renderer))

        // Un clic sin arrastrar no es una selección: no hay nada que copiar.
        renderer.selection = CardSelection(task.id, TextPos(0, 3), TextPos(0, 3))
        assertEquals(null, CardTextSelection.selectedText(tree, renderer))
    }

    /**
     * Los distintivos se ven **siempre**, aunque la fila se haya medido a otro ancho.
     *
     * El árbol mide el alto de cada fila una vez y lo guarda, mientras que el título se
     * envuelve contra el ancho de cada momento: estrechar la ventana parte el título en
     * una línea más de las que se midieron, y la que sobra por abajo es justo la de
     * prioridad, vencimiento y etiquetas. Desaparecían sin dejar rastro —había tarjetas
     * con ellas y tarjetas sin ellas— y nada explicaba la diferencia.
     *
     * La ventana ya no deja que las dos medidas se separen (`TasklanePanel`), pero el
     * sitio donde eso **no puede** doler es éste: la última línea se pega al fondo del
     * hueco que haya en vez de caerse fuera. Ver [com.tasklane.ui.toolwindow.RowStack].
     */
    @Test
    fun `los distintivos se ven aunque la fila se haya medido mas ancha`() {
        val task = task("ver lo de los endpoints de configuracion de brand de company\nGET /api/brand")
        val tree = treeWith(task)
        // Medida a 600 y pintada a 300, que es lo que pasaba al aparecer la barra de
        // desplazamiento: el alto sigue siendo el de dos líneas y el título ya pide tres.
        tree.setSize(300, 400)
        tree.doLayout()

        val hit = scan(tree, painted(tree, 0)) {
            renderer.hotspotAt(tree, it) as? TaskTreeRenderer.Hotspot.Priority
        }

        assertEquals(task.id, hit?.task?.id)
    }

    // ------------------------------------------------------ el ancho de la fila

    /**
     * Una palabra que no cabe ni partiéndola por espacios se **corta**, y la fila sigue
     * acabando donde acaba el hueco.
     *
     * Es el caso que descolocaba los botones. La tarjeta pasaba a medir lo que esa
     * palabra; el árbol pinta la fila hasta el borde visible, pero el *hit testing*
     * rehace la cuenta sobre lo que la fila dice que mide, así que los dos números
     * dejaban de ser el mismo y los botones se buscaban un escalón a la derecha de
     * donde se veían: pulsar el marcador plegaba la tarjeta.
     *
     * Con la tarea **dentro de un grupo**, que es donde de verdad aparecía: la sangría
     * de la fila es ancho que la tarjeta no tiene, y era justo lo que faltaba descontar.
     */
    @Test
    fun `una palabra sin cortes no descoloca los botones de la fila`() {
        val long = task("ajkhbasjgabsldgasdksbfknsabfkasnbdgkjhbasdkjbasdkjbaskdjb\nfasd\ngsa")
        val tree = treeWithGroup(long)
        renderer.hoveredRow = 1

        val bounds = tree.getRowBounds(1)

        assertTrue("la fila de un grupo va sangrada", bounds.x > 0)
        assertTrue(
            "la fila acaba en ${bounds.x + bounds.width} sobre un arbol de ${tree.width}",
            bounds.x + bounds.width <= tree.width,
        )
    }

    /**
     * Y con la sangría descontada: el tope de ancho se pone sobre lo que le queda a
     * **esa** fila, no sobre lo que se ve del árbol entero. Con distintivos de sobra
     * —que es cuando el tope entra en juego— la fila de un grupo se pasaba justo la
     * sangría, y los botones se iban con ella.
     */
    @Test
    fun `la tarjeta de un grupo tampoco se sale por la sangria`() {
        val tree = treeWithGroup(
            task(
                "Comprar pan",
                tags = listOf("planificacion", "pendiente", "compras", "semana", "casa", "cocina"),
            ),
        )

        val bounds = tree.getRowBounds(1)

        assertTrue(
            "la fila acaba en ${bounds.x + bounds.width} sobre un arbol de ${tree.width}",
            bounds.x + bounds.width <= tree.width,
        )
    }

    /**
     * La tarjeta no se sale del hueco visible por muchos distintivos que lleve.
     *
     * La línea de distintivos pide el ancho de todos los suyos aunque luego deje
     * fuera los que no caben, y el árbol crece hasta la fila más ancha que tenga. Con
     * la tool window estrecha eso daba por recortadas todas las tarjetas: la
     * plataforma sacaba al pasar el ratón su ventanita de «fila completa» fuera del
     * panel, y dentro iban los botones de la derecha — que se cerraba antes de que el
     * ratón llegara a ellos.
     */
    @Test
    fun `la tarjeta no se sale del ancho visible`() {
        val tree = treeWith(
            task(
                "Comprar pan",
                tags = listOf("planificacion", "pendiente", "compras", "semana", "casa", "cocina"),
            ),
        )

        val bounds = tree.getRowBounds(0)

        assertTrue(
            "la fila acaba en ${bounds.x + bounds.width} sobre un arbol de ${tree.width}",
            bounds.x + bounds.width <= tree.width,
        )
    }

    /**
     * El resalte se pinta partiendo los tramos y dándole fondo al de en medio, así que
     * lo que no puede pasar es que marcar cambie una sola letra de la fila.
     */
    @Test
    fun `marcar texto no cambia lo que se pinta`() {
        val task = task("Comprar pan")
        val tree = treeWith(task)
        renderer.selection = CardSelection(task.id, TextPos(0, 2), TextPos(0, 5))

        val node = tree.getPathForRow(0).lastPathComponent
        renderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false)

        assertEquals("Comprar pan", renderer.textRenderer.getCharSequence(false).toString())
    }

    /**
     * Que la lista **se pinte**, con todo encendido a la vez: la tarjeta desplegada
     * —que crea líneas y vistas previas nuevas dentro del propio pintado—, una imagen,
     * un tramo de texto marcado —que parte los tramos y les pone fondo— y el ratón
     * encima.
     *
     * Es el test que faltaba cuando la fila se quedó en blanco al poner el nombre
     * accesible de una pestaña: ninguno montaba de verdad el pintado, así que una
     * excepción ahí dentro no la veía nadie hasta abrir el IDE.
     */
    @Test
    fun `la lista se pinta con la tarjeta desplegada y texto marcado`() {
        val task = task(
            "Comprar pan https://ejemplo.com/a\nen la panaderia\n![](tasklane:$SHA)\ny pagar en efectivo",
            priority = TasklaneConfig.HIGH,
        )
        renderer.images = previewsOf(AttachmentId(SHA) to square(60))
        val tree = treeWith(task)
        expand(tree, task)
        renderer.selection = CardSelection(task.id, TextPos(0, 2), TextPos(2, 3))
        renderer.hoveredRow = 0

        val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            tree.paint(graphics)
        } finally {
            graphics.dispose()
        }

        // Y que haya pintado algo: un lienzo en blanco pasaría el test sin haber
        // llegado nunca al renderer, que es justo lo que se quiere comprobar.
        val painted = (0 until image.height).any { y -> (0 until image.width).any { x -> image.getRGB(x, y) != 0 } }
        assertTrue("el arbol tiene que haber pintado la tarjeta", painted)
    }

    /** La altura de la tarjeta según el propio renderer, sin depender de la caché del árbol. */
    private fun cardHeight(tree: JTree, row: Int): Int {
        val node = tree.getPathForRow(row).lastPathComponent
        return renderer.getTreeCellRendererComponent(tree, node, false, false, true, row, false)
            .preferredSize.height
    }

    /**
     * El rectángulo en el que se **pinta** la fila, que es más ancho que el que
     * `JTree` calcula para ella: el árbol estira el renderer hasta el borde visible.
     * Barrer el estrecho era barrer sólo la parte con letras, y por eso estos tests
     * pasaban mientras la mitad derecha de la tarjeta no respondía a nada.
     */
    private fun painted(tree: JTree, row: Int) = paintedRowBounds(tree, row)!!

    /**
     * Desplegar una tarjeta **como lo hace la ventana**: cambia lo que mide la fila, y
     * el árbol guarda esas medidas, así que hay que tirárselas. Sin esto la fila se
     * queda con el alto de la tarjeta plegada y lo que se mide encima es un estado que
     * en el plugin no existe — ver `TasklanePanel.toggleExpanded`.
     */
    private fun expand(tree: JTree, task: Task) {
        renderer.toggleExpanded(task)
        remeasure(tree)
    }

    /**
     * Un árbol con una cabecera de grupo y la tarea **dentro**, que es como se ve en la
     * ventana en cuanto el estado agrupa por algo. Importa porque una fila de un grupo
     * va sangrada, y la sangría es ancho que la tarjeta no tiene.
     */
    private fun treeWithGroup(task: Task, width: Int = 420): JTree {
        val root = CheckedTreeNode("root")
        val group = GroupNode(GroupKey.OfDate(DateGroup.Today), 1)
        group.add(TaskNode(task, false))
        root.add(group)
        val tree = JTree(DefaultTreeModel(root))
        tree.cellRenderer = renderer
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.rowHeight = 0
        tree.setSize(width, 400)
        tree.doLayout()
        tree.expandRow(0)
        remeasure(tree)
        return tree
    }

    /** El primer punto de la fila donde [probe] contesta algo. Rejilla de 2 px. */
    private fun <T : Any> scan(tree: JTree, bounds: Rectangle, probe: (Point) -> T?): T? =
        scanAll(tree, bounds, probe).firstOrNull()

    /** La primera columna de la fila en la que [probe] dice que sí. */
    private fun firstColumn(tree: JTree, bounds: Rectangle, probe: (Point) -> Boolean): Int? =
        (0 until bounds.width).firstOrNull { x ->
            (0 until bounds.height step 2).any { y -> probe(Point(bounds.x + x, bounds.y + y)) }
        }

    private fun <T : Any> scanAll(tree: JTree, bounds: Rectangle, probe: (Point) -> T?): List<T> =
        (0 until bounds.height step 2).flatMap { y ->
            (0 until bounds.width step 2).mapNotNull { x -> probe(Point(bounds.x + x, bounds.y + y)) }
        }

    companion object {
        /**
         * Holgura de la comprobación del punto de color: el icono mide ocho píxeles y
         * detrás va el hueco hasta el texto, así que sin él la zona del distintivo
         * empezaría bastante más de esto a la derecha.
         */
        const val ICON_SLACK = 4

        /** `TasklanePanel.MIN_WIDTH`: lo más estrecha que se deja poner la ventana. */
        const val MIN_WIDTH = 300

        /** Lo que mide, como mucho, un distintivo que es sólo su icono y su relleno. */
        const val ICON_ONLY = 24

        /** Un SHA-256 de mentira, con la longitud exacta que exige `ImageRefParser`. */
        const val SHA = "abc123def456abc123def456abc123def456abc123def456abc123def4561234"

        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
