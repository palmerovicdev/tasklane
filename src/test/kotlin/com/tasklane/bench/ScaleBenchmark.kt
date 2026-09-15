package com.tasklane.bench

import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.CheckedTreeNode
import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.DateGrouper
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.domain.query.QueryParser
import com.tasklane.search.LinearScanIndex
import com.tasklane.search.SearchScope
import com.tasklane.service.SearchResults
import com.tasklane.ui.toolwindow.GroupNode
import com.tasklane.ui.toolwindow.TaskNode
import com.tasklane.ui.toolwindow.TaskTreeRenderer
import com.tasklane.ui.toolwindow.VisibleTasks
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.JTree
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * El banco de la Fase 0 (`docs/plan-escala.md` §0.3). **Convierte cada fila de §1.7 en
 * una cifra medida** en lugar de una estimación de lectura del código.
 *
 * Se ejecuta a mano y en el job nocturno, nunca en el build normal: un corpus de 100k
 * tarda minutos y reserva gigas, y eso no puede colgar de un `./gradlew test`.
 *
 *     ./gradlew test --tests '*ScaleBenchmark' -PbenchN=10000   -PtestHeap=2g
 *     ./gradlew test --tests '*ScaleBenchmark' -PbenchN=100000  -PtestHeap=6g
 *     ./gradlew test --tests '*ScaleBenchmark' -PbenchN=1000000 -PtestHeap=24g
 *
 * **Se salta con `Assume` y no con `@Ignore`**, aunque el plan dijera `@Ignore`. Son lo
 * mismo para el build normal —sin `-PbenchN` esto no corre— pero `@Ignore` obliga a
 * **editar el fichero** para lanzarlo, y un instrumento que hay que modificar para
 * usarlo es un instrumento que se queda sin usar. Con esto el job nocturno del §6.3 es
 * una línea de Gradle y nada más.
 *
 * **Qué mide y qué no.** Todo lo que hay aquí son las piezas **puras** del camino
 * real: el códec, el reducer, el índice, el recuento de la ventana y el árbol de
 * Swing con su renderer de verdad. Lo que no se puede medir sin arrancar un IDE
 * —`TaskService` y `SearchService` son `@Service(PROJECT)`— se reproduce llamando a
 * exactamente lo que ellos llaman, y los sitios donde eso ocurre están señalados uno
 * a uno. No se mide el IO de la VFS porque el plugin no la usa.
 */
class ScaleBenchmark {

    private val config = TasklaneConfig.DEFAULT
    private val repo = RepoKey.ROOT
    private val reducer = TaskReducer()

    @Before
    fun soloBajoPeticion() {
        Assume.assumeTrue(
            "Banco: se lanza con -PbenchN=<tareas>. Ver el KDoc de ScaleBenchmark.",
            System.getProperty("tasklane.bench.n") != null,
        )
    }

    // ------------------------------------------------------------------ escenarios

    /**
     * **§1.2 — lo que revienta primero.** `TaskFileStore.readFile` construye el DOM
     * entero antes de que exista una sola `Task`, y encima `reduceScoped` pasa las
     * regex de `LinkExtractor` e `ImageRefParser` sobre todo el texto.
     *
     * El plan predice `OutOfMemoryError` a ~300.000 tareas con el heap de 2 GB que
     * trae IntelliJ de fábrica. Si esto muere, **ése es el dato**: se anota el N al
     * que murió y con cuánto heap.
     */
    @Test
    fun `carga en frio`() {
        val file = corpusFile()
        val result = Bench.measure("carga en frio: JDOM + decode + normalize", runs = 3, warmup = 1) {
            val decoded = TasksCodec.decode(JDOMUtil.load(file), repo)
            // Exactamente lo que hace TaskService.load -> apply(Loaded) -> reduceScoped.
            val snapshot = reducer.reduce(base(), TaskCommand.Loaded(repo, decoded.tasks))
            assertTrue(snapshot.activeTasks.size == n)
        }
        record(result)
    }

    /**
     * **§1.3 — el volcado.** `writeElement` devuelve un `String` que luego se pasa a
     * `ByteArray`: el pico son las dos representaciones vivas a la vez. Y ocurre
     * **cada 500 ms de tecleo**, porque el debounce reescribe el fichero entero
     * aunque haya cambiado una letra.
     */
    @Test
    fun `volcado completo`() {
        val tasks = corpus()
        val result = Bench.measure("volcado: encode + writeElement + toByteArray", runs = 3, warmup = 1) {
            val bytes = JDOMUtil.writeElement(TasksCodec.encode(repo, tasks)).toByteArray(StandardCharsets.UTF_8)
            assertTrue(bytes.isNotEmpty())
        }
        record(result)
    }

