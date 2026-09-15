package com.tasklane.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.attachment.Chore
import com.tasklane.data.config.TasklaneConfigService
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.data.sqlite.Fts5Index
import com.tasklane.data.sqlite.SqlitePager
import com.tasklane.data.sqlite.TaskDb
import com.tasklane.data.sqlite.TaskStore
import com.tasklane.data.sqlite.TasksXmlReader
import com.tasklane.data.store.StorageLayout
import com.tasklane.data.store.TaskFileStore
import com.tasklane.data.store.TasksCodec
import com.tasklane.diagnostics.BlobStats
import com.tasklane.diagnostics.TaskStats
import com.tasklane.diagnostics.TasklaneMetrics
import com.tasklane.domain.command.RepoScoped
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.AnchoredTask
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.paging.MemoryPager
import com.tasklane.search.LinearScanIndex
import com.tasklane.search.TaskSearchIndex
import com.tasklane.paging.TaskPager
import com.tasklane.repo.RepositoryRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Collections

/**
 * Punto único de mutación del modelo y única capa que habla con el almacén.
 *
 * La UI observa [snapshot], envía comandos con [apply] y pide **páginas** con [pager];
 * nunca toca [TaskStore].
 *
 * ## Lo que la Fase 3 cambió aquí
 *
 * 1. **El servicio ya no guarda las tareas.** [snapshot] describe la ventana
 *    —configuración, repositorios, cuál está activo— y lleva un número de revisión que
 *    sube con cada escritura. Las tareas viven en `tasklane.db` y se piden por páginas.
 * 2. **Un comando es una transacción.** Se leen del almacén **sólo las tareas que el
 *    comando nombra**, el reducer planifica, y el almacén aplica. Todo dentro de la
 *    misma transacción, que es lo que hace que leer-modificar-escribir sea atómico sin
 *    el bucle de *compare-and-set* que hacía falta cuando el modelo vivía en un
 *    `StateFlow`.
 * 3. **Desapareció el debounce de 500 ms.** Existía porque cada volcado reescribía el
 *    fichero entero; con WAL, confirmar es escribir las páginas que cambiaron al final
 *    de un fichero secuencial. Quitarlo arregla de paso lo que costaba: hasta medio
 *    segundo de ediciones perdidas si el IDE se caía.
 * 4. **Cargar se convirtió en migrar.** Abrir un repositorio ya no lee su `tasks.xml`:
 *    la primera vez lo **importa** a la base, en segundo plano y con progreso, y a
 *    partir de ahí el fichero sólo se conserva como vuelta atrás.
 */
