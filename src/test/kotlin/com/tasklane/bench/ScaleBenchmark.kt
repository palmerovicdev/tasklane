package com.tasklane.bench

import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.CheckedTreeNode
import com.tasklane.data.attachment.AttachmentGc
import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.attachment.ImageNormalizer
import com.tasklane.data.sqlite.RepoSnapshot
import com.tasklane.data.sqlite.SqlitePager
import com.tasklane.data.sqlite.Fts5Index
import com.tasklane.data.sqlite.TasksXmlWriter
import com.tasklane.domain.export.ExportFormat
import com.tasklane.domain.export.TaskExporter
import kotlinx.coroutines.runBlocking
import com.tasklane.data.sqlite.TaskDb
import com.tasklane.data.sqlite.TaskImport
import com.tasklane.data.sqlite.TaskStore
import com.tasklane.data.sqlite.TasksXmlReader
import com.tasklane.data.store.StorageLayout
import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.query.QueryParser
import com.tasklane.paging.GroupOutline
import com.tasklane.paging.PageQuery
import com.tasklane.paging.TaskPager
import com.tasklane.search.SearchCorpus
import com.tasklane.service.AttachmentService
import com.tasklane.search.SearchScope
import com.tasklane.ui.toolwindow.GroupNode
import com.tasklane.ui.toolwindow.ListSync
import com.tasklane.ui.toolwindow.MoreNode
import com.tasklane.ui.toolwindow.TaskNode
import com.tasklane.ui.toolwindow.TaskTreeModel
import com.tasklane.ui.toolwindow.TaskTreeRenderer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * El banco de escala (`docs/plan-escala.md` §0.3). **Convierte cada fila de las puertas
 * en una cifra medida** en lugar de una estimación de lectura del código.
 *
 * Se ejecuta a mano y en el job nocturno, nunca en el build normal: un corpus de 100k
 * tarda minutos y reserva gigas, y eso no puede colgar de un `./gradlew test`.
 *
 *     ./gradlew test --tests '*ScaleBenchmark' -PbenchN=10000   -PtestHeap=2g
 *     ./gradlew test --tests '*ScaleBenchmark' -PbenchN=100000  -PtestHeap=4g
 *     ./gradlew test --tests '*ScaleBenchmark' -PbenchN=1000000 -PtestHeap=8g
 *
 * **Desde la Fase 3 el heap que hace falta para medir baja, y ése es medio resultado.**
 * Hasta la Fase 2 medir un millón de tareas exigía `-PtestHeap=32g` porque el corpus
 * tenía que caber en memoria; ahora el corpus vive en `tasklane.db` y lo único que se
 * materializa es lo que se está mirando.
 *
 * **Se salta con `Assume` y no con `@Ignore`**, aunque el plan dijera `@Ignore`. Son lo
 * mismo para el build normal —sin `-PbenchN` esto no corre— pero `@Ignore` obliga a
 * **editar el fichero** para lanzarlo, y un instrumento que hay que modificar para
 * usarlo es un instrumento que se queda sin usar.
 *
 * **Qué mide y qué no.** Todo lo que hay aquí son las piezas **de producción** del
 * camino real: el almacén, el paginador, el índice de texto, el reducer y el árbol de
 * Swing con su renderer de verdad. Lo que no se puede medir sin arrancar un IDE
 * —`TaskService` y `SearchService` son `@Service(PROJECT)`— se reproduce llamando a
 * exactamente lo que ellos llaman, y los sitios donde eso ocurre están señalados uno a
 * uno. No se mide el IO de la VFS porque el plugin no la usa.
 */
class ScaleBenchmark {

    private val config = TasklaneConfig.DEFAULT.normalized()
    private val repo = RepoKey.ROOT
    private val reducer = TaskReducer()

    @Before
    fun soloBajoPeticion() {
        Assume.assumeTrue(
            "Banco: se lanza con -PbenchN=<tareas>. Ver el KDoc de ScaleBenchmark.",
            System.getProperty("tasklane.bench.n") != null,
        )
    }

    // ======================================================== las puertas de la Fase 3

    /**
     * **La puerta: abrir en frío un repositorio de un millón y pintar por debajo de
     * 300 ms.**
     *
     * Lo que se mide es exactamente lo que hace la pestaña al abrirse: construir el
     * paginador, pedirle los contadores y las cabeceras —fuera del EDT— y sincronizar
     * el árbol —dentro—. Nada de esto materializa el corpus: los contadores son cinco
     * filas de `counter` y cada cabecera son dos saltos de índice.
     *
     * Se abre **una conexión nueva en cada vuelta**, porque medir con la caché de
     * páginas de SQLite ya caliente sería medir la segunda apertura y no la primera.
     */
    @Test
    fun `apertura en frio`() {
        val file = dbFile()
        val state = TasklaneConfig.TODO

        val fuera = Bench.measure("apertura · contadores + cabeceras (fuera del EDT)", runs = 5, warmup = 2) {
            val db = TaskDb.open(file.parent)!!
            try {
                val pager = SqlitePager(db.reader, repo, board, TaskFilter.ALL, Instant.now())
                assertTrue(pager.counts().isNotEmpty())
                pager.outline(state)
            } finally {
                db.close()
            }
        }
        record(fuera, ceiling = OPEN_MILLIS, why = "abrir en frío, puerta de la Fase 3")

        val db = TaskDb.open(file.parent)!!
        try {
            val pager = SqlitePager(db.reader, repo, board, TaskFilter.ALL, Instant.now()).also {
                it.counts()
                it.outline(state)
            }
            val outline = pager.outline(state)
            val dentro = Bench.measureOnEdt("apertura · primer pintado (EDT)", runs = 5, warmup = 2) {
                val tree = newTree()
                list(tree, pager, state, outline).sync()
                settle(tree)
            }
            record(dentro, ceiling = EDT_MILLIS, why = "primer pintado en el EDT")
        } finally {
            db.close()
        }
    }

    /**
     * **La puerta: crear, editar y completar por debajo de 50 ms de interfaz y 20 ms de
     * volcado.**
     *
     * Es el camino entero de `TaskService.apply`: leer del almacén **sólo la tarea que
     * el comando nombra**, planificar y aplicar, todo dentro de una transacción. Hasta
     * la Fase 2 esto copiaba la lista del repositorio y reescribía el fichero completo
     * detrás; aquí no depende de N por ninguno de los dos lados.
     */
    @Test
    fun `un comando`() {
        val db = openDb()
        val store = TaskStore(db)
        val victim = firstTask(db, store)

        record(
            Bench.measure("comando · leer sujeto + planificar + transacción", runs = 50, warmup = 10) {
                store.write {
                    val subject = TaskReducer.Subject(config, store.tasks(listOf(victim.id)))
                    val plan = reducer.plan(subject, TaskCommand.ToggleBookmark(repo, victim.id))
                    store.apply(plan.config, plan.mutations)
                }
            },
            ceiling = WRITE_MILLIS,
            why = "volcado de una edición, puerta de la Fase 3",
        )

        // Y el desglose, que es lo que dice dónde está el coste si algún día sube.
        record(
            Bench.measure("comando · desglose: leer el sujeto", runs = 50, warmup = 10) {
                store.tasks(listOf(victim.id))
            },
        )
        val subject = TaskReducer.Subject(config, store.tasks(listOf(victim.id)))
        record(
            Bench.measure("comando · desglose: el reducer", runs = 200, warmup = 50) {
                reducer.plan(subject, TaskCommand.ToggleBookmark(repo, victim.id))
            },
        )
        val plan = reducer.plan(subject, TaskCommand.ToggleBookmark(repo, victim.id))
        record(
            Bench.measure("comando · desglose: la transacción", runs = 50, warmup = 10) {
                store.apply(plan.config, plan.mutations)
            },
        )
    }

    /**
     * **La puerta: una tecla en el buscador por debajo de 100 ms.**
     *
     * Hasta la Fase 2 buscar era normalizar el texto de todas las tareas y recorrerlas:
     * 91 ms a 100.000 y ~0,9 s extrapolados a un millón, más 3 KB por tarea de
     * documentos cacheados. Aquí es un `MATCH` contra un índice invertido que ya está
     * en disco y un `LIMIT 200`.
     *
     * Se miden tres consultas distintas a propósito: una palabra frecuente —donde el
     * `LIMIT` es lo que salva—, una rara y una con operadores, que es la que va por el
     * `WHERE` de SQL en vez de por el índice de texto.
     */
    @Test
    fun `tecla en el buscador`() {
        val db = openDb()
        val index = Fts5Index(db.reader).apply { setCorpus(SearchCorpus(config)) }

        for (query in listOf("token", "revision del despliegue", "is:done token", "#api token")) {
            // «token» está en el 98 % del corpus sintético: es el peor término posible, y
            // cualquier consulta que lo lleve cuesta lo que cuestan sus aciertos, que crecen con
            // N (§3-bis.5). Su techo es el de una regresión, no el de la puerta.
            val worst = "token" in query
            record(
                Bench.measure("buscar · «$query»", runs = 20, warmup = 5) {
                    index.search(QueryParser.parse(query), SearchScope.Repo(repo))
                },
                ceiling = if (worst) searchWorstMillis else SEARCH_MILLIS,
                why = if (worst) "el término presente en casi todo" else "tecla en el buscador, puerta de la Fase 3",
            )
        }
    }