    /**
     * **§1.5 — seis pasadas O(n) por pulsación de tecla.** Se mide un comando
     * completo tal y como lo ejecuta `TaskService.apply`: el reducer, más las dos
     * pasadas de `reportRemapped` y la comparación de `markDirty`.
     *
     * Las tres líneas de contabilidad están copiadas de `TaskService` a propósito y no
     * extraídas: lo que se mide tiene que ser lo que el servicio hace hoy, no una
     * versión limpia de ello. La Fase 1 las hace desaparecer.
     */
    @Test
    fun `un comando`() {
        val before = loaded()
        val victim = before.activeTasks[before.activeTasks.size / 2]

        val result = Bench.measure("un comando: reduce + reportRemapped + markDirty", runs = 20, warmup = 5) {
            val after = reducer.reduce(before, TaskCommand.ToggleComplete(repo, victim.id))

            // --- TaskService.reportRemapped: flatten() del snapshot DOS veces -------
            val was = TaskReducer.orphans(before.tasksByRepo.values.flatten()).map { it.id }.toSet()
            val now = TaskReducer.orphans(after.tasksByRepo.values.flatten())
            now.filterNot { it.id in was }

            // --- TaskService.markDirty: comparación estructural de la lista --------
            after.tasksByRepo.keys.filter { key -> after.tasksOf(key) != before.tasksOf(key) }
        }
        record(result)
    }

    /**
     * **§1.5 y §1.7 — una tecla en el buscador.** `LinearScanIndex.setCorpus` rehace
     * un `HashSet` con **todos** los ids en cada snapshot, y después la búsqueda
     * recorre el corpus entero. Las dos cosas pasan por cada tecla.
     */
    @Test
    fun `tecla en el buscador`() {
        val snapshot = loaded()
        val index = LinearScanIndex()
        // Primera pasada aparte: normalizar el corpus se paga una vez, y meterlo en la
        // medida contaría como coste por tecla algo que no lo es.
        index.setCorpus(snapshot)
        index.search(QueryParser.parse("token"), SearchScope.Repo(repo))

        var i = 0
        val queries = listOf("login", "token ca", "#api", "state:doing token", "has:image cache")
        val result = Bench.measure("tecla en el buscador: setCorpus + parse + search", runs = 20, warmup = 5) {
            index.setCorpus(snapshot)
            index.search(QueryParser.parse(queries[i++ % queries.size]), SearchScope.Repo(repo))
        }
        record(result)
    }

    /**
     * **§1.7 — cambiar de pestaña.** Filtrar, contar, ordenar y agrupar: lo que
     * `TasklanePanel` hace fuera del EDT en cada repintado, para las tres pestañas.
     *
     * El bloque de agrupación reproduce `TasklanePanel.buildSections`, que es privado.
     * Se mantiene al lado del original a propósito: si aquél cambia de orden, esto
     * mide otra cosa y hay que tocarlo.
     */
    @Test
    fun `repintado - contar, filtrar, ordenar y agrupar`() {
        val snapshot = loaded()
        val found = SearchResults.NONE
        val now = Instant.now()

        val result = Bench.measure("repintado: countsByState + of + sort + group", runs = 10, warmup = 3) {
            VisibleTasks.countsByState(snapshot, found, TaskFilter.ALL, now)
            for (state in config.states) {
                val mine = VisibleTasks.of(snapshot, found, TaskFilter.ALL, state.id, now)
                sections(mine, state.grouping, state.anchor)
            }
        }
        record(result)
    }