@Service(Service.Level.PROJECT)
class TaskService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    private val layout = StorageLayout.forProject(project)

    /** El lado XML: exportar al formato de intercambio y borrar el directorio de un repo. */
    private val files = layout?.let(::TaskFileStore)
    /**
     * La base, y **el `.gitignore` antes que ella**.
     *
     * Hasta la Fase 2 el `.gitignore` con `*` lo escribía la primera escritura de un
     * `tasks.xml`; ahora el primer fichero que aparece en `.idea/tasklane/` es
     * `tasklane.db`, y si el marcador no estuviera ya puesto, la base —y su diario, y
     * sus adjuntos— acabarían en el `git status` del usuario.
     */
    private val db: TaskDb? = layout?.let {
        runCatching { it.ensureIgnored() }.onFailure { e ->
            thisLogger().warn("Tasklane: no se pudo escribir el .gitignore de los datos", e)
        }
        TaskDb.open(it.root)
    }
    private val store: TaskStore? = db?.let(::TaskStore)
    private val reducer = TaskReducer()

    /**
     * El índice de búsqueda del proyecto.
     *
     * Lo crea el servicio y no [SearchService] porque quien tiene la conexión es quien
     * tiene la base. Sin base —un *default project*, un test ligero— se cae al escaneo
     * lineal sobre un corpus vacío, que es exactamente «no hay nada que buscar».
     */
    internal val searchIndex: TaskSearchIndex = db?.let { Fts5Index(it.reader) } ?: LinearScanIndex()
    private val configService = TasklaneConfigService.getInstance(project)
    private val registry = RepositoryRegistry.getInstance(project)
    private val workspace = TasklaneWorkspaceService.getInstance(project)

    /**
     * El cronómetro de *Tasklane: Diagnostics*. Se guarda como campo y no se pide en
     * cada comando porque `apply` es el camino caliente: una búsqueda de servicio por
     * pulsación de tecla sería medir con un instrumento que pesa.
     */
    private val metrics = TasklaneMetrics.getInstance(project)

    private val _snapshot = MutableStateFlow(
        TasklaneSnapshot.EMPTY.copy(config = configService.config.value),
    )
    val snapshot: StateFlow<TasklaneSnapshot> = _snapshot.asStateFlow()

    /**
     * Petición de «enséñame estas tareas». La emiten la acción de una notificación y el
     * clic sobre una marca del editor, y la atiende la pestaña que las contenga. Va por
     * flow y no por llamada directa porque el servicio no conoce la UI ni debe conocerla.
     *
     * **`replay = 1` porque la ventana puede no existir todavía.** Sus pestañas se
     * construyen al abrirla por primera vez y se suscriben desde una corrutina, así que
     * una petición hecha justo al abrirla —que es lo que hace el clic de una marca— se
     * perdería entre la construcción y la suscripción.
     */
    private val _reveal = MutableSharedFlow<Set<TaskId>>(replay = 1, extraBufferCapacity = 8)
    val reveal: SharedFlow<Set<TaskId>> = _reveal.asSharedFlow()

    /** Repos cuyo `tasks.xml` viene de una versión futura del formato. */
    private val readOnly: MutableSet<RepoKey> = Collections.synchronizedSet(mutableSetOf())

    /** Repos cuya migración ya se ha lanzado, para no lanzarla dos veces. */
    private val importing: MutableSet<RepoKey> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Un comando cada vez.
     *
     * Hay dos productores reales —el hilo de interfaz y las corrutinas de fondo que
     * recogen la configuración y el catálogo— y cada comando es un leer-modificar-escribir
     * sobre [_snapshot]. Hasta la Fase 2 eso se resolvía con un bucle de *compare-and-set*
     * porque el snapshot **era** el modelo; ahora el modelo lo guarda el almacén, que ya
     * serializa sus transacciones, y lo único que queda por proteger es que dos comandos
     * no se pisen la vista. Un cerrojo dice eso mismo en una línea.
     */
    private val gate = Any()

    init {
        // La selección guardada se restaura ANTES de que llegue el catálogo: así la
        // tool window no llega a pintar el repositorio equivocado.
        workspace.selectedRepo?.let { apply(TaskCommand.SelectRepo(RepoKey(it))) }
        // La configuración entra en el modelo como un comando más. El primer valor
        // que emite el StateFlow es el que ya se sembró arriba, así que es inocuo.
        scope.launch { configService.config.collect { apply(TaskCommand.ConfigChanged(it)) } }
        scope.launch(Dispatchers.IO) {
            registry.repositories.collect { repositories ->
                apply(TaskCommand.RepositoriesChanged(repositories))
                importPending()
            }
        }
    }

    // ------------------------------------------------------------------ API

    fun apply(command: TaskCommand) = metrics.time(TasklaneMetrics.Op.COMMAND) { applyNow(command) }

    private fun applyNow(command: TaskCommand) = synchronized(gate) { applyLocked(command) }

    private fun applyLocked(command: TaskCommand) {
        val before = _snapshot.value
        val view = reducer.view(before, command)
        val store = store

        if (store == null || store.readOnly || isReadOnly(command)) {
            if (view !== before) _snapshot.value = view
            return
        }

        val repo = (command as? RepoScoped)?.repo ?: before.activeRepo

        // Leer el sujeto, planificar y escribir, todo dentro de la misma transacción.
        // Es lo que sustituye al bucle de compare-and-set: la atomicidad la da ahora
        // quien de verdad guarda el dato.
        val applied = metrics.time(TasklaneMetrics.Op.SAVE) {
            runCatching {
                store.write {
                    val subject = TaskReducer.Subject(
                        // La configuración de **antes** del comando, no la de después.
                        // Importa en un solo caso y ahí lo es todo: `ConfigChanged`
                        // devuelve `Renormalize(la anterior)`, que es lo que le permite
                        // al almacén tocar sólo lo que cambió. Pasándole la nueva, el
                        // almacén compararía la configuración consigo misma y no
                        // recolocaría nada.
                        config = before.config,
                        tasks = store.tasks(reducer.targetsOf(command)),
                        nextOrder = { store.nextOrder(repo) },
                    )
                    val plan = reducer.plan(subject, command)
                    if (plan.isEmpty) null else store.apply(plan.config, plan.mutations)
                }
            }.onFailure { report(it) }.getOrNull()
        }

        val touched = applied != null && applied.rows > 0
        _snapshot.value = if (touched) view.touched() else view
        applied?.parked?.takeIf { it.count > 0 }?.let(::reportParked)
    }

    /** Un comando sobre un repositorio abierto en solo lectura no se aplica. */
    private fun isReadOnly(command: TaskCommand): Boolean =
        command is RepoScoped && command.repo in readOnly

    fun isReadOnly(repo: RepoKey): Boolean = repo in readOnly || store?.readOnly == true

    // ------------------------------------------------------------- lectura

    /**
     * De dónde saca la ventana sus filas **en este repintado**.
     *
     * El reparto es la decisión central de la fase: SQL para lo que no cabe —la lista de
     * una pestaña— y Kotlin para lo que está acotado por su propia naturaleza. Ver
     * `SqlitePager` y `MemoryPager`.
     */
    internal fun pager(found: SearchResults, filter: TaskFilter, now: Instant): TaskPager {
        val snapshot = _snapshot.value
        val db = db
        val store = store
        return when {
            db == null || store == null ->
                MemoryPager(emptyList(), snapshot.config, found, filter, now)

            // Buscando, el universo son los doscientos aciertos que devolvió FTS5, y el
            // alcance ya lo aplicó el índice: si el usuario pidió «todos los
            // repositorios», los de fuera del activo tienen que poder salir.
            found.active -> MemoryPager(found.tasks, snapshot.config, found, filter, now)

            filter == TaskFilter.OVERDUE ->
                MemoryPager(store.overdue(snapshot.activeRepo, now), snapshot.config, found, filter, now)

            else -> SqlitePager(db.reader, snapshot.activeRepo, snapshot.config, filter, now)
        }
    }

    /** Una tarea por id. La pide la ventana para saber si una petición de enseñar es suya. */
    fun task(id: TaskId): Task? = store?.task(id)

    /** Todo lo de un repositorio. Sólo la exportación, que es O(n) por definición. */
    fun tasksOf(repo: RepoKey): List<Task> = store?.allOf(repo).orEmpty()

    /** Qué tareas cuelgan de un fichero. Es el §3.6: un salto de índice, no el modelo entero. */
    fun anchorsIn(path: String): List<AnchoredTask> = store?.anchorsIn(path).orEmpty()

    /**
     * Cuáles de estos adjuntos sigue nombrando alguna tarea. Lo pregunta el recolector,
     * por tandas: ver `TaskStore.referencedAmong`.
     */
    fun referencedAmong(repo: RepoKey, ids: List<AttachmentId>): Set<String> =
        store?.referencedAmong(repo, ids).orEmpty()

    // ------------------------------------------------------- adjuntos (Fase 4)

    /**
     * ¿Se puede fiar el recolector de las referencias de este repositorio?
     *
     * Es **la** salvaguarda de la Fase 4, y la que sustituye a la espera con la que
     * `AttachmentGcActivity` se protegía hasta la 2.0: entonces las referencias salían
     * del corpus en memoria y había que esperar a tenerlo cargado; ahora salen de
     * `blob_ref`, que se mantiene en la misma transacción que la escritura de la tarea.
     * Pero hay un momento en que esa tabla está legítimamente incompleta: **mientras el
     * `tasks.xml` se está importando**. Recolectar ahí no encontraría basura,
     * encontraría todas las imágenes del usuario.
     */
    fun referencesComplete(repo: RepoKey): Boolean {
        val store = store ?: return false
        if (store.readOnly || isReadOnly(repo) || repo in importing) return false
        val layout = layout ?: return false
        return store.isImported(repo) || !Files.exists(layout.tasksFile(repo))
    }

    /** Apunta en la tabla un blob recién escrito. Ver `TaskStore.recordBlob`. */
    fun recordBlob(repo: RepoKey, blob: BlobRecord, at: Instant) {
        val store = store ?: return
        if (store.readOnly) return
        runCatching { store.write { store.recordBlob(repo, blob, at.toEpochMilli()) } }
            .onFailure { thisLogger().warn("Tasklane: no se pudo apuntar el adjunto ${blob.id.value}", it) }
    }

    /** Los candidatos a recoger, por tandas. Ver `TaskStore.collectibleBlobs`. */
    fun collectibleBlobs(repo: RepoKey, before: Instant, limit: Int): List<BlobRecord> =
        store?.collectibleBlobs(repo, before.toEpochMilli(), limit).orEmpty()

    fun forgetBlobs(repo: RepoKey, ids: List<AttachmentId>) {
        val store = store ?: return
        if (store.readOnly || ids.isEmpty()) return
        store.write { store.forgetBlobs(repo, ids) }
    }

    /** Cuáles de estos ya conoce la tabla. Lo pregunta la reconciliación. */
    fun knownBlobs(repo: RepoKey, ids: List<AttachmentId>): Set<String> =
        store?.knownBlobs(repo, ids).orEmpty()

    /**
     * Sella lo que el recorrido encontró y adopta lo que no conocía, **en una sola
     * transacción**: una tanda del recorrido es una unidad, y media tanda confirmada
     * dejaría filas selladas junto a filas que no se llegaron a adoptar.
     */
    fun reconcileBlobs(repo: RepoKey, seen: List<AttachmentId>, adopted: List<BlobRecord>, at: Instant) {
        val store = store ?: return
        if (store.readOnly) return
        if (seen.isEmpty() && adopted.isEmpty()) return
        store.write {
            if (seen.isNotEmpty()) store.seeBlobs(repo, seen, at.toEpochMilli())
            if (adopted.isNotEmpty()) store.adoptBlobs(repo, adopted, at.toEpochMilli())
        }
    }

    /** Marca ausentes las filas que el recorrido no volvió a ver. @return cuántas. */
    fun markMissingBlobs(repo: RepoKey, before: Instant): Int {
        val store = store ?: return 0
        if (store.readOnly) return 0
        return store.write { store.markMissingBlobs(repo, before.toEpochMilli()) }
    }

    fun blobStatsOf(repo: RepoKey, maxSize: Int): BlobStats = store?.blobStatsOf(repo, maxSize) ?: BlobStats()

    /** Lo que ocupan los adjuntos del proyecto. Es la cifra que vigila la cuota del §4.5. */
    fun blobBytes(): Long = store?.blobBytes() ?: 0L

    fun chore(name: String): Chore? = store?.chore(name)

    fun saveChore(name: String, at: Instant, n: Long) {
        val store = store ?: return
        if (store.readOnly) return
        store.write { store.saveChore(name, at.toEpochMilli(), n) }
    }

    fun forgetChore(name: String) {
        val store = store ?: return
        if (store.readOnly) return
        store.write { store.forgetChore(name) }
    }

    fun statsOf(repo: RepoKey): TaskStats = store?.statsOf(repo) ?: TaskStats()

    /**
     * Cuántas tareas hay en cada estado, **sumando todos los repositorios**: la
     * configuración es del proyecto, no de la pestaña abierta.
     *
     * Sale de la tabla `counter`, que son cinco filas. Antes era un recorrido del corpus
     * por cada fila de la tabla de estados de los ajustes.
     */
    fun countsByState(): Map<StateId, Int> = store?.countsByState().orEmpty()

    fun countsByPriority(): Map<PriorityId, Int> = store?.countsByPriority().orEmpty()

    /** Cuántas siguen sin cerrar en un estado. Lo pregunta el ofrecimiento de rellenar `completedAt`. */
    fun openCountOf(state: StateId): Int = store?.openCountOf(state) ?: 0

    /**
     * Cambia el repositorio activo y lo recuerda para la próxima apertura. Pasa por
     * aquí y no por el propio `apply` porque persistir la selección es un efecto que
     * sólo tiene sentido cuando la decisión es del usuario, no cuando el reducer
     * mueve el activo porque el suyo desapareció.
     */
    fun selectRepo(repo: RepoKey) {
        if (repo == _snapshot.value.activeRepo) return
        apply(TaskCommand.SelectRepo(repo))
        workspace.selectedRepo = repo.value
    }

    fun requestReveal(ids: Set<TaskId>) {
        if (ids.isNotEmpty()) _reveal.tryEmit(ids)
    }

    /**
     * «Llévame a estas tareas», con el repositorio ya puesto.
     *
     * Es lo que pide una marca del editor al pulsarla: el fichero no sabe de
     * repositorios, así que la tarea que cuelga de él puede ser de una lista que ni
     * siquiera está abierta. Se cambia sólo si **ninguna** de las tareas está en el
     * activo: con una que lo esté, cambiar sería llevarse al usuario de su lista para
     * enseñarle algo que ya podía ver.
     */
    fun revealTasks(tasks: List<Task>) {
        if (tasks.isEmpty()) return
        val active = _snapshot.value.activeRepo
        if (tasks.none { it.repo == active }) selectRepo(tasks.first().repo)
        requestReveal(tasks.map { it.id }.toSet())
    }

    /**
     * Saca un repositorio del modelo y borra sus datos. Es la mitad destructiva de
     * «Exportar y quitar», y quien llama ya ha confirmado con el usuario y ha dejado el
     * texto en el portapapeles.
     */
    fun forgetRepo(repo: RepoKey) {
        importing -= repo
        readOnly -= repo
        apply(TaskCommand.ForgetRepo(repo))
        if (workspace.selectedRepo == repo.value) {
            workspace.selectedRepo = _snapshot.value.activeRepo.value
        }
        scope.launch(Dispatchers.IO) {
            runCatching { files?.delete(repo) }.onFailure { e ->
                thisLogger().warn("Tasklane: no se pudo borrar el directorio de $repo", e)
            }
            // Sin datos en disco, el catalogo deja de conservar la entrada huerfana.
            registry.refresh()
        }
    }

    // --------------------------------------------------------------- migración

    /**
     * Importa a la base los `tasks.xml` que queden, uno por repositorio.
     *
     * **En segundo plano, cancelable y reanudable.** Un fichero de 2,9 GB no se puede
     * meter en un DOM —ver `TasksXmlReader`— ni se puede importar dentro de una sola
     * transacción, así que va a tandas de dos mil tareas; cada tanda se confirma con su
     * propia marca de progreso, y si el IDE se cierra a mitad, la siguiente apertura
     * continúa por donde iba.
     *
     * **El `tasks.xml` no se borra**: al terminar se renombra a `tasks.xml.migrated`.
     * La vuelta atrás es renombrarlo.
     */
    private fun importPending() {
        val store = store ?: return
        val layout = layout ?: return
        if (store.readOnly) return

        val pending = _snapshot.value.repositories
            .map { it.key }
            .filter { it !in importing && !store.isImported(it) && Files.exists(layout.tasksFile(it)) }
        if (pending.isEmpty()) return

        importing.addAll(pending)
        synchronized(gate) { _snapshot.value = _snapshot.value.let { it.copy(loading = it.loading + pending) } }

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("migrate.progress"), true) {
                override fun run(indicator: ProgressIndicator) {
                    for (repo in pending) {
                        // Un repositorio que falle no puede llevarse por delante a los
                        // demás, ni dejar la marca de «importando» puesta para siempre.
                        // Lo ya confirmado sigue confirmado: WAL no deshace tandas.
                        runCatching { importRepo(repo, indicator) }
                            .onFailure { thisLogger().warn("Tasklane: fallo importando $repo", it) }
                    }
                }

                override fun onFinished() {
                    importing.removeAll(pending.toSet())
                    synchronized(gate) {
                        _snapshot.value = _snapshot.value.let { it.copy(loading = it.loading - pending) }.touched()
                    }
                }
            },
        )
    }

    private fun importRepo(repo: RepoKey, indicator: ProgressIndicator) {
        val store = store ?: return
        val layout = layout ?: return
        val file = layout.tasksFile(repo)
        val config = _snapshot.value.config

        indicator.text = TasklaneBundle.message("migrate.repo", repo.value)
        val total = TasksXmlReader.count(file).coerceAtLeast(1)
        var written = store.importedCount(repo)

        val result = metrics.time(TasklaneMetrics.Op.LOAD) {
            store.bulk {
                TasksXmlReader.read(file, repo, skip = written, cancelled = { indicator.isCanceled }) { chunk ->
                    store.write {
                        store.importBatch(chunk, config)
                        written += chunk.size
                        store.markImported(repo, TasksCodec.CURRENT_VERSION, written, false, nowMillis())
                    }
                    indicator.fraction = written.toDouble() / total
                    // La ventana se va llenando mientras se importa: cada tanda
                    // confirmada es una lista un poco más larga, no una pantalla en
                    // blanco de un minuto.
                    synchronized(gate) { _snapshot.value = _snapshot.value.touched() }
                }
            }
        }

        when (result) {
            is TasksXmlReader.Result.Done -> {
                store.write { store.markImported(repo, result.version, written, complete = true, at = nowMillis()) }
                val archived = archive(file)
                // Se avisa a propósito, aunque haya salido bien. Cambiar el formato de
                // los datos de alguien sin decírselo es lo contrario de lo que hace este
                // plugin con los ficheros corruptos, y aquí además hay algo que el
                // usuario necesita saber: dónde quedó el original y que renombrarlo es
                // la vuelta atrás.
                if (written > 0) {
                    notify(
                        TasklaneBundle.message("migrate.done.title"),
                        TasklaneBundle.message("migrate.done.content", written, archived),
                        NotificationType.INFORMATION,
                    )
                }
            }

            is TasksXmlReader.Result.FutureVersion -> {
                // Exactamente lo que hacía `TaskFileStore`: no se toca y se avisa.
                readOnly += repo
                notify(
                    TasklaneBundle.message("notification.readOnly.title"),
                    TasklaneBundle.message("notification.readOnly.content", result.version),
                    NotificationType.WARNING,
                )
            }

            is TasksXmlReader.Result.Broken -> recover(repo, file, written, result)
            is TasksXmlReader.Result.Cancelled -> Unit
        }
    }

    /**
     * El fichero se rompió por el camino. Lo importado hasta ahí ya está confirmado —a
     * diferencia de antes, cuando un XML ilegible dejaba el repositorio entero en
     * blanco—, así que lo que queda es intentar el `.bak` y contarlo.
     */
    private fun recover(repo: RepoKey, file: Path, written: Int, broken: TasksXmlReader.Result.Broken) {
        val store = store ?: return
        val layout = layout ?: return
        thisLogger().warn("Tasklane: $file no se pudo leer entero", broken.cause)

        val backup = layout.backupFile(repo)
        var recovered = 0
        if (written == 0 && Files.exists(backup)) {
            val config = _snapshot.value.config
            TasksXmlReader.read(backup, repo) { chunk ->
                store.write {
                    store.importBatch(chunk, config)
                    recovered += chunk.size
                }
            }
        }
        val quarantined = runCatching {
            val target = layout.corruptFile(repo, nowMillis())
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
            target
        }.getOrNull()
        store.write { store.markImported(repo, TasksCodec.CURRENT_VERSION, written + recovered, true, nowMillis()) }

        val head = if (written + recovered > 0) {
            TasklaneBundle.message("notification.corrupt.recovered", written + recovered)
        } else {
            TasklaneBundle.message("notification.corrupt.lost")
        }
        notify(
            TasklaneBundle.message("notification.corrupt.title"),
            quarantined?.let { "$head ${TasklaneBundle.message("notification.corrupt.quarantined", it)}" } ?: head,
            NotificationType.ERROR,
        )
    }

    /**
     * El original se conserva. La vuelta atrás es quitarle el sufijo.
     *
     * Devuelve dónde quedó, que es lo que se le dice al usuario. Si no se pudo mover
     * —permisos, un volumen de red— se devuelve el sitio de siempre y no se toca nada
     * más: el fichero de más es un problema mucho menor que el fichero de menos.
     */
    private fun archive(file: Path): String = runCatching {
        Files.move(
            file,
            file.resolveSibling("${file.fileName}${StorageLayout.MIGRATED_SUFFIX}"),
            StandardCopyOption.REPLACE_EXISTING,
        ).toString()
    }.onFailure { thisLogger().warn("Tasklane: no se pudo archivar $file", it) }
        .getOrDefault(file.toString())

    private fun nowMillis(): Long = Instant.now().toEpochMilli()

    // ----------------------------------------------- configuracion desincronizada

    /**
     * Avisa de las tareas que se acaban de aparcar en el estado o la prioridad por
     * defecto por apuntar a configuración que ya no existe. No es un error recuperable
     * en silencio: el usuario ve sus tareas en un sitio que no eligió, y merece saber
     * cuántas y cuáles.
     */
    private fun reportParked(parked: TaskStore.Parked) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                TasklaneBundle.message("notification.remapped.title"),
                TasklaneBundle.message("notification.remapped.content", parked.count),
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimple(TasklaneBundle.message("notification.remapped.action")) {
                    requestReveal(parked.ids)
                },
            )
            .notify(project)
    }

    private fun report(error: Throwable) {
        thisLogger().error("Tasklane: fallo al escribir en la base de tareas", error)
        notify(
            TasklaneBundle.message("notification.save.title"),
            TasklaneBundle.message("notification.save.content", error.message.orEmpty()),
            NotificationType.ERROR,
        )
    }

    override fun dispose() {
        db?.close()
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "Tasklane"

        fun getInstance(project: Project): TaskService = project.service()
    }
}