    /**
     * **La puerta: el heap del plugin por debajo de 150 MB y plano entre 10k y 1M.**
     *
     * Se mide lo que queda **vivo** después de abrir, pintar y buscar, que es el estado
     * en el que el plugin pasa el día. Hasta la Fase 2 eran 12,9 KB por tarea —el
     * §0-bis.4— y el corpus entero; ahora lo único retenido son las páginas cargadas y
     * la caché de páginas de SQLite, que tiene tope.
     */
    @Test
    fun `memoria del plugin`() {
        val baseline = Bench.heapMegabytes()
        val db = openDb()
        val state = TasklaneConfig.TODO

        val pager = SqlitePager(db.reader, repo, board, TaskFilter.ALL, Instant.now())
        pager.counts()
        val outline = pager.outline(state)
        val afterOpen = Bench.heapMegabytes()

        // Pintar: los siete `lazy` de cada tarea visible se materializan aquí, que es
        // lo que a la Fase 2 le costaba 3,4 KB por tarea **del corpus entero**.
        val tree = Bench.edt { newTree() }
        val sync = list(tree, pager, state, outline)
        Bench.edt {
            sync.sync()
            settle(tree)
        }
        val afterPaint = Bench.heapMegabytes()

        val index = Fts5Index(db.reader).apply { setCorpus(SearchCorpus(config)) }
        val found = index.search(QueryParser.parse("token"), SearchScope.Repo(repo))
        val afterSearch = Bench.heapMegabytes()

        println(
            """
            |
            |┌─ Memoria del plugin con n = $n
            |│  abrir (contadores + cabeceras)  %9.1f MB
            |│  + primer pintado                %9.1f MB
            |│  + una búsqueda                  %9.1f MB
            |│  TOTAL                           %9.1f MB   (%6.0f B por tarea)
            |└─
            """.trimMargin().format(
                afterOpen - baseline,
                afterPaint - afterOpen,
                afterSearch - afterPaint,
                afterSearch - baseline,
                (afterSearch - baseline) * 1024 * 1024 / n,
            ),
        )
        // Referenciado hasta el final para que el GC de la última medida no se lo lleve.
        assertTrue(found.isNotEmpty() && tree.rowCount > 0 && outline.isNotEmpty())
        heap("memoria · abrir + pintar + buscar", afterSearch - baseline, PLUGIN_HEAP_MB, "presupuesto del §2.6")
    }