    /**
     * **§1.4 — la trampa del EDT.** Con `rowHeight = 0`, `JTree` usa
     * `VariableHeightLayoutCache`, que **mide cada fila invocando al renderer**.
     * Construir los nodos, recargar y desplegar los grupos es una invocación del
     * renderer por tarea, en el hilo de UI.
     *
     * Éste es el escenario que el plan llama «independiente del almacén»: aunque los
     * datos vinieran de una base de datos perfecta, esto mata el IDE. Por eso la
     * Fase 2 va antes que la 3.
     *
     * `JTree` y no `CheckboxTree` por lo mismo que en `TaskTreeRendererTest`: el
     * constructor de la de la plataforma instala su speed search y eso sí necesita
     * `Application`. Lo que se mide —crear nodos, recargar y **medir cada fila**— es
     * idéntico.
     */
    @Test
    fun `render en el EDT`() {
        val snapshot = loaded()
        val state = config.states.first()
        val mine = VisibleTasks.of(snapshot, SearchResults.NONE, TaskFilter.ALL, state.id, Instant.now())
        val sections = sections(mine, Grouping.BY_DATE, state.anchor)

        val renderer = TaskTreeRenderer().apply {
            config = this@ScaleBenchmark.config
            // Sin IDE no hay DateFormatUtil; para medir, la fecha es texto y nada más.
            formatDate = { "15/09/2026" }
        }
        val root = CheckedTreeNode("root")
        val tree = Bench.edt {
            JTree(DefaultTreeModel(root)).apply {
                cellRenderer = renderer
                isRootVisible = false
                showsRootHandles = false
                rowHeight = 0
                setSize(400, 800)
                doLayout()
            }
        }

        val result = Bench.measureOnEdt("render en el EDT: nodos + reload + medir filas", runs = 3, warmup = 1) {
            root.removeAllChildren()
            for ((key, tasks) in sections) {
                val group = GroupNode(key, tasks.size)
                tasks.forEach { group.add(TaskNode(it, false)) }
                root.add(group)
            }
            (tree.model as DefaultTreeModel).reload()
            // `expandGroups()`: desplegar es lo que obliga a MEDIR cada fila, y medir
            // es lo que invoca al renderer una vez por tarea. Sin esto el árbol sólo
            // mediría las cabeceras y el banco diría que todo va bien.
            //
            // Por RUTA y no por fila, igual que el panel. `expandRow(i)` parece
            // equivalente y no lo es: al desplegar el grupo 0 sus miles de hijos se
            // meten en la numeración, así que la fila 1 ya no es el grupo 1 sino una
            // tarea, y el resto del bucle no despliega nada. Con ese fallo este banco
            // marcaba 51 ms a 100k —sublineal, imposible— en vez de lo que cuesta.
            for (node in root.children().toList().filterIsInstance<GroupNode>()) {
                tree.expandPath(TreePath(node.path))
            }
            (tree.ui as BasicTreeUI).let { it.leftChildIndent = it.leftChildIndent }
            assertTrue(tree.rowCount > 0)
            tree.preferredSize
        }
        record(result)
    }

