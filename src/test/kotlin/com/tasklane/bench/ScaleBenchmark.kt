package com.tasklane.bench

import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.CheckedTreeNode
import com.tasklane.data.attachment.AttachmentGc
import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.attachment.ImageNormalizer
import com.tasklane.data.sqlite.SqlitePager
import com.tasklane.data.sqlite.Fts5Index
import com.tasklane.data.sqlite.TaskDb
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
        record(fuera)

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
            record(dentro)
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
            record(
                Bench.measure("buscar · «$query»", runs = 20, warmup = 5) {
                    index.search(QueryParser.parse(query), SearchScope.Repo(repo))
                },
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
        val dir = Files.createTempDirectory("tasklane-bench-migrate")
        try {
            val db = TaskDb.open(dir)!!
            val store = TaskStore(db)
            val baseline = Bench.heapMegabytes()
            var peak = 0.0
            var written = 0

            val result = Bench.measure("migración · StAX + importBatch de $n tareas", runs = 1, warmup = 0) {
                store.bulk {
                    TasksXmlReader.read(source, repo) { chunk ->
                        store.write {
                            store.importBatch(chunk, config)
                            written += chunk.size
                            store.markImported(repo, TasksCodec.CURRENT_VERSION, written, false, 0L)
                        }
                        if (written % 100_000 < TasksXmlReader.CHUNK) {
                            peak = maxOf(peak, Bench.heapMegabytes() - baseline)
                        }
                    }
                }
            }
            record(result)

            assertEquals("tienen que estar todas", n, written)
            println("  pico de heap durante la migración: %.1f MB".format(peak))
            println("  la base pesa: ${Files.size(dir.resolve(TaskDb.FILE_NAME)) / (1024 * 1024)} MB")
            db.close()
        } finally {
            dir.toFile().deleteRecursively()
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
                    pager.all(PageQuery(state, group.key)).forEach { node.add(TaskNode(it, false)) }
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
        record(ahora)
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
        record(result)
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
                assertEquals(blobs, tasks.blobStatsOf(repo, SyntheticBlobs.DEFAULT_SIZE).count)
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

    private fun record(result: Bench.Result) {
        results += result
        println("  $result")
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

        /**
         * Lo descodificado, retenido a propósito: sin esto el GC se lleva las imágenes
         * antes de que [Bench] pueda pesar el heap, y lo que se mediría sería cero.
         */
        private var held: List<java.awt.image.BufferedImage> = emptyList()

        private val results = mutableListOf<Bench.Result>()

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

        @AfterClass
        @JvmStatic
        fun summary() {
            Bench.report("Resumen · n = $n", results)
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
