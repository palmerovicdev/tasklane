package com.tasklane.bench

import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.CheckedTreeNode
import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.domain.model.StateId
import com.tasklane.domain.query.QueryParser
import com.tasklane.paging.GroupOutline
import com.tasklane.paging.InMemoryPager
import com.tasklane.paging.PageQuery
import com.tasklane.paging.TaskPager
import com.tasklane.search.LinearScanIndex
import com.tasklane.search.SearchScope
import com.tasklane.service.SearchResults
import com.tasklane.ui.toolwindow.GroupNode
import com.tasklane.ui.toolwindow.ListSync
import com.tasklane.ui.toolwindow.MoreNode
import com.tasklane.ui.toolwindow.TaskNode
import com.tasklane.ui.toolwindow.TaskTreeModel
import com.tasklane.ui.toolwindow.TaskTreeRenderer
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
import javax.swing.JTree
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
     * **§1.5 — el comando completo**, tal y como lo ejecuta hoy `TaskService.apply`:
     * `reducer.plan` y el consumo de su recibo.
     *
     * Antes de la Fase 1 esto eran tres cosas: el reducer, más dos `flatten()` del
     * snapshot entero para averiguar qué tareas se habían aparcado, más una
     * comparación estructural de las listas de todos los repositorios. Ahora el
     * reducer lo dice al devolver.
     */
    @Test
    fun `un comando`() {
        val before = loaded()
        val victim = before.activeTasks[before.activeTasks.size / 2]

        val result = Bench.measure("un comando: plan + consumir el recibo", runs = 20, warmup = 5) {
            val reduction = reducer.plan(before, TaskCommand.ToggleComplete(repo, victim.id))
            assertTrue(reduction.dirty.size == 1 && reduction.remapped.isEmpty())
        }
        record(result)
    }

    /**
     * **La puerta de la Fase 1, tal y como está escrita**: «una edición de tarea por
     * debajo de 16 ms de EDT».
     *
     * Y una edición no es un comando: `TasklanePanel.editSelected` manda **seis**
     * seguidos al aceptar el diálogo —cuerpo, estado, prioridad, etiquetas,
     * vencimiento y anclas—, todos desde el EDT. Medir uno solo y dar la puerta por
     * cerrada sería medir la sexta parte del problema.
     */
    @Test
    fun `editar una tarea`() {
        val start = loaded()
        val victim = start.activeTasks[start.activeTasks.size / 2]
        val otroEstado = config.states.first { it.id != victim.stateId }.id
        val otraPrioridad = config.priorities.first { it.id != victim.priorityId }.id

        var flip = false
        // Los dos caminos, medidos uno al lado del otro: el que había hasta la Fase 1
        // y el que hay ahora. Es la única forma de que la cifra signifique algo.
        val seis = Bench.measureOnEdt("editar · seis comandos (como hasta la Fase 1)", runs = 10, warmup = 3) {
            flip = !flip
            // Se alterna para que ningún comando se salga por «ya estaba así»: una
            // edición que no cambia nada es barata por razones que no queremos medir.
            var snapshot = start
            for (command in listOf(
                TaskCommand.UpdateBody(repo, victim.id, if (flip) "cuerpo nuevo" else "otro cuerpo"),
                TaskCommand.ChangeState(repo, victim.id, if (flip) otroEstado else victim.stateId),
                TaskCommand.ChangePriority(repo, victim.id, if (flip) otraPrioridad else victim.priorityId),
                TaskCommand.SetTags(repo, victim.id, if (flip) listOf("uno") else listOf("dos")),
                TaskCommand.SetDueDate(repo, victim.id, if (flip) SyntheticCorpus.REFERENCE_NOW else null),
                TaskCommand.SetAnchors(repo, victim.id, emptyList()),
            )) {
                snapshot = reducer.plan(snapshot, command).snapshot
            }
            assertTrue(snapshot !== start)
        }
        record(seis)

        val uno = Bench.measureOnEdt("editar · un UpdateTask (lo que hace el diálogo)", runs = 10, warmup = 3) {
            flip = !flip
            val reduction = reducer.plan(
                start,
                TaskCommand.UpdateTask(
                    repo = repo,
                    id = victim.id,
                    body = if (flip) "cuerpo nuevo" else "otro cuerpo",
                    stateId = if (flip) otroEstado else victim.stateId,
                    priorityId = if (flip) otraPrioridad else victim.priorityId,
                    tags = if (flip) listOf("uno") else listOf("dos"),
                    dueDate = java.util.Optional.ofNullable(
                        if (flip) SyntheticCorpus.REFERENCE_NOW else null,
                    ),
                    anchors = emptyList(),
                ),
            )
            assertTrue(reduction.snapshot !== start)
        }
        record(uno)
    }

    /**
     * El desglose del comando, pieza a pieza. El §1.5 lista seis sitios O(n) pero no
     * dice cuál pesa, y arreglar el que no era es la forma más común de gastar una
     * semana sin mover una cifra. Aquí se ve cuál era: el `reduce`.
     *
     * Las dos últimas líneas miden lo que el §1.5 llamaba «escaneo lineal» y
     * «`maxOfOrNull` sobre todo el repo». Desde la Fase 1 son búsquedas en un mapa y
     * por eso su cifra deja de crecer con N — que es justamente lo que hay que poder
     * comprobar.
     */
    @Test
    fun `desglose de un comando`() {
        val before = loaded()
        val victim = before.activeTasks[before.activeTasks.size / 2]
        val command = TaskCommand.ToggleComplete(repo, victim.id)

        record(Bench.measure("  desglose · plan (reducer entero)", runs = 20, warmup = 5) {
            reducer.plan(before, command)
        })
        // Lo que `TaskService.apply` hace con el recibo: descontar los repositorios en
        // solo lectura y mirar si hay algo que anunciar. Antes esto eran dos `flatten()`
        // del snapshot entero más un `equals` de listas.
        val readOnly = emptySet<com.tasklane.domain.model.RepoKey>()
        record(
            Bench.measure("  desglose · consumir el recibo", runs = 20, warmup = 5) {
                val reduction = reducer.plan(before, command)
                assertTrue(reduction.dirty.none { it in readOnly } && reduction.remapped.isEmpty())
            },
        )
        // El índice por id se construye la primera vez que alguien pregunta, así que se
        // fuerza fuera de la medida: lo que interesa es cuánto cuesta preguntar, no
        // cuánto costó construirlo.
        before.task(victim.id)
        record(
            Bench.measure("  desglose · snapshot.task(id)", runs = 20, warmup = 5) {
                assertTrue(before.task(victim.id) != null)
            },
        )
        record(
            Bench.measure("  desglose · snapshot.nextOrder", runs = 20, warmup = 5) {
                assertTrue(before.nextOrder(repo) > 0)
            },
        )
        record(
            Bench.measure("  desglose · crear una tarea", runs = 20, warmup = 5) {
                reducer.plan(before, TaskCommand.Create(repo, "una tarea nueva"))
            },
        )
    }

    /**
     * Y el desglose de la tecla. `setCorpus` recorre el corpus entero en cada snapshot
     * (§1.5) y `search` lo recorre otra vez; saber cuál de los dos manda decide si la
     * Fase 1 toca el índice o no.
     */
    @Test
    fun `desglose de una tecla`() {
        val snapshot = loaded()
        val index = LinearScanIndex()
        index.setCorpus(snapshot)
        index.search(QueryParser.parse("token"), SearchScope.Repo(repo))

        record(Bench.measure("  desglose · setCorpus", runs = 20, warmup = 5) { index.setCorpus(snapshot) })
        record(
            Bench.measure("  desglose · search (texto libre)", runs = 20, warmup = 5) {
                index.search(QueryParser.parse("token cache"), SearchScope.Repo(repo))
            },
        )
        record(
            Bench.measure("  desglose · search (solo operadores)", runs = 20, warmup = 5) {
                index.search(QueryParser.parse("is:done #api"), SearchScope.Repo(repo))
            },
        )
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
     * `TasklanePanel` pide fuera del EDT en cada repintado.
     *
     * Desde la Fase 2 esto **es** código de producción: construir el pager y
     * calentarle el contorno y los contadores es exactamente lo que hace el panel en
     * su corrutina. Antes había aquí una copia de `buildSections` que podía derivar
     * del original sin que nada avisara.
     */
    @Test
    fun `repintado - contar, filtrar, ordenar y agrupar`() {
        val snapshot = loaded().copy(config = board)
        val now = Instant.now()

        val result = Bench.measure("repintado: pager (contar + filtrar + ordenar + agrupar)", runs = 10, warmup = 3) {
            val pager = InMemoryPager(snapshot, SearchResults.NONE, TaskFilter.ALL, now)
            pager.counts()
            for (state in board.states) pager.outline(state.id)
        }
        record(result)
    }

    /**
     * **§1.4 — la trampa del EDT, y la puerta de la Fase 2.** Con `rowHeight = 0`,
     * `JTree` usa `VariableHeightLayoutCache`, que **mide cada fila invocando al
     * renderer**: ~0,064 ms cada una.
     *
     * Los dos caminos, uno al lado del otro y en la misma ejecución, que es la única
     * forma de que la comparación signifique algo (§1-bis.5):
     *
     * - **Hasta la Fase 2**: un nodo por tarea, `reload()` y desplegar todos los
     *   grupos. Una invocación del renderer por tarea, en el hilo de interfaz.
     * - **Desde la Fase 2**: las cabeceras salen del agregado y sólo los grupos que
     *   caben en el presupuesto reciben su **primera página**. El árbol no llega a
     *   contener el corpus, así que la cifra deja de crecer con N — que es justamente
     *   lo que hay que poder comprobar.
     *
     * Cada ejecución parte de un árbol nuevo a propósito: lo que se mide es el
     * **primer** pintado, que es el único momento en el que sincronizar por
     * diferencias no ayuda porque no hay nada puesto que reaprovechar.
     *
     * `JTree` y no `CheckboxTree` por lo mismo que en `TaskTreeRendererTest`: el
     * constructor de la de la plataforma instala su speed search y eso sí necesita
     * `Application`. Lo que se mide —crear nodos y **medir cada fila**— es idéntico.
     */
    @Test
    fun `render en el EDT`() {
        val snapshot = loaded().copy(config = board)
        val state = TasklaneConfig.TODO
        val pager = pager(snapshot, state)
        val outline = pager.outline(state)

        val antes = Bench.measureOnEdt("render · el corpus entero + reload (hasta la Fase 2)", runs = 3, warmup = 1) {
            val tree = newTree()
            val root = tree.model.root as CheckedTreeNode
            for (group in outline) {
                val node = GroupNode(group.key, group.size)
                pager.all(PageQuery(state, group.key)).forEach { node.add(TaskNode(it, false)) }
                root.add(node)
            }
            (tree.model as DefaultTreeModel).reload()
            // Desplegar es lo que obliga a MEDIR cada fila, y medir es lo que invoca al
            // renderer una vez por tarea. Sin esto el árbol sólo mediría las cabeceras
            // y el banco diría que todo va bien.
            //
            // Por RUTA y no por fila. `expandRow(i)` parece equivalente y no lo es: al
            // desplegar el grupo 0 sus miles de hijos se meten en la numeración, así que
            // la fila 1 ya no es el grupo 1 sino una tarea, y el resto del bucle no
            // despliega nada. Con ese fallo este banco marcaba 51 ms a 100k —sublineal,
            // imposible— en vez de lo que cuesta.
            for (node in root.children().toList().filterIsInstance<GroupNode>()) {
                tree.expandPath(TreePath(node.path))
            }
            settle(tree)
        }
        record(antes)

        val ahora = Bench.measureOnEdt("render · contorno + una página por grupo (Fase 2)", runs = 3, warmup = 1) {
            val tree = newTree()
            list(tree, pager, state, outline).sync()
            settle(tree)
        }
        record(ahora)
    }

    /**
     * **Lo que de verdad se paga a diario**: el repintado que llega detrás de *cada*
     * comando y de *cada* tecla del buscador, con el árbol ya pintado.
     *
     * Es el escenario que justifica que el árbol se sincronice por diferencias en vez
     * de reconstruirse. Aquí la lista ya está en pantalla y sólo ha cambiado una
     * tarea: lo que se mide es cuántas filas hay que volver a medir por ello.
     */
    @Test
    fun `repintado con el arbol ya pintado`() {
        val start = loaded().copy(config = board)
        val state = TasklaneConfig.TODO
        val before = pager(start, state)
        val outlineBefore = before.outline(state)
        // La primera fila de la primera página del primer grupo: la tarea que el
        // usuario tiene delante. Completar una que no esté cargada mediría el caso
        // fácil —no hay nada que rehacer porque no había nada puesto—.
        val victim = before.page(PageQuery(state, outlineBefore.first().key)).items.first()
        val after = reducer.plan(start, TaskCommand.ToggleComplete(repo, victim.id)).snapshot

        val pagers = listOf(before, pager(after, state))
        val outlines = listOf(outlineBefore, pagers[1].outline(state))

        var flip = 0
        val viejo = Bench.measureOnEdt("repintar · reload del corpus entero (hasta la Fase 2)", runs = 3, warmup = 1) {
            val tree = newTree()
            val root = tree.model.root as CheckedTreeNode
            val which = flip++ % 2
            root.removeAllChildren()
            for (group in outlines[which]) {
                val node = GroupNode(group.key, group.size)
                pagers[which].all(PageQuery(state, group.key)).forEach { node.add(TaskNode(it, false)) }
                root.add(node)
            }
            (tree.model as DefaultTreeModel).reload()
            for (node in root.children().toList().filterIsInstance<GroupNode>()) {
                tree.expandPath(TreePath(node.path))
            }
            settle(tree)
        }
        record(viejo)

        val sync = Bench.edt { newTree().let { tree -> list(tree, pagers[0], state, outlines[0]) to tree } }
        Bench.edt { sync.first.sync() }
        var touched = 0
        var round = 0
        val nuevo = Bench.measureOnEdt("repintar · sincronizar por diferencias (Fase 2)", runs = 20, warmup = 5) {
            val which = round++ % 2
            sync.first.pager = pagers[which]
            sync.first.outline = outlines[which]
            touched = maxOf(touched, sync.first.sync())
            settle(sync.second)
        }
        // Si esto fuera cero el escenario no estaría midiendo nada: querría decir que
        // la tarea que cambió no estaba en ninguna página cargada.
        assertTrue("el repintado tiene que tener algo que rehacer", touched > 0)
        println("  filas tocadas por repintado: $touched")
        record(nuevo)
    }

    /**
     * **Desplazarse.** Llegar al centinela pide la siguiente página, y ésas sí son cien
     * filas nuevas que hay que medir. Es el otro número de la puerta de la Fase 2 —el
     * desplazamiento tiene que mantener 60 fps— y el que dice si el tamaño de página
     * está bien elegido: el presupuesto son 16 ms.
     *
     * Va sobre la pestaña **sin agrupar**, que es donde la raíz se pagina directamente
     * y las páginas son más largas —agrupando, una página se reparte entre grupos—.
     */
    @Test
    fun `desplazarse una pagina`() {
        val snapshot = loaded()
        val state = TasklaneConfig.TODO
        val pager = pager(snapshot, state)
        assertTrue("esta pestaña no agrupa: la raíz se pagina sola", pager.outline(state).isEmpty())

        val tree = Bench.edt { newTree() }
        val sync = list(tree, pager, state, emptyList())
        // La primera página fuera de la medida: lo que se cronometra es lo que cuesta
        // traer la siguiente, no abrir la lista.
        Bench.edt {
            sync.sync()
            settle(tree)
        }

        val result = Bench.measureOnEdt("desplazarse: una página más (${TaskPager.PAGE} filas)", runs = 10, warmup = 3) {
            // Exactamente lo que hace llegar al centinela desplazándose.
            val sentinel = tree.getPathForRow(tree.rowCount - 1).lastPathComponent as MoreNode
            sync.loadMore(sentinel)
            settle(tree)
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
     * La configuración de los escenarios de lista: la pestaña *ToDo* agrupando por
     * fecha. Es la agrupación que más cabeceras produce y la que el §1.4 usa para
     * describir la trampa del EDT.
     */
    private val board = config.copy(
        states = config.states.map {
            if (it.id == TasklaneConfig.TODO) it.copy(grouping = Grouping.BY_DATE) else it
        },
    )

    /**
     * El pager de una pestaña, **ya caliente**: es lo que el panel construye en su
     * corrutina, fuera del EDT. Lo que se mide después es lo que le queda al hilo de
     * interfaz.
     */
    private fun pager(snapshot: TasklaneSnapshot, state: StateId): InMemoryPager =
        InMemoryPager(snapshot, SearchResults.NONE, TaskFilter.ALL, Instant.now()).also {
            it.counts()
            it.outline(state)
        }

    private val renderer = TaskTreeRenderer().apply {
        config = board
        // Sin IDE no hay DateFormatUtil; para medir, la fecha es texto y nada más.
        formatDate = { "15/09/2026" }
    }

    /** El árbol de la tool window, con su renderer de verdad y su altura variable. */
    private fun newTree(): JTree {
        val root = CheckedTreeNode("root")
        return JTree(TaskTreeModel(root)).apply {
            cellRenderer = renderer
            isRootVisible = false
            showsRootHandles = false
            rowHeight = 0
            setSize(400, 800)
            doLayout()
        }
    }

    /**
     * Fuerza la medida de las filas que estén sin medir. Sin esto el árbol aplaza el
     * trabajo que es justamente lo que se quiere cronometrar.
     *
     * **Nada de `leftChildIndent`.** Tocarlo era la forma de forzar la medida hasta la
     * Fase 2, y hace algo más de lo que dice: `BasicTreeUI.setLeftChildIndent` llama a
     * `treeState.invalidateSizes()`, o sea tira **todas** las alturas cacheadas. Con
     * un árbol que se reconstruía entero daba igual; midiendo un repintado por
     * diferencias contaría como coste de esta fase justo lo que esta fase evita.
     */
    private fun settle(tree: JTree) {
        assertTrue(tree.rowCount > 0)
        tree.preferredSize
    }

    /**
     * Lo que hace la pestaña con el árbol, que desde la Fase 2 es **código de
     * producción**: [ListSync] no necesita un IDE, sólo un `JTree` y un pager. Antes
     * aquí había una copia de `buildSections` que podía derivar del original sin que
     * nada avisara; ahora el banco mide exactamente lo que corre.
     */
    private fun list(tree: JTree, pager: TaskPager, state: StateId, outline: List<GroupOutline>) =
        ListSync(tree, state).apply {
            this.pager = pager
            this.outline = outline
        }

    private fun record(result: Bench.Result) {
        results += result
        println("  $result")
    }

    companion object {
        /** El tamaño del corpus. `-PbenchN=100000`. */
        val n: Int = System.getProperty("tasklane.bench.n")?.toIntOrNull() ?: 10_000

        /** Lo único de `TasklanePanel` que aquí sigue copiado. Ver [ScaleBenchmark.syncBoard]. */
        const val GROUP_PAGE = 50

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