    /**
     * **§1.6 — los adjuntos.** `AttachmentStore.list` hace `Files.list(dir).toList()`
     * más un `readAttributes` por fichero, sobre un **único directorio plano**. Con
     * diez millones de blobs eso es lo que hace que `AttachmentGcActivity` no termine.
     *
     * Escribir los blobs cuesta mucho más que medirlos, así que este escenario lleva
     * su propio N —`-PbenchBlobs`— y por defecto se queda corto.
     */
    @Test
    fun `listar el directorio de adjuntos`() {
        val blobs = System.getProperty("tasklane.bench.blobs")?.toIntOrNull() ?: 2_000
        val dir = Files.createTempDirectory("tasklane-blobs")
        try {
            val bytes = SyntheticBlobs.writeAll(dir, blobs)
            println("Escritos $blobs blobs, ${bytes / (1024 * 1024)} MB (%.0f KB de media)".format(bytes / 1024.0 / blobs))

            val result = Bench.measure("adjuntos: Files.list + readAttributes de $blobs", runs = 5, warmup = 1) {
                Files.list(dir).use { paths ->
                    paths.toList().map { path ->
                        Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                    }
                }
            }
            record(result)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * **§2.6 — el presupuesto de memoria.** Cuánto retiene el modelo vivo: las `Task`
     * con sus siete `lazy` ya forzados (que es lo que pasa en cuanto la lista se
     * pinta una vez) más los documentos del índice.
     *
     * El plan estima 5–6 KB por tarea más 2 KB del `SearchDocument`. Esta medida es la
     * que dice si acertó.
     */
    @Test
    fun `memoria retenida por el modelo vivo`() {
        val baseline = Bench.heapMegabytes()

        // 1. Recién cargado. Es lo que hay en el heap durante la apertura del
        //    proyecto, ANTES de que la ventana pinte nada: los siete `lazy` de `Task`
        //    siguen sin forzarse, así que ni el título ni los bloques del detalle
        //    existen todavía.
        val snapshot = loaded()
        val afterLoad = Bench.heapMegabytes()

        // 2. Después del primer repintado. Pintar una fila pide título, descripción,
        //    enlaces del título y bloques del detalle, así que en cuanto la lista se
        //    ve una vez los siete `lazy` de cada tarea visible quedan materializados
        //    —y con el desplazamiento, los de todas—.
        //
        //    La diferencia entre 1 y 2 no es un detalle contable: explica por qué el
        //    proyecto ABRE con 200.000 tareas y se muere al mirarlas.
        var chars = 0L
        for (task in snapshot.activeTasks) {
            chars += task.title.length + task.description.length + task.titleLinks.size + task.detailBlocks.size
        }
        val afterLazy = Bench.heapMegabytes()

        // 3. Y el corpus del buscador, que guarda el cuerpo normalizado OTRA VEZ.
        val index = LinearScanIndex()
        index.setCorpus(snapshot)
        index.search(QueryParser.parse("token"), SearchScope.Repo(repo))
        val afterIndex = Bench.heapMegabytes()

        val loadedMb = afterLoad - baseline
        val lazyMb = afterLazy - afterLoad
        val docsMb = afterIndex - afterLazy
        val total = loadedMb + lazyMb + docsMb
        println(
            """
            |
            |┌─ Memoria retenida con n = $n
            |│  Task recién cargada     %9.1f MB   (%6.0f B por tarea)
            |│  + los siete lazy        %9.1f MB   (%6.0f B por tarea)   <- los fuerza el primer repintado
            |│  + SearchDocument        %9.1f MB   (%6.0f B por tarea)
            |│  TOTAL                   %9.1f MB   (%6.0f B por tarea)
            |└─
            """.trimMargin().format(
                loadedMb, loadedMb * 1024 * 1024 / n,
                lazyMb, lazyMb * 1024 * 1024 / n,
                docsMb, docsMb * 1024 * 1024 / n,
                total, total * 1024 * 1024 / n,
            ),
        )
        // Referenciado hasta el final para que el GC de la última medida no se lo lleve.
        assertTrue(snapshot.activeTasks.isNotEmpty() && chars > 0)
    }

    // ------------------------------------------------------------------ andamiaje

    /** Un snapshot vacío con la configuración por defecto: el punto de partida real. */
    private fun base() = TasklaneSnapshot.EMPTY.copy(config = config)

    /** El corpus ya dentro de un snapshot, como lo deja `TaskCommand.Loaded`. */
    private fun loaded(): TasklaneSnapshot = reducer.reduce(base(), TaskCommand.Loaded(repo, corpus()))

    /**
     * `TasklanePanel.buildSections`, reproducido. Ver la nota del escenario de
     * repintado sobre por qué está duplicado en vez de extraído.
     */
    private fun sections(
        tasks: List<Task>,
        grouping: Grouping,
        anchor: com.tasklane.domain.model.DateAnchor,
    ): List<Pair<GroupKey, List<Task>>> {
        val natural = compareByDescending<Task> { it.bookmarked }
            .thenByDescending { config.priorityOrDefault(it.priorityId).order }
            .thenByDescending { (DateGrouper.anchorOf(it, anchor) ?: it.updatedAt).toEpochMilli() }

        if (grouping == Grouping.NONE) {
            return listOf(GroupKey.OfTag(null) to tasks.sortedWith(natural))
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        return tasks
            .groupBy { DateGrouper.groupOf(DateGrouper.anchorOf(it, anchor), today, zone, firstDay) }
            .toSortedMap()
            .map { (group, inGroup) -> GroupKey.OfDate(group) as GroupKey to inGroup.sortedWith(natural) }
    }

    private fun record(result: Bench.Result) {
        results += result
        println("  $result")
    }

    companion object {
        /** El tamaño del corpus. `-PbenchN=100000`. */
        val n: Int = System.getProperty("tasklane.bench.n")?.toIntOrNull() ?: 10_000

        private val results = mutableListOf<Bench.Result>()

        private var cached: List<Task>? = null
        private var cachedFile: Path? = null

        /**
         * El corpus, generado una vez para toda la clase. Regenerarlo por escenario
         * costaría más que todo lo que se mide junto.
         */
        fun corpus(): List<Task> = cached ?: SyntheticCorpus.tasks(n).also { cached = it }

        fun corpusFile(): Path = cachedFile ?: run {
            val dir = Files.createTempDirectory("tasklane-bench")
            val file = dir.resolve("tasks.xml")
            val bytes = SyntheticCorpus.writeTasksXml(file, n)
            println("Corpus de $n tareas en disco: ${bytes / (1024 * 1024)} MB")
            cachedFile = file
            file
        }

        @BeforeClass
        @JvmStatic
        fun announce() {
            println("\n═══ Banco de escala · n = $n · heap máx = ${Runtime.getRuntime().maxMemory() / (1 shl 20)} MB ═══")
        }

        @AfterClass
        @JvmStatic
        fun summary() {
            Bench.report("Resumen · n = $n", results)
            cachedFile?.parent?.toFile()?.deleteRecursively()
            cached = null
            cachedFile = null
        }
    }
}