    /**
     * **La puerta: migrar un millón desde `tasks.xml`, terminando, cancelable y sin
     * pasar de 300 MB de heap.**
     *
     * Es el §3.3 entero: StAX en vez de JDOM —un fichero de 2,9 GB no cabe en un DOM—,
     * tandas de dos mil tareas, cada una con su transacción y su marca de progreso.
     * Lo que se mide es el camino completo, y lo que se vigila es el techo de memoria:
     * si el lector retuviera el corpus, esta cifra crecería con N.
     */
    @Test
    fun `migracion desde tasks_xml`() {
        val source = corpusFile()
        // El bucle de producción, `TaskImport`, y al lado el experimento que el §5-bis.8 dejó
        // pendiente: espaciar el volcado del diario como se hizo al quitar un repositorio.
        // A más de 200.000 sólo la de producción: cada variante es otra base entera en disco.
        val variants = if (n > 200_000) listOf(TaskImport.CHECKPOINT_PAGES) else listOf(TaskImport.CHECKPOINT_PAGES, TaskStore.REMOVAL_CHECKPOINT_PAGES)
        for (pages in variants) {
            val dir = Files.createTempDirectory("tasklane-bench-migrate")
            try {
                val db = TaskDb.open(dir)!!
                val store = TaskStore(db)
                val baseline = Bench.heapMegabytes()
                var peak = 0.0
                var written = 0
                val label = if (pages == 0) "volcado de SQLite" else "volcado cada $pages páginas"

                val result = Bench.measure("migración · $n tareas, $label", runs = 1, warmup = 0) {
                    val outcome = TaskImport.run(store, source, repo, config, checkpointPages = pages) { total ->
                        written = total
                        // Cada diez tandas y al final: pesar el heap fuerza cuatro GC y no puede ir en cada una.
                        if (total % (TasksXmlReader.CHUNK * 10) == 0 || total == n) peak = maxOf(peak, Bench.heapMegabytes() - baseline)
                    }
                    assertTrue("${outcome.result}", outcome.result is TasksXmlReader.Result.Done)
                }
                record(result)

                assertEquals("tienen que estar todas", n, written)
                println("  pico de heap durante la migración: %.1f MB".format(peak))
                println("  la base pesa: ${Files.size(dir.resolve(TaskDb.FILE_NAME)) / (1024 * 1024)} MB")
                if (pages == TaskImport.CHECKPOINT_PAGES) heap("migración · pico de heap", peak, MIGRATION_HEAP_MB, "puerta de la Fase 3")
                db.close()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    // ================================================ el camino viejo, para comparar

    /**
     * **Hasta la Fase 2 — la carga en frío.** `TaskFileStore.readFile` construye el DOM
     * entero antes de que exista una sola `Task`. Se mide al lado de la migración para
     * que la comparación sea en la misma ejecución (§1-bis.5).
     *
     * A partir de cierto N esto **muere con `OutOfMemoryError`**, y ése es el dato: se
     * anota el N al que murió y con cuánto heap.
     */
    @Test
    fun `carga en frio con JDOM (hasta la Fase 2)`() {
        Assume.assumeTrue("el DOM de un millón no cabe en el heap del banco", n <= 200_000)
        val file = corpusFile()
        record(
            Bench.measure("hasta la Fase 2 · JDOM + decode", runs = 2, warmup = 0) {
                val decoded = TasksCodec.decode(JDOMUtil.load(file), repo)
                assertEquals(n, decoded.tasks.size)
            },
        )
    }

    /**
     * **Hasta la Fase 2 — el volcado.** `writeElement` devuelve un `String` que luego se
     * pasa a `ByteArray`: el pico son las dos representaciones vivas a la vez. Y ocurría
     * **cada 500 ms de tecleo**, porque el debounce reescribía el fichero entero aunque
     * hubiera cambiado una letra. Desde la Fase 3 no hay volcado: hay transacción.
     */
    @Test
    fun `volcado completo (hasta la Fase 2)`() {
        Assume.assumeTrue("el corpus de un millón en memoria no es el escenario", n <= 200_000)
        val tasks = corpus()
        record(
            Bench.measure("hasta la Fase 2 · encode + writeElement", runs = 2, warmup = 0) {
                val bytes = JDOMUtil.writeElement(TasksCodec.encode(repo, tasks)).toByteArray(StandardCharsets.UTF_8)
                assertTrue(bytes.isNotEmpty())
            },
        )
    }

    // ============================================== los escenarios del EDT (Fase 2)

    /**
     * **§1.4 — la trampa del EDT.** Con `rowHeight = 0`, `JTree` usa
     * `VariableHeightLayoutCache`, que **mide cada fila invocando al renderer**:
     * ~0,064 ms cada una, o sea que el presupuesto de 16 ms se agota en ~250 filas.
     *
     * Los dos caminos, uno al lado del otro y en la misma ejecución:
     *
     * - **Hasta la Fase 2**: un nodo por tarea, `reload()` y desplegar todos los grupos.
     * - **Desde la Fase 2**: las cabeceras salen del agregado y sólo los grupos que
     *   caben en el presupuesto reciben su primera página.
     *
     * Ahora las filas salen de `SqlitePager`, así que el camino viejo hay que
     * materializarlo a propósito —`pager.all`— y a un millón de tareas eso ya no cabe:
     * por eso se salta a partir de cierto N. **Que no quepa es el resultado.**
     */
    @Test
    fun `render en el EDT`() {
        val db = openDb()
        val state = TasklaneConfig.TODO
        val pager = boardPager(db, state)
        val outline = pager.outline(state)

        if (n <= 200_000) {
            val antes = Bench.measureOnEdt("render · el corpus entero + reload (hasta la Fase 2)", runs = 3, warmup = 1) {
                val tree = newTree()
                val root = tree.model.root as CheckedTreeNode
                for (group in outline) {
                    val node = GroupNode(group.key, group.size)
                    pager.each(PageQuery(state, group.key)) { chunk -> chunk.forEach { node.add(TaskNode(it, false)) } }
                    root.add(node)
                }
                (tree.model as DefaultTreeModel).reload()
                for (node in root.children().toList().filterIsInstance<GroupNode>()) {
                    tree.expandPath(TreePath(node.path))
                }
                settle(tree)
            }
            record(antes)
        }

        val ahora = Bench.measureOnEdt("render · lista acotada (Fase 2 sobre SQLite)", runs = 5, warmup = 2) {
            val tree = newTree()
            list(tree, pager, state, outline).sync()
            settle(tree)
        }
        record(ahora, ceiling = EDT_MILLIS, why = "pintar la pestaña en el EDT")
    }

    /**
     * **Lo que de verdad se paga a diario**: el repintado que llega detrás de *cada*
     * comando y de *cada* tecla del buscador, con el árbol ya pintado.
     *
     * Aquí la lista ya está en pantalla y sólo ha cambiado una tarea: lo que se mide es
     * cuántas filas hay que volver a medir por ello.
     */
    @Test
    fun `repintado con el arbol ya pintado`() {
        val db = openDb()
        val store = TaskStore(db)
        val state = TasklaneConfig.TODO
        val pager = boardPager(db, state)
        val outline = pager.outline(state)
        // El PRIMER grupo con algo dentro, no el primero a secas: agrupando por fecha,
        // «Hoy» se pinta aunque esté vacío —es deliberado, ver `SqlitePager.dateOutline`—
        // y un corpus cuyas fechas caen todas en el pasado empieza justo por él.
        val victim = outline.firstNotNullOfOrNull { group ->
            pager.page(PageQuery(state, group.key)).items.firstOrNull()
        } ?: error("el corpus no tiene ninguna tarea en $state")

        val tree = Bench.edt { newTree() }
        val sync = list(tree, pager, state, outline)
        Bench.edt {
            sync.sync()
            settle(tree)
        }

        // 1. El suelo: un repintado en el que **nada** ha cambiado. Es el caso más
        //    frecuente de todos —un evento de VCS repetido, el `ConfigChanged` de cada
        //    arranque— y es el que tiene que ser plano en N.
        //
        //    Los paginadores se construyen FUERA de la medida a propósito: el panel los
        //    construye en su corrutina y sólo sincroniza en el hilo de interfaz. Uno por
        //    vuelta, porque un paginador reutilizado tendría la ventana ya traída y eso
        //    mediría el caso fácil.
        val fresh = ArrayDeque((0 until 26).map { boardPager(db, state) })
        record(
            Bench.measureOnEdt("repintar · sin cambios (volver a pedir la ventana)", runs = 20, warmup = 5) {
                sync.pager = fresh.removeFirstOrNull() ?: return@measureOnEdt
                sync.sync()
                settle(tree)
            },
            ceiling = EDT_MILLIS,
            why = "repintado en el EDT",
        )

        // 2. Y lo que el usuario nota al pulsar el marcador: la transacción, el
        //    paginador nuevo y el repintado, todo en el hilo de interfaz, que es donde
        //    de verdad ocurre.
        var touched = 0
        var round = 0
        record(
            Bench.measureOnEdt("editar una tarea · comando + repintado (EDT)", runs = 20, warmup = 5) {
                val marked = round++ % 2 == 0
                store.apply(board, listOf(Mutation.Upsert(listOf(victim.copy(bookmarked = marked)))))
                val fresh = boardPager(db, state)
                sync.pager = fresh
                sync.outline = fresh.outline(state)
                touched = maxOf(touched, sync.sync())
                settle(tree)
            },
            ceiling = UI_MILLIS,
            why = "crear/editar/completar, puerta de la Fase 3",
        )
        // Si esto fuera cero el escenario no estaría midiendo nada: querría decir que la
        // tarea que cambió no estaba en ninguna página cargada.
        assertTrue("el repintado tiene que tener algo que rehacer", touched > 0)
        println("  filas tocadas por repintado: $touched")
    }

    /**
     * **Desplazarse.** Llegar al centinela pide la siguiente página, y ésas sí son
     * cincuenta filas nuevas que hay que medir. Es el número que dice si el tamaño de
     * página está bien elegido: el presupuesto son 16 ms.
     *
     * Va sobre la pestaña **sin agrupar**, que es donde la raíz se pagina directamente.
     */
    @Test
    fun `desplazarse una pagina`() {
        val db = openDb()
        val state = TasklaneConfig.TODO
        val pager = SqlitePager(db.reader, repo, config, TaskFilter.ALL, Instant.now()).also { it.counts() }
        assertTrue("esta pestaña no agrupa: la raíz se pagina sola", pager.outline(state).isEmpty())

        val tree = Bench.edt { newTree() }
        val sync = list(tree, pager, state, emptyList())
        Bench.edt {
            sync.sync()
            settle(tree)
        }

        val result = Bench.measureOnEdt("desplazarse: una página más (${TaskPager.PAGE} filas)", runs = 10, warmup = 3) {
            val sentinel = sync.visibleSentinel() ?: lastSentinel(tree) ?: return@measureOnEdt
            sync.loadMore(sentinel)
            settle(tree)
        }
        record(result, ceiling = EDT_MILLIS, why = "desplazarse en el EDT")
    }

    // ===================================================== longevidad (Fase 6, §6.4)

    /**
     * **§6.4 — muchos comandos seguidos sobre el corpus grande, y que nada crezca.**
     *
     * Es la clase de fallo que ya apareció una vez (`dc3f601`, `lastScrollAt`): nada va lento
     * el primer minuto, y a las tres horas el heap es otro. Se aplican [commands] comandos
     * —marcar, editar el cuerpo, mover de estado, crear y borrar— con el camino de producción,
     * con repintados de la pestaña y búsquedas entre medias, y se vigilan tres cosas:
     *
     * - **El heap retenido** no crece entre la primera muestra y las siguientes.
     * - **El diario** no crece: una lectura que se quedara abierta sujetaría el WAL y lo haría
     *   crecer sin techo, que en WAL es la fuga que no se ve en el heap.
     * - **La latencia** del último tramo no se aleja de la del primero.
     *
     * Sobre quinientas tareas del corpus, que se dejan al final **como estaban**: los demás
     * escenarios comparten la base.
     */
    @Test
    fun `longevidad · muchos comandos seguidos`() {
        val db = openDb()
        val store = TaskStore(db)
        val victims = SqlitePager(db.reader, repo, config, TaskFilter.ALL, Instant.now())
            .page(PageQuery(TasklaneConfig.TODO, null, LONGEVITY_VICTIMS)).items
        assertTrue("hacen falta tareas sobre las que trabajar", victims.size >= 100)
        val originals = store.tasks(victims.map { it.id })
        val index = Fts5Index(db.reader).apply { setCorpus(SearchCorpus(config)) }
        val tree = Bench.edt { newTree() }
        val state = TasklaneConfig.TODO

        fun command(i: Int) {
            val victim = victims[i % victims.size]
            when (i % 10) {
                0, 1, 2, 3 -> apply(store, TaskCommand.ToggleBookmark(repo, victim.id))
                4, 5 -> apply(store, TaskCommand.UpdateBody(repo, victim.id, victim.body + " · vuelta $i"))
                6, 7 -> apply(store, TaskCommand.ChangeState(repo, victim.id, if (i % 20 < 10) TasklaneConfig.DOING else TasklaneConfig.TODO))
                8 -> store.apply(config, listOf(Mutation.Upsert(listOf(victim.copy(id = com.tasklane.domain.model.TaskId("longevidad-$i"))))))
                else -> store.apply(config, listOf(Mutation.Delete(listOf(com.tasklane.domain.model.TaskId("longevidad-${i - 1}")))))
            }
        }

        val windows = 10
        val perWindow = (commands / windows).coerceAtLeast(1)
        val heaps = ArrayList<Double>()
        val wal = ArrayList<Long>()
        val p99 = ArrayList<Double>()
        var i = 0
        try {
            repeat(windows) { w ->
                val samples = DoubleArray(perWindow)
                for (k in 0 until perWindow) {
                    val start = System.nanoTime()
                    command(i++)
                    samples[k] = (System.nanoTime() - start) / 1_000_000.0
                    // Lo que la ventana hace detrás de cada comando, cada tanto: pedir la
                    // pestaña de nuevo y repintarla, y una tecla en el buscador.
                    if (i % 1_000 == 0) {
                        val pager = SqlitePager(db.reader, repo, config, TaskFilter.ALL, Instant.now()).also { it.counts() }
                        Bench.edt {
                            list(tree, pager, state, emptyList()).sync()
                            settle(tree)
                        }
                    }
                    if (i % 2_000 == 0) index.search(QueryParser.parse("token"), SearchScope.Repo(repo))
                }
                samples.sort()
                p99 += samples[(samples.size * 99 / 100).coerceAtMost(samples.size - 1)]
                heaps += Bench.heapMegabytes()
                wal += runCatching { Files.size(TaskDb.walOf(db.file)) }.getOrDefault(0L)
                println("  longevidad · tramo ${w + 1}/$windows · p99 %.2f ms · heap %.1f MB · diario %d MB".format(p99.last(), heaps.last(), wal.last() / (1 shl 20)))
            }
        } finally {
            // Las tareas como estaban, y fuera las que se crearon.
            store.apply(config, listOf(Mutation.Upsert(originals)))
            store.apply(config, listOf(Mutation.Delete((0 until i).map { com.tasklane.domain.model.TaskId("longevidad-$it") })))
        }

        // La primera muestra es la de después de calentar: clases cargadas, JIT, cachés
        // llenas. Lo que importa es que de ahí no se mueva.
        val growth = heaps.drop(1).max() - heaps.first()
        heap("longevidad · crecimiento del heap en $commands comandos", growth, LONGEVITY_HEAP_GROWTH_MB, "sin fugas, §6.4")
        heap("longevidad · diario al final", wal.last() / (1024.0 * 1024.0), LONGEVITY_WAL_MB, "ninguna lectura sujeta el WAL")
        val drift = p99.last()
        assertTrue(
            "la latencia se alejó: p99 del primer tramo %.2f ms, del último %.2f ms".format(p99.first(), drift),
            drift <= maxOf(p99.first() * 2, p99.first() + 2.0),
        )
    }

    private fun apply(store: TaskStore, command: TaskCommand) {
        store.write {
            val plan = reducer.plan(TaskReducer.Subject(config, store.tasks(reducer.targetsOf(command))), command)
            if (!plan.isEmpty) store.apply(plan.config, plan.mutations)
        }
    }

    // ======================================================== las puertas de la Fase 4

    /**
     * **§1.6 / §4.1 — el arranque, antes y ahora.**
     *
     * Hasta la 2.0, saber qué adjuntos había era listar el directorio: un `Files.list`
     * más un `readAttributes` por entrada, en cada apertura de proyecto, sobre un
     * directorio plano. Es la operación que el §1.6 dice que no termina con diez
     * millones de ficheros, y aquí está medida sobre el número que se le pase.
     *
     * Desde la Fase 4 el arranque **no mira el directorio**: los candidatos salen de la
     * tabla `blob` por índice y a tandas. Las dos cifras se miden aquí para que la
     * comparación no sea una afirmación.
     *
     *     ./gradlew test --tests '*ScaleBenchmark' -PbenchN=10000 -PbenchBlobs=100000
     */
    @Test
    fun `adjuntos · el arranque, antes y ahora`() {
        val root = Files.createTempDirectory("tasklane-blobs")
        try {
            val layout = StorageLayout(root)
            val dir = layout.attachmentsDir(repo)
            val bytes = SyntheticBlobs.writeAll(dir, blobs)
            println(
                "Escritos $blobs blobs planos, ${bytes / (1024 * 1024)} MB " +
                    "(%.0f KB de media)".format(bytes / 1024.0 / blobs),
            )

            record(
                Bench.measure("adjuntos · listar el directorio plano de $blobs (hasta la 2.0)", runs = 3, warmup = 1) {
                    Files.list(dir).use { paths ->
                        paths.toList().map { Files.readAttributes(it, BasicFileAttributes::class.java) }
                    }
                },
            )

            withBlobTable(blobs) { store ->
                record(
                    Bench.measure("adjuntos · una tanda de candidatos de $blobs (2.1)", runs = 10, warmup = 3) {
                        store.collectibleBlobs(repo, Instant.now().toEpochMilli(), AttachmentService.GC_BATCH)
                    },
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * **§4.2 — la puerta: recoger la basura de un millón de blobs en menos de 30 s, en
     * segundo plano.**
     *
     * Lo que se mide es el bucle de `AttachmentService.collectGarbage` con sus piezas de
     * producción: la consulta por tandas, la política pura de [AttachmentGc] y el borrado
     * del fichero y su miniatura. Ninguna tarea referencia nada, así que **se recoge
     * todo**: es el peor caso posible y el más caro, porque cada blob cuesta además un
     * borrado de disco.
     */
    @Test
    fun `adjuntos · recoger la basura`() {
        val root = Files.createTempDirectory("tasklane-gc")
        try {
            val layout = StorageLayout(root)
            val store = AttachmentStore(layout)
            val written = SyntheticBlobs.writeAll(layout.attachmentsDir(repo), blobs, sharded = true)
            println("Escritos $blobs blobs en el árbol, ${written / (1024 * 1024)} MB")

            withBlobTable(blobs) { tasks ->
                var deleted = 0
                val result = Bench.measure("adjuntos · recoger $blobs sin referencias", runs = 1, warmup = 0) {
                    val now = Instant.now()
                    val cutoff = now.minus(AttachmentGc.DEFAULT_GRACE).toEpochMilli()
                    while (true) {
                        val batch = tasks.collectibleBlobs(repo, cutoff, AttachmentService.GC_BATCH)
                        if (batch.isEmpty()) break
                        val collectible = AttachmentGc.collectible(batch, emptySet(), now)
                        // `flatToo = false`: el árbol ya está fragmentado, que es lo que
                        // decide el servicio mirando la marca del traslado. Con `true`
                        // esto mediría dos `unlink` de más por blob que no pueden
                        // encontrar nada, o sea el doble de llamadas al sistema.
                        for (blob in collectible) if (store.delete(repo, blob.id, flatToo = false)) deleted++
                        tasks.write { tasks.forgetBlobs(repo, collectible.map { it.id }) }
                    }
                }
                assertEquals("tiene que recogerlos todos", blobs, deleted)
                record(result)
                println("  %.3f ms por blob".format(result.p50Millis / blobs))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * **§4.3 — reconciliar el árbol.**
     *
     * El recorrido completo, que es lo que ocurre como mucho una vez por semana: mirar
     * todos los ficheros, preguntar por tandas cuáles ya se conocen, adoptar los que no
     * —leyendo su tamaño de la cabecera del PNG, no descodificándolo— y marcar ausente
     * lo que no se vio.
     *
     * Se mide sobre una tabla **vacía**, que es el caso más caro: todo es nuevo y todo
     * hay que adoptarlo. La pasada semanal normal no adopta nada y sólo sella.
     */
    @Test
    fun `adjuntos · reconciliar el arbol`() {
        val root = Files.createTempDirectory("tasklane-fsck")
        try {
            val layout = StorageLayout(root)
            val store = AttachmentStore(layout)
            SyntheticBlobs.writeAll(layout.attachmentsDir(repo), blobs, sharded = true)

            withBlobTable(0) { tasks ->
                var adopted = 0
                val result = Bench.measure("adjuntos · reconciliar $blobs desde cero", runs = 1, warmup = 0) {
                    val stamp = Instant.now().toEpochMilli()
                    store.scan(repo) { batch ->
                        val ids = batch.blobs.map { it.id }
                        val known = tasks.knownBlobs(repo, ids)
                        val fresh = batch.blobs.filter { it.id.value !in known }.map { blob ->
                            val size = store.dimensionsOf(blob.path)
                            BlobRecord(blob.id, blob.size, size?.width ?: 0, size?.height ?: 0, blob.modified)
                        }
                        tasks.write {
                            tasks.seeBlobs(repo, ids, stamp)
                            tasks.adoptBlobs(repo, fresh, stamp)
                        }
                        adopted += fresh.size
                    }
                    tasks.write { tasks.markMissingBlobs(repo, stamp) }
                }
                assertEquals("tiene que adoptarlos todos", blobs, adopted)
                assertEquals(blobs, tasks.blobStatsOf(repo).count)
                record(result)

                // Y la pasada de verdad: la semanal, en la que no hay nada nuevo. No se
                // abre un solo fichero para leer su cabecera, así que lo que queda es el
                // recorrido del árbol y sellar lo visto. Es el número que se paga cada
                // semana; el de arriba se paga una vez.
                record(
                    Bench.measure("adjuntos · reconciliar $blobs sin novedades", runs = 1, warmup = 0) {
                        val stamp = Instant.now().toEpochMilli()
                        store.scan(repo) { batch ->
                            val ids = batch.blobs.map { it.id }
                            val known = tasks.knownBlobs(repo, ids)
                            val fresh = batch.blobs.filter { it.id.value !in known }
                            assertTrue("en la segunda pasada no puede haber nada nuevo", fresh.isEmpty())
                            tasks.write { tasks.seeBlobs(repo, ids, stamp) }
                        }
                        assertEquals(0, tasks.write { tasks.markMissingBlobs(repo, stamp) })
                    },
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * **§4.4 — lo que una tarjeta desplegada pone en memoria.**
     *
     * Diez imágenes en una tarjeta, que es lo que dice la especificación del §1.1. Con
     * los originales de 1600 px que puede haber guardados de antes son diez
     * `BufferedImage` de 10,2 MB; con sus miniaturas de 256, de 260 KB. Es la cifra que
     * justifica que la lista **nunca** descodifique el original.
     */
    @Test
    fun `adjuntos · lo que descodifica una tarjeta`() {
        val root = Files.createTempDirectory("tasklane-thumbs")
        try {
            val layout = StorageLayout(root)
            val store = AttachmentStore(layout)
            val ids = (0 until CARD_IMAGES).map { n ->
                val id = store.put(repo, SyntheticBlobs.png(n, SyntheticBlobs.LEGACY_SIZE))
                ImageNormalizer.thumbnail(store.load(repo, id)!!)?.let { store.putThumbnail(repo, id, it) }
                id
            }

            val originals = Bench.measure("adjuntos · $CARD_IMAGES originales de 1600 px", runs = 3, warmup = 1) {
                held = ids.map { store.load(repo, it)!! }
            }
            record(originals)
            val thumbs = Bench.measure("adjuntos · $CARD_IMAGES miniaturas de 256 px", runs = 3, warmup = 1) {
                held = ids.map { store.load(repo, it, thumbnail = true)!! }
            }
            record(thumbs)
            held = emptyList()

            println(
                "  heap retenido: %.1f MB con originales, %.1f MB con miniaturas"
                    .format(originals.heapMegabytes, thumbs.heapMegabytes),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * **2.3 — guardar una imagen, antes y ahora.** Hasta la 2.2 toda imagen se reescalaba
     * a 400 px y se volvía a codificar a PNG; desde la 2.3 se guarda a su tamaño: los
     * píxeles del portapapeles en PNG, y un fichero soltado con sus bytes.
     *
     * Sobre una captura sintética de [SCREENSHOT_SIZE] px calibrada como las del §0-bis.5.
     * Todo en memoria: lo que se compara es el trabajo de CPU, no el disco. Ocurre fuera
     * del EDT en los dos caminos, al pegar.
     */
    @Test
    fun `adjuntos · guardar una imagen, antes y ahora`() {
        val source = SyntheticBlobs.png(7, SCREENSHOT_SIZE)
        val decoded = javax.imageio.ImageIO.read(source.inputStream())
        val clipboard = java.awt.image.BufferedImage(decoded.width, decoded.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            .also { it.createGraphics().run { drawImage(decoded, 0, 0, null); dispose() } }
        val file = Files.createTempFile("tasklane-drop", ".png").also { Files.write(it, source) }
        try {
            var before = ByteArray(0)
            var after = ByteArray(0)
            record(
                Bench.measure("guardar · pegar píxeles, a 400 px (hasta la 2.2)", runs = 8, warmup = 2) {
                    before = legacyNormalize(clipboard, 400)
                    ImageNormalizer.sha256(before)
                },
            )
            record(
                Bench.measure("guardar · pegar píxeles, a su tamaño + miniatura (2.3)", runs = 8, warmup = 2) {
                    val pixels = ImageNormalizer.pixels(clipboard)
                    after = ImageNormalizer.encode(pixels)
                    ImageNormalizer.thumbnail(pixels)
                    ImageNormalizer.sha256(after)
                },
            )
            record(
                Bench.measure("guardar · soltar un fichero, descodificar + 400 px (hasta la 2.2)", runs = 8, warmup = 2) {
                    ImageNormalizer.sha256(legacyNormalize(javax.imageio.ImageIO.read(file.toFile()), 400))
                },
            )
            record(
                Bench.measure("guardar · soltar un fichero, sus bytes (2.3)", runs = 8, warmup = 2) {
                    val bytes = Files.readAllBytes(file)
                    assertTrue(ImageNormalizer.sizeOf(bytes) != null)
                    ImageNormalizer.sha256(bytes)
                },
            )
            // Lo que el fichero soltado deja para después: su miniatura, que la lista hace
            // la primera vez que lo pinta, en segundo plano.
            record(
                Bench.measure("guardar · la miniatura aplazada de un fichero (2.3)", runs = 8, warmup = 2) {
                    ImageNormalizer.thumbnail(javax.imageio.ImageIO.read(file.toFile()))
                },
            )
            println(
                "  en disco: %d KB a 400 px, %d KB a su tamaño (%.0f×); un fichero soltado, %d KB tal cual"
                    .format(before.size / 1024, after.size / 1024, after.size.toDouble() / before.size, source.size / 1024),
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** El `ImageNormalizer.normalize` de hasta la 2.2, para medirlo al lado: reescalar y PNG. */
    private fun legacyNormalize(image: java.awt.image.BufferedImage, maxSize: Int): ByteArray {
        val factor = maxSize.toDouble() / maxOf(image.width, image.height)
        val scaled = if (factor >= 1.0) image else {
            val w = (image.width * factor).toInt().coerceAtLeast(1)
            val h = (image.height * factor).toInt().coerceAtLeast(1)
            java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB).also { target ->
                target.createGraphics().run {
                    setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                    setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    drawImage(image, 0, 0, w, h, null)
                    dispose()
                }
            }
        }
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(scaled, "png", it) }.toByteArray()
    }

    // =========================================== las operaciones grandes (Fase 5)

    /**
     * **§5.1 — exportar un estado entero, en streaming.**
     *
     * Exportar es O(n) por definición, así que la puerta no es el tiempo sino **la
     * memoria**: que exportar 300.000 filas no retenga 300.000 `Task`. Se mide lo que hace
     * la pestaña —las secciones de su esquema, sobre una foto del paginador— escribiendo a
     * un sumidero que sólo cuenta, y se vigila el pico de heap por el camino.
     *
     * Al lado, el camino de la 2.1: pedir cada sección entera a una lista y construir el
     * `String`.
     */
    @Test
    fun `exportar un estado entero`() {
        val db = openDb()
        val state = TasklaneConfig.TODO
        val pager = SqlitePager(db.reader, repo, board, TaskFilter.ALL, Instant.now(), detach = db::openReader)
        val outline = pager.outline(state)
        val rows = outline.sumOf { it.size }

        fun stream(sample: (() -> Unit)?): Long {
            val sink = CountingSink()
            val out = TaskExporter.Stream(sink, board, ExportFormat.MARKDOWN)
            var chunks = 0
            pager.snapshot { frozen ->
                for (group in outline) {
                    out.section("ToDo · ${group.key}")
                    frozen.each(PageQuery(state, group.key)) { chunk ->
                        chunk.forEach(out::task)
                        if (sample != null && ++chunks % 4 == 0) sample()
                    }
                }
            }
            assertEquals(rows, out.written)
            return sink.chars
        }

        var chars = 0L
        record(Bench.measure("exportar · un estado de $rows filas, en streaming", runs = 3, warmup = 1) { chars = stream(null) })
        val baseline = Bench.heapMegabytes()
        var peak = 0.0
        stream { peak = maxOf(peak, Bench.heapMegabytes() - baseline) }
        println("  %d MB de texto · pico de heap en streaming: %.1f MB".format(chars / (1024 * 1024), peak))
        heap("exportar un estado · pico de heap", peak, PLUGIN_HEAP_MB, "memoria constante, Fase 5")

        if (n > 200_000) return
        record(
            Bench.measure("hasta la 2.1 · cada sección a una lista + String", runs = 3, warmup = 1) {
                assertTrue(TaskExporter.export(oldSections(pager, outline, state), board, ExportFormat.MARKDOWN).isNotEmpty())
            },
        )
        // Lo que tenía vivo la 2.1 en su punto más alto: las secciones enteras y, encima,
        // el texto construido a partir de ellas.
        val before = Bench.heapMegabytes()
        val sections = oldSections(pager, outline, state)
        val text = TaskExporter.export(sections, board, ExportFormat.MARKDOWN)
        println(
            "  retenido por el camino de la 2.1 en su pico: %.1f MB (%d secciones, %d MB de texto)"
                .format(Bench.heapMegabytes() - before, sections.size, text.length / (1024 * 1024)),
        )
    }

    private fun oldSections(pager: TaskPager, outline: List<GroupOutline>, state: StateId): List<TaskExporter.Section> =
        outline.map { group ->
            TaskExporter.Section("ToDo · ${group.key}", buildList { pager.each(PageQuery(state, group.key), Int.MAX_VALUE) { addAll(it) } })
        }

    /**
     * **§5.1 — exportar el repositorio a `tasks.xml`.** La puerta de salida de la base.
     * En la 2.1 era leer el repositorio entero a una lista, construir el árbol de JDOM,
     * pasarlo a `String` y a bytes: tres copias del fichero vivas a la vez, y a un millón
     * un `OutOfMemoryError` seguro.
     */
    @Test
    fun `exportar a XML`() {
        val db = openDb()

        fun stream(sample: (() -> Unit)?): Long {
            val out = CountingStream()
            var chunks = 0
            RepoSnapshot.open(db, repo, config) { snapshot ->
                val xml = TasksXmlWriter(out, repo)
                snapshot.eachInOrder { chunk ->
                    xml.write(chunk)
                    if (sample != null && ++chunks % 10 == 0) sample()
                }
                xml.finish()
                assertEquals(n, xml.written)
            }
            return out.bytes
        }

        var bytes = 0L
        record(Bench.measure("exportar · tasks.xml de $n, en streaming", runs = 2, warmup = 1) { bytes = stream(null) })
        val baseline = Bench.heapMegabytes()
        var peak = 0.0
        stream { peak = maxOf(peak, Bench.heapMegabytes() - baseline) }
        println("  %d MB de XML · pico de heap en streaming: %.1f MB".format(bytes / (1024 * 1024), peak))
        heap("exportar a XML · pico de heap", peak, PLUGIN_HEAP_MB, "memoria constante, Fase 5")

        if (n > 200_000) return
        record(
            Bench.measure("hasta la 2.1 · lista + JDOM + String + bytes", runs = 2, warmup = 0) {
                val tasks = buildList { RepoSnapshot.open(db, repo, config) { it.eachInOrder { chunk -> addAll(chunk) } } }
                assertTrue(JDOMUtil.writeElement(TasksCodec.encode(repo, tasks)).toByteArray(StandardCharsets.UTF_8).isNotEmpty())
            },
        )
        val before = Bench.heapMegabytes()
        val tasks = buildList { RepoSnapshot.open(db, repo, config) { it.eachInOrder { chunk -> addAll(chunk) } } }
        val element = TasksCodec.encode(repo, tasks)
        val string = JDOMUtil.writeElement(element)
        val bytesOld = string.toByteArray(StandardCharsets.UTF_8)
        println(
            "  retenido por el camino de la 2.1 en su pico: %.1f MB (%d tareas, %d MB de XML)"
                .format(Bench.heapMegabytes() - before, tasks.size, bytesOld.size / (1024 * 1024)),
        )
        assertTrue(element.contentSize > 0 && string.isNotEmpty())
    }

    /**
     * **§5.3 — una operación sobre una selección grande.** Hasta la 2.1 la pestaña mandaba
     * un comando por fila: una transacción y un snapshot por cada una. Ahora es un
     * `TaskCommand.Batch`: una transacción y un snapshot para todas.
     *
     * Se marca y se desmarca, que es un cambio que no toca `updatedAt`: así las vueltas del
     * banco dejan la base como estaba y los demás escenarios miden lo mismo.
     *
     * Y el desglose del lote, que es lo que dice dónde está el coste por fila.
     */
    @Test
    fun `operacion masiva`() {
        val db = openDb()
        val store = TaskStore(db)

        for (size in listOf(10, 20, 50, 500, 2_000, 5_000)) {
            val ids = SqlitePager(db.reader, repo, config, TaskFilter.ALL, Instant.now())
                .page(PageQuery(TasklaneConfig.TODO, null, size)).items.map { it.id }
            if (ids.size < size) continue

            record(
                Bench.measure("masiva · $size comandos sueltos (hasta la 2.1)", runs = if (size <= 50) 20 else 3, warmup = if (size <= 50) 6 else 1) {
                    for (id in ids) {
                        store.write {
                            val command = TaskCommand.ToggleBookmark(repo, id)
                            val plan = reducer.plan(TaskReducer.Subject(config, store.tasks(listOf(id))), command)
                            store.apply(plan.config, plan.mutations)
                        }
                    }
                },
            )
            val batch = TaskCommand.Batch(ids.map { TaskCommand.ToggleBookmark(repo, it) })
            // El lote que `TaskService.applyAll` aplica en el acto —hasta `BULK_INLINE`— lo hace
            // en el EDT, así que ése tiene el techo del EDT. Los demás van en segundo plano.
            val inline = size <= com.tasklane.service.TaskService.BULK_INLINE
            record(
                Bench.measure("masiva · $size en un lote", runs = if (size <= 50) 20 else 3, warmup = if (size <= 50) 6 else 1) {
                    store.write {
                        val plan = reducer.plan(TaskReducer.Subject(config, store.tasks(reducer.targetsOf(batch))), batch)
                        store.apply(plan.config, plan.mutations)
                    }
                },
                ceiling = if (inline) EDT_MILLIS else null,
                why = "lote aplicado en el acto, en el EDT",
            )

            if (size != 2_000) continue
            // La misma escritura con la caché de páginas grande que usa la migración: si el
            // coste por fila crece con el tamaño de la base, es aquí donde tiene que verse.
            record(
                Bench.measure("masiva · $size en un lote, caché grande", runs = 3, warmup = 1) {
                    store.bulk {
                        store.write {
                            val plan = reducer.plan(TaskReducer.Subject(config, store.tasks(reducer.targetsOf(batch))), batch)
                            store.apply(plan.config, plan.mutations)
                        }
                    }
                },
            )
            record(Bench.measure("masiva · desglose: leer $size", runs = 5, warmup = 2) { store.tasks(ids) })
            val subject = TaskReducer.Subject(config, store.tasks(ids))
            record(Bench.measure("masiva · desglose: planificar $size", runs = 5, warmup = 2) { reducer.plan(subject, batch) })
            val on = reducer.plan(subject, batch)
            val off = reducer.plan(TaskReducer.Subject(config, (on.mutations.single() as Mutation.Upsert).tasks), batch)
            var flip = false
            record(
                Bench.measure("masiva · desglose: escribir $size", runs = 4, warmup = 2) {
                    flip = !flip
                    store.apply(config, if (flip) on.mutations else off.mutations)
                },
            )
            // Lo que cuesta sólo el índice de texto de esas filas: borrar y volver a
            // insertar, dentro de una transacción que se deshace al final.
            val rowsFts = db.writer.rows(
                "SELECT seq, title, body, tags_text, files_text FROM task WHERE id IN (${ids.take(500).joinToString(",") { "'${it.value}'" }})",
            ) { arrayOf<Any>(it.getLong(0), it.getString(1).orEmpty(), it.getString(2).orEmpty(), it.getString(3).orEmpty(), it.getString(4).orEmpty()) }
            record(
                Bench.measure("masiva · desglose: sólo FTS de 500 (x4 = 2000)", runs = 4, warmup = 2) {
                    runCatching {
                        db.writer.transaction {
                            db.writer.batch("INSERT INTO task_fts(task_fts, rowid, title, body, tags_text, files_text) VALUES ('delete', ?, ?, ?, ?, ?)", 5, rowsFts.asSequence())
                            db.writer.batch("INSERT INTO task_fts(rowid, title, body, tags_text, files_text) VALUES (?, ?, ?, ?, ?)", 5, rowsFts.asSequence())
                            throw Rollback
                        }
                    }
                },
            )
        }
    }

    /**
     * **§5.2 — quitar un repositorio entero, y §5.4 — la copia y la comprobación.**
     *
     * La copia es `VACUUM INTO` sobre la base del banco, y es además la base que se
     * destruye después: quitar un repositorio de verdad sobre la compartida dejaría a los
     * demás escenarios sin corpus.
     *
     * Lo que importa de quitar no es el total —borrar un millón de filas cuesta lo que
     * cuesta— sino **la tanda más larga**: es lo máximo que espera cualquier otro comando
     * mientras tanto, contra la transacción única de la 2.1, que tenía el escritor tomado
     * de principio a fin.
     */
    @Test
    fun `quitar un repositorio y copiar la base`() {
        // Tres copias enteras de la base: a un millón son treinta gigas, y no caben en el
        // runner de la CI nocturna. Lo que mide ya se midió a 100.000 (§5-bis).
        Assume.assumeTrue("tres copias de la base no caben a este tamaño", n <= 200_000)
        val db = openDb()
        val dir = Files.createTempDirectory("tasklane-bench-remove")
        try {
            val copyFile = dir.resolve("copia").resolve(TaskDb.FILE_NAME)
            Files.createDirectories(copyFile.parent)
            var size = 0L
            record(Bench.measure("mantenimiento · copia de $n con VACUUM INTO", runs = 1, warmup = 0) { size = db.backupTo(copyFile) })
            println("  la copia pesa ${size / (1024 * 1024)} MB; la base, ${Files.size(db.file) / (1024 * 1024)} MB")
            record(
                Bench.measure("mantenimiento · integrity_check de $n", runs = 1, warmup = 0) {
                    assertTrue(db.checkIntegrity().isEmpty())
                },
            )

            // Las variantes, cada una sobre su propia copia: tal cual, con la caché grande
            // que ya usa la migración, y con la caché y los *checkpoints* espaciados. Es el
            // experimento que decidió cómo borra `TaskService.removeRepo`.
            fun removal(label: String, file: Path, around: (TaskDb, TaskStore, () -> Unit) -> Unit) {
                val copy = TaskDb.open(file.parent)!!
                val batches = ArrayList<Double>()
                try {
                    val store = TaskStore(copy)
                    record(
                        Bench.measure("quitar · $n por tandas de ${TaskStore.FORGET_BATCH} · $label", runs = 1, warmup = 0) {
                            around(copy, store) {
                                while (true) {
                                    val start = System.nanoTime()
                                    val removed = store.forgetBatch(repo)
                                    if (removed == 0) break
                                    batches += (System.nanoTime() - start) / 1_000_000.0
                                }
                            }
                        },
                    )
                    assertEquals(0, copy.reader.count("SELECT count(*) FROM task"))
                } finally {
                    copy.close()
                    file.parent.toFile().deleteRecursively()
                }
                batches.sort()
                println(
                    "  %s · %d tandas · p50 %.1f ms · p99 %.1f ms · la más larga %.1f ms".format(
                        label,
                        batches.size,
                        batches[batches.size / 2],
                        batches[(batches.size * 99 / 100).coerceAtMost(batches.size - 1)],
                        batches.last(),
                    ),
                )
            }

            fun freshCopy(name: String): Path {
                if (name == "copia") return copyFile
                val file = dir.resolve(name).resolve(TaskDb.FILE_NAME)
                Files.createDirectories(file.parent)
                db.backupTo(file)
                return file
            }

            removal("tal cual", freshCopy("copia")) { _, _, run -> run() }
            removal("caché grande", freshCopy("bulk")) { _, store, run -> store.bulk(block = run) }
            // Y la de producción: la caché grande con el volcado del diario espaciado.
            removal("caché grande + checkpoint cada ${TaskStore.REMOVAL_CHECKPOINT_PAGES} páginas", freshCopy("bulk-wal")) { _, store, run ->
                store.bulk(TaskStore.REMOVAL_CHECKPOINT_PAGES, run)
            }

            if (n > 200_000) return
            val again = dir.resolve("otra").resolve(TaskDb.FILE_NAME)
            Files.createDirectories(again.parent)
            db.backupTo(again)
            val old = TaskDb.open(again.parent)!!
            try {
                val store = TaskStore(old)
                record(
                    Bench.measure("hasta la 2.1 · quitar $n en UNA transacción", runs = 1, warmup = 0) {
                        store.apply(config, listOf(Mutation.Forget(repo)))
                    },
                )
            } finally {
                old.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * **§5.2 — borrar el árbol de adjuntos de un repositorio.** Hasta la 2.1 era
     * `Files.walk` más `sorted(reverseOrder())`, y ordenar es materializar: una ruta en
     * memoria por fichero antes de borrar el primero. Ahora `walkFileTree`, que sólo
     * recuerda la rama en la que está. Eje `-PbenchBlobs`; los ficheros van vacíos,
     * porque lo que se mide son las rutas y no los bytes.
     */
    @Test
    fun `adjuntos · borrar el arbol de un repositorio`() {
        val root = Files.createTempDirectory("tasklane-rmtree")
        try {
            val layout = StorageLayout(root)
            fun populate() {
                val dir = layout.attachmentsDir(repo)
                for (i in 0 until blobs) {
                    val id = SyntheticBlobs.id(i)
                    val leaf = dir.resolve(id.value.substring(0, 2)).resolve(id.value.substring(2, 4))
                    Files.createDirectories(leaf)
                    Files.createFile(leaf.resolve("${id.value}.png"))
                }
            }

            populate()
            var peakOld = 0.0
            val baseOld = Bench.heapMegabytes()
            record(
                Bench.measure("hasta la 2.1 · walk + sorted de $blobs ficheros", runs = 1, warmup = 0) {
                    Files.walk(layout.repoDir(repo)).use { paths ->
                        var i = 0
                        paths.sorted(Comparator.reverseOrder()).forEach { path ->
                            if (++i == 1) peakOld = Bench.heapMegabytes() - baseOld
                            Files.deleteIfExists(path)
                        }
                    }
                },
            )

            populate()
            var peakNew = 0.0
            val baseNew = Bench.heapMegabytes()
            record(
                Bench.measure("borrar · walkFileTree de $blobs ficheros", runs = 1, warmup = 0) {
                    runBlocking {
                        com.tasklane.data.store.TaskFileStore(layout).delete(repo) {
                            if (it == 1L) peakNew = Bench.heapMegabytes() - baseNew
                        }
                    }
                },
            )
            assertTrue(!Files.exists(layout.repoDir(repo)))
            println("  heap al empezar a borrar: %.1f MB ordenando, %.1f MB recorriendo".format(peakOld, peakNew))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Un sumidero de texto que sólo cuenta. Mide el exportador sin medir el disco. */
    private class CountingSink : Appendable {
        var chars = 0L
        override fun append(csq: CharSequence?): Appendable = apply { chars += csq?.length ?: 4 }
        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = apply { chars += end - start }
        override fun append(c: Char): Appendable = apply { chars++ }
    }

    private class CountingStream : java.io.OutputStream() {
        var bytes = 0L
        override fun write(b: Int) {
            bytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bytes += len
        }
    }

    /** Para deshacer una transacción del banco a propósito. */
    private object Rollback : RuntimeException() {
        private fun readResolve(): Any = Rollback
    }

    /**
     * Una base con [count] blobs apuntados y ninguna tarea que los referencie.
     *
     * Se puebla con [TaskStore.adoptBlobs] —un lote y una sentencia preparada— porque
     * apuntarlos de uno en uno costaría más que todo lo que se mide después.
     */
    private fun <T> withBlobTable(count: Int, block: (TaskStore) -> T): T {
        val dir = Files.createTempDirectory("tasklane-blobdb")
        val db = TaskDb.open(dir)!!
        return try {
            val store = TaskStore(db)
            if (count > 0) {
                val born = Instant.now().minus(AttachmentGc.DEFAULT_GRACE).minusSeconds(60)
                store.write {
                    store.adoptBlobs(
                        repo,
                        (0 until count).map {
                            BlobRecord(
                                SyntheticBlobs.id(it),
                                SyntheticBlobs.DEFAULT_SIZE.toLong() * 80,
                                SyntheticBlobs.DEFAULT_SIZE,
                                SyntheticBlobs.DEFAULT_SIZE,
                                born,
                            )
                        },
                        born.toEpochMilli(),
                    )
                }
            }
            block(store)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ andamiaje

    /**
     * La configuración de los escenarios de lista: la pestaña *ToDo* agrupando por
     * fecha. Es la agrupación que más cabeceras produce y la que el §1.4 usa para
     * describir la trampa del EDT.
     */
    private val board = config.copy(
        states = config.states.map {
            if (it.id == TasklaneConfig.TODO) it.copy(grouping = Grouping.BY_DATE) else it
        },
    ).normalized()

    /**
     * El paginador de una pestaña, **ya caliente**: es lo que el panel construye en su
     * corrutina, fuera del EDT. Lo que se mide después es lo que le queda al hilo de
     * interfaz.
     */
    private fun boardPager(db: TaskDb, state: StateId): SqlitePager =
        SqlitePager(db.reader, repo, board, TaskFilter.ALL, Instant.now()).also {
            it.counts()
            it.outline(state)
        }

    private fun firstTask(db: TaskDb, store: TaskStore): Task {
        val pager = SqlitePager(db.reader, repo, config, TaskFilter.ALL, Instant.now())
        return pager.page(PageQuery(TasklaneConfig.TODO, null, 1)).items.firstOrNull()
            ?: store.tasks(listOf(com.tasklane.domain.model.TaskId("nope"))).first()
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
     * `treeState.invalidateSizes()`, o sea tira **todas** las alturas cacheadas.
     */
    private fun settle(tree: JTree) {
        assertTrue(tree.rowCount > 0)
        tree.preferredSize
    }

    /**
     * Lo que hace la pestaña con el árbol, que desde la Fase 2 es **código de
     * producción**: [ListSync] no necesita un IDE, sólo un `JTree` y un paginador.
     */
    private fun list(tree: JTree, pager: TaskPager, state: StateId, outline: List<GroupOutline>) =
        ListSync(tree, state).apply {
            this.pager = pager
            this.outline = outline
        }

    /**
     * El centinela del final del árbol. El de [ListSync.visibleSentinel] necesita un
     * viewport de verdad, que en el banco no hay: aquí se busca en el modelo.
     */
    private fun lastSentinel(tree: JTree): MoreNode? {
        val root = tree.model.root as CheckedTreeNode
        return root.children().toList().filterIsInstance<MoreNode>()
            .lastOrNull { it.direction == MoreNode.Direction.AFTER }
    }

    private fun record(result: Bench.Result, ceiling: Double? = null, why: String = "") {
        results += Recorded(result, ceiling, why)
        println("  $result" + (ceiling?.let { "   techo %.0f ms".format(it) } ?: ""))
        // Después de apuntarlo: un techo roto tiene que dejar su cifra en el informe.
        if (ceiling != null) {
            assertTrue(
                "${result.name}: p99 %.2f ms por encima del techo de %.0f ms ($why)".format(result.p99Millis, ceiling),
                result.p99Millis <= ceiling,
            )
        }
    }

    /**
     * Un techo de memoria (§6.3). Como [record], pero la cifra es megabytes retenidos y no
     * milisegundos; va al mismo CSV con la columna de heap.
     */
    private fun heap(name: String, megabytes: Double, ceiling: Double, why: String) {
        val result = Bench.Result(name, 1, 0.0, 0.0, 0.0, megabytes)
        results += Recorded(result, ceiling, why, memory = true)
        println("  %-46s heap %8.1f MB   techo %.0f MB".format(name, megabytes, ceiling))
        assertTrue("$name: %.1f MB por encima del techo de %.0f MB ($why)".format(megabytes, ceiling), megabytes <= ceiling)
    }

    /** Una cifra con su techo, para el CSV de la CI nocturna. */
    private class Recorded(val result: Bench.Result, val ceiling: Double?, val why: String, val memory: Boolean = false) {
        val ok: Boolean get() = ceiling == null || (if (memory) result.heapMegabytes else result.p99Millis) <= ceiling
    }

    companion object {
        /** El tamaño del corpus. `-PbenchN=100000`. */
        val n: Int = System.getProperty("tasklane.bench.n")?.toIntOrNull() ?: 10_000

        /**
         * Cuántos blobs para las puertas de la Fase 4. `-PbenchBlobs=1000000`.
         *
         * Aparte de [n] porque son dos ejes distintos y cuestan cosas distintas: un
         * millón de tareas son 10 GB de base, y un millón de blobs a 400 px son 33 GB de
         * PNG. Medir los dos a la vez es una noche de disco, y ninguna de las dos puertas
         * necesita a la otra.
         */
        val blobs: Int = System.getProperty("tasklane.bench.blobs")?.toIntOrNull() ?: 2_000

        /** Las imágenes de una tarjeta desplegada, según la especificación del §1.1. */
        const val CARD_IMAGES = 10

        /** El lado de una captura de un portátil con pantalla de alta densidad. */
        const val SCREENSHOT_SIZE = 2880

        /**
         * Lo descodificado, retenido a propósito: sin esto el GC se lleva las imágenes
         * antes de que [Bench] pueda pesar el heap, y lo que se mediría sería cero.
         */
        private var held: List<java.awt.image.BufferedImage> = emptyList()

        private val results = mutableListOf<Recorded>()

        // ------------------------------------------------ los techos (§6.3)
        //
        // **Presupuestos del plan, no medidas de hoy.** Un techo sacado de la cifra de esta
        // máquina saltaría en un runner más lento sin que nada hubiera empeorado; uno sacado
        // de la puerta de cada fase salta cuando el plugin deja de cumplir lo que prometió,
        // que es lo que tiene que romper el build. Todos sobre el p99, no sobre la media: lo
        // que congela una ventana es la cola.

        /** Un fotograma a 60 Hz: todo lo que ocurre en el EDT. */
        const val EDT_MILLIS = 16.0

        /** Crear, editar o completar visto desde la interfaz: comando y repintado. */
        const val UI_MILLIS = 50.0

        /** Lo que tarda en escribirse una edición. */
        const val WRITE_MILLIS = 20.0

        /** Abrir en frío: contadores y cabeceras, fuera del EDT. */
        const val OPEN_MILLIS = 300.0

        /** Una tecla en el buscador hasta tener resultados. */
        const val SEARCH_MILLIS = 100.0

        /**
         * El término que está en casi todas las tareas. Crece con los aciertos (§3-bis.5), así
         * que no puede tener el techo de la puerta: tiene uno **por tarea**, diez veces lo
         * medido —0,5 µs por tarea a 100.000—, con un suelo de un segundo.
         */
        val searchWorstMillis: Double get() = maxOf(1_000.0, n * SEARCH_WORST_MICROS_PER_TASK / 1_000.0)

        const val SEARCH_WORST_MICROS_PER_TASK = 5.0

        /** El heap del plugin, §2.6. */
        const val PLUGIN_HEAP_MB = 150.0

        /** El pico de heap migrando, puerta de la Fase 3. */
        const val MIGRATION_HEAP_MB = 300.0

        /** Cuántos comandos aplica la prueba de longevidad. `-PbenchCommands=100000`. */
        val commands: Int = System.getProperty("tasklane.bench.commands")?.toIntOrNull() ?: 100_000

        const val LONGEVITY_VICTIMS = 500

        /** Lo que puede moverse el heap retenido entre muestras sin que sea una fuga: ruido del GC y cachés con tope. */
        const val LONGEVITY_HEAP_GROWTH_MB = 16.0

        /** Cuatro volcados automáticos del diario: más, y algo lo está sujetando. */
        const val LONGEVITY_WAL_MB = 32.0

        private var cached: List<Task>? = null
        private var cachedFile: Path? = null
        private var cachedDb: Path? = null
        private var openedDb: TaskDb? = null

        /**
         * El corpus materializado. **Sólo para los escenarios del camino viejo**: a un
         * millón son los ~5,5 GB del §1.2, que es precisamente lo que esta fase borra.
         */
        fun corpus(): List<Task> = cached ?: SyntheticCorpus.tasks(n).also { cached = it }

        /** El `tasks.xml` del camino viejo y de la migración, escrito en streaming. */
        fun corpusFile(): Path = cachedFile ?: run {
            val dir = Files.createTempDirectory("tasklane-bench-xml")
            val file = dir.resolve("tasks.xml")
            val bytes = SyntheticCorpus.writeTasksXml(file, n)
            println("Corpus de $n tareas en XML: ${bytes / (1024 * 1024)} MB")
            cachedFile = file
            file
        }

        /**
         * La base ya poblada, una vez para toda la clase. Poblarla por escenario
         * costaría más que todo lo que se mide junto.
         */
        fun dbFile(): Path = cachedDb ?: run {
            val dir = Files.createTempDirectory("tasklane-bench-db")
            val db = TaskDb.open(dir)!!
            val store = TaskStore(db)
            val config = TasklaneConfig.DEFAULT.normalized()
            var written = 0
            store.bulk {
                val chunk = ArrayList<Task>(TasksXmlReader.CHUNK)
                for (task in SyntheticCorpus.sequence(n)) {
                    chunk += task
                    if (chunk.size >= TasksXmlReader.CHUNK) {
                        store.importBatch(chunk, config)
                        written += chunk.size
                        chunk.clear()
                    }
                }
                if (chunk.isNotEmpty()) {
                    store.importBatch(chunk, config)
                    written += chunk.size
                }
            }
            db.close()
            val file = dir.resolve(TaskDb.FILE_NAME)
            println("Base de $written tareas: ${Files.size(file) / (1024 * 1024)} MB")
            cachedDb = file
            file
        }

        /** La base abierta, compartida por los escenarios que no miden la apertura. */
        internal fun openDb(): TaskDb = openedDb ?: TaskDb.open(dbFile().parent)!!.also { openedDb = it }

        @BeforeClass
        @JvmStatic
        fun announce() {
            println("\n═══ Banco de escala · n = $n · heap máx = ${Runtime.getRuntime().maxMemory() / (1 shl 20)} MB ═══")
        }

        /**
         * Las cifras de esta ejecución, con su techo, en `build/bench/results-<n>.csv`. Lo que la
         * CI nocturna guarda de cada noche: una fila por medida, para poder ver una tendencia y
         * no sólo un rojo o un verde.
         */
        private fun writeCsv() {
            if (results.isEmpty()) return
            val dir = Path.of(System.getProperty("tasklane.bench.out") ?: "build/bench")
            runCatching {
                Files.createDirectories(dir)
                val file = dir.resolve("results-$n.csv")
                val fresh = !Files.exists(file)
                val stamp = Instant.now().toString()
                val lines = buildList {
                    if (fresh) add("when,n,name,runs,p50_ms,p99_ms,max_ms,heap_mb,ceiling,unit,ok,why")
                    for (r in results) {
                        val x = r.result
                        add(
                            listOf(
                                stamp, n.toString(), "\"" + x.name.replace("\"", "'") + "\"", x.runs.toString(),
                                "%.3f".format(java.util.Locale.ROOT, x.p50Millis), "%.3f".format(java.util.Locale.ROOT, x.p99Millis),
                                "%.3f".format(java.util.Locale.ROOT, x.maxMillis), "%.1f".format(java.util.Locale.ROOT, x.heapMegabytes),
                                r.ceiling?.let { "%.0f".format(java.util.Locale.ROOT, it) }.orEmpty(), if (r.memory) "MB" else "ms",
                                r.ok.toString(), "\"" + r.why.replace("\"", "'") + "\"",
                            ).joinToString(","),
                        )
                    }
                }
                Files.write(file, lines, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                println("Resultados en ${file.toAbsolutePath()}")
            }.onFailure { println("No se pudieron escribir los resultados: $it") }
        }

        @AfterClass
        @JvmStatic
        fun summary() {
            Bench.report("Resumen · n = $n", results.map { it.result })
            writeCsv()
            openedDb?.close()
            cachedFile?.parent?.toFile()?.deleteRecursively()
            cachedDb?.parent?.toFile()?.deleteRecursively()
            cached = null
            cachedFile = null
            cachedDb = null
            openedDb = null
        }
    }
}
