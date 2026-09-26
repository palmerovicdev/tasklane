package com.tasklane.service

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.StatusBar
import com.tasklane.data.sqlite.StoreRecovery
import com.tasklane.data.store.LoadAlert
import com.tasklane.domain.query.TaskQuery
import com.tasklane.search.ScoredTask
import com.tasklane.search.SearchCorpus
import com.tasklane.search.SearchScope
import java.text.DateFormat
import java.util.Date
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.data.attachment.AttachmentChore
import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.attachment.Chore
import com.tasklane.data.config.TasklaneConfigService
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.data.sqlite.Fts5Index
import com.tasklane.data.sqlite.RepoSnapshot
import com.tasklane.data.sqlite.SqlitePager
import com.tasklane.data.sqlite.StoreMaintenance
import com.tasklane.data.sqlite.TaskDb
import com.tasklane.data.sqlite.TaskImport
import com.tasklane.data.sqlite.TaskStore
import com.tasklane.data.sqlite.TasksXmlReader
import com.tasklane.data.store.StorageLayout
import com.tasklane.data.store.TaskFileStore
import com.tasklane.data.store.TasksCodec
import com.tasklane.diagnostics.BlobStats
import com.tasklane.diagnostics.TaskStats
import com.tasklane.diagnostics.TasklaneMetrics
import com.tasklane.domain.command.Change
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.command.RepoScoped
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.command.TaskReducer
import com.tasklane.domain.model.AnchorMove
import com.tasklane.domain.model.AnchoredTask
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.DueCount
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TagCount
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.paging.MemoryPager
import com.tasklane.paging.PageQuery
import com.tasklane.search.LinearScanIndex
import com.tasklane.search.TaskSearchIndex
import com.tasklane.paging.TaskPager
import com.tasklane.repo.RepositoryRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

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
    private val opening: TaskDb.Opening? = layout?.let {
        runCatching { it.ensureIgnored() }.onFailure { e ->
            thisLogger().warn("Tasklane: no se pudo escribir el .gitignore de los datos", e)
        }
        TaskDb.openChecked(it.root)
    }

    /**
     * La base abierta. **`var` desde la Fase 6**: una base dañada no se abre, se recupera en
     * segundo plano —ver [recoverStore]— y se pone aquí cuando está entera. Hasta entonces el
     * servicio funciona como sin almacén y en solo lectura. Se asigna una sola vez; quien la
     * usa la copia a una variable local, como ya hacía.
     */
    @Volatile
    private var db: TaskDb? = (opening as? TaskDb.Opening.Ready)?.db

    @Volatile
    private var store: TaskStore? = db?.let(::TaskStore)

    /** La base se está recuperando (§6.2): nada se escribe hasta que termine. */
    @Volatile
    private var recovering: Boolean = opening is TaskDb.Opening.Damaged

    /**
     * Se encontró daño **con la base abierta** —la comprobación tras un cierre sucio, o un
     * `SQLITE_CORRUPT` al escribir—. Desde ese momento no se escribe: lo que se escribiera en
     * un fichero dañado se perdería con él, y la recuperación de la apertura siguiente rescata
     * lo que haya hasta aquí. Ver [markDamaged].
     */
    @Volatile
    private var damaged: Boolean = false

    private val reducer = TaskReducer()

    /**
     * El índice de búsqueda del proyecto.
     *
     * Lo crea el servicio y no [SearchService] porque quien tiene la conexión es quien
     * tiene la base. Sin base —un *default project*, un test ligero— se cae al escaneo
     * lineal sobre un corpus vacío, que es exactamente «no hay nada que buscar».
     */
    private val index = LateIndex(db?.let { Fts5Index(it.reader) } ?: LinearScanIndex())
    internal val searchIndex: TaskSearchIndex get() = index
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

    /**
     * Las rutas de las anclas que acaba de escribir un comando (2.13.0). Las escucha
     * `AnchorFiles` para comprobar en el disco **sólo eso**, y no todas las rutas ancladas
     * del proyecto después de cada edición.
     *
     * Casi siempre son rutas que ya se sabía que existían —capturar, escribir o pedir un
     * ancla comprueba el fichero—. La que no es una tarea devuelta con *Undo*, que trae
     * las anclas de cuando se borró. Sin límite de búfer: perder un aviso sería dejar sin
     * marcar un ancla rota, y cada uno son un puñado de rutas.
     */
    private val _anchorsWritten = MutableSharedFlow<Set<String>>(extraBufferCapacity = Channel.UNLIMITED)
    internal val anchorsWritten: SharedFlow<Set<String>> = _anchorsWritten.asSharedFlow()

    /**
     * Repos en los que no se escribe: su `tasks.xml` viene de una versión futura del
     * formato, o se están exportando para quitarlos. Ver [beginRemoval].
     */
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
        // La sesión anterior no cerró la base: se apunta que hay que comprobarla, y lo
        // hace el mantenimiento en segundo plano. Apuntado en la base y no en memoria
        // para que una comprobación que no llegue a terminar se repita.
        val store = store
        if (db?.dirty == true && store != null && !store.readOnly) {
            runCatching { store.write { store.saveChore(StoreMaintenance.INTEGRITY_PENDING, nowMillis(), 0) } }
                .onFailure { thisLogger().warn("Tasklane: no se pudo apuntar la comprobación de la base", it) }
        }
        // La selección guardada se restaura ANTES de que llegue el catálogo: así la
        // tool window no llega a pintar el repositorio equivocado.
        workspace.selectedRepo?.let { apply(TaskCommand.SelectRepo(RepoKey(it))) }
        // Una base dañada se recupera antes de nada, en segundo plano. Ver [recoverStore].
        if (recovering) scope.launch(Dispatchers.IO) { recoverStore() }
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

    /**
     * Aplica un comando. Si es un gesto sobre las tareas —de la lista, del diálogo, de crear
     * desde el editor—, queda en la pila de `⌘Z` (2.16.0); lo que no toca filas, como la
     * configuración o el repositorio activo, no.
     *
     * [undoable] `false` es para quien escribe sin ser el usuario: las herramientas MCP. Lo
     * que hace un agente se ve en la lista, pero no entra en la pila del usuario. Si
     * entrara, `⌘Z` para deshacer lo que uno acaba de mover desharía lo último que tocó el
     * agente por detrás. Deshacer lo propio respeta lo del agente: ver
     * [TaskCommand.Revert].
     */
    fun apply(command: TaskCommand, undoable: Boolean = true) = metrics.time(TasklaneMetrics.Op.COMMAND) {
        synchronized(gate) { applyLocked(command, onChange = if (undoable) recorder(command) else null) }
    }

    /**
     * La misma edición sobre varias tareas: **una** transacción y **un** snapshot.
     *
     * Es la operación masiva de la Fase 5. Hasta la 2.1 la pestaña mandaba un comando por
     * fila seleccionada, y cada uno era su transacción y su repintado. Ahora:
     *
     * - **Una sola**, tal cual: es el camino de siempre y no hay nada que componer.
     * - **Hasta [BULK_INLINE] filas**, en el acto: una transacción así cabe en el gesto.
     * - **Más**, en segundo plano, con barra y cancelable. Cancelar **deshace**: la
     *   transacción es una, y lo que un «cancelar» significa en una selección es que no
     *   quede la mitad movida.
     *
     * Lo que se aplica es [TaskCommand.Batch], así que la semántica es la de los comandos
     * sueltos por construcción.
     */
    fun applyAll(commands: List<RepoScoped>) = applyAll(commands) { command -> recorder(command) }

    /**
     * Borra estas tareas y ofrece **deshacerlo** (2.9.0): un aviso con *Undo*, y `⌘Z` en
     * la lista. No pregunta antes, a propósito: una confirmación estorba en el caso normal
     * —se borra lo que se quería borrar— y no salva el otro, porque se acepta sin leerla.
     * Deshacer después cuesta lo mismo y sólo se paga cuando hace falta.
     *
     * Un `Delete` por fila y no uno por repositorio, por lo que dice [applyAll]: así el
     * lote cuenta filas, que es lo que decide si cabe en el gesto o va en segundo plano.
     */
    fun delete(tasks: List<Task>) {
        applyAll(tasks.map { TaskCommand.Delete(it.repo, listOf(it.id)) }) { command ->
            { change -> record(command, change)?.let(::offerUndo) ?: notifyForGood(change) }
        }
    }

    /** @param onChange qué hacer con lo que cambió el comando que al final se aplica. */
    private fun applyAll(commands: List<RepoScoped>, onChange: (TaskCommand) -> ((Change) -> Unit)?) {
        when {
            commands.isEmpty() -> return
            commands.size <= BULK_INLINE -> metrics.time(TasklaneMetrics.Op.COMMAND) {
                val command = commands.singleOrNull() ?: TaskCommand.Batch(commands)
                synchronized(gate) { applyLocked(command, onChange = onChange(command)) }
            }
            else -> ProgressManager.getInstance().run(
                object : ProgressTask.Backgroundable(
                    project,
                    TasklaneBundle.message("bulk.progress", commands.size),
                    true,
                ) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = false
                        metrics.time(TasklaneMetrics.Op.COMMAND) {
                            synchronized(gate) {
                                // La caché grande: una transacción de miles de filas
                                // repartidas por todos los índices. Medido sobre 100.000
                                // tareas, dos mil filas pasan de 965 ms a 739.
                                val command = TaskCommand.Batch(commands)
                                inBulk {
                                    applyLocked(
                                        command,
                                        { done, total ->
                                            indicator.checkCanceled()
                                            indicator.fraction = done.toDouble() / total.coerceAtLeast(1)
                                        },
                                        onChange(command),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    /**
     * Borra todas las tareas que pertenecen al grupo que la lista acaba de mostrar.
     *
     * La cabecera sólo tiene el agregado y el árbol sólo conserva una página, así que
     * no se puede convertir el grupo en una selección de nodos: [TaskPager.each] lo lee
     * entero sobre una foto y lo entrega a tandas. La búsqueda y el filtro forman parte
     * de ese paginador, por lo que se borra exactamente lo que el grupo representa en
     * la ventana, no tareas que el usuario no está viendo.
     *
     * Cada tanda es una transacción independiente y la operación no es cancelable. Una
     * cancelación a mitad dejaría un grupo parcialmente borrado; es la misma regla que
     * [clearRepo], donde se acepta el coste de varias transacciones para no tomar el
     * escritor durante minutos.
     */
    internal fun deleteGroup(pager: TaskPager, query: PageQuery, total: Int) {
        if (total <= 0) return
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(
                project,
                TasklaneBundle.message("bulk.delete.progress", total),
                false,
            ) {
                /**
                 * Lo borrado, para poder deshacerlo. Hasta [UNDO_LIMIT] tareas: un grupo de
                 * cien mil guardado entero en memoria para un *Undo* que casi nadie pulsa no
                 * compensa, y pasado el tope el aviso dice que no se puede deshacer.
                 */
                private var gone = Change.NONE
                private var overflow = false
                private val repos = LinkedHashSet<RepoKey>()

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    var removed = 0
                    pager.snapshot { frozen ->
                        // Una selección que busca en todos los repositorios puede mezclar
                        // filas de repositorios en solo lectura. Se hace la comprobación
                        // antes de borrar la primera tanda para no dejarla a medias.
                        frozen.each(query, TaskStore.FORGET_BATCH) { tasks ->
                            tasks.forEach { repos += it.repo }
                        }
                        if (repos.any(::isReadOnly)) return@snapshot

                        frozen.each(query, TaskStore.FORGET_BATCH) { tasks ->
                            if (tasks.isEmpty()) return@each
                            val commands = tasks
                                .groupBy { it.repo }
                                .map { (repo, inRepo) ->
                                    TaskCommand.Delete(repo, inRepo.map { it.id })
                                }
                            val before = removed
                            metrics.time(TasklaneMetrics.Op.COMMAND) {
                                synchronized(gate) {
                                    inBulk {
                                        applyLocked(
                                            TaskCommand.Batch(commands),
                                            { done, _ ->
                                                indicator.fraction =
                                                    (before + done).toDouble() / total.coerceAtLeast(1)
                                            },
                                        ) { batch ->
                                            if (overflow || gone.size + batch.size > UNDO_LIMIT) {
                                                overflow = true
                                                gone = Change.NONE
                                            } else {
                                                gone += batch
                                            }
                                        }
                                    }
                                }
                            }
                            removed += tasks.size
                            indicator.fraction = removed.toDouble() / total.coerceAtLeast(1)
                        }
                    }
                }

                override fun onFinished() {
                    if (overflow) {
                        history.forget(repos + _snapshot.value.activeRepo)
                        notifyForGood(total, deleted = true)
                    } else if (!gone.isEmpty) {
                        record(null, gone)?.let(::offerUndo)
                    }
                }
            },
        )
    }

    // ------------------------------------------------------- deshacer y rehacer (2.16.0)

    /** Ver [UndoHistory]. */
    private val history = UndoHistory()

    /**
     * El aviso del último borrado, con su *Undo*. Uno a la vez: el de un borrado nuevo
     * sustituye al anterior, porque dos avisos con *Undo* dejarían en el aire cuál devuelve
     * qué. El borrado de antes sigue en la pila de `⌘Z`.
     */
    private class Notice(val step: UndoHistory.Step, val notification: Notification)

    private val notice = AtomicReference<Notice?>()

    /** Si `⌘Z` en la lista tiene algo que deshacer en [repo]. */
    fun canUndo(repo: RepoKey): Boolean = !isReadOnly(repo) && history.canUndo(repo)

    /** Si `⌘⇧Z` en la lista tiene algo que rehacer en [repo]. */
    fun canRedo(repo: RepoKey): Boolean = !isReadOnly(repo) && history.canRedo(repo)

    /** Deshace lo último que se hizo en [repo]. Lo pide `⌘Z` en la lista. */
    fun undo(repo: RepoKey) {
        if (isReadOnly(repo)) return
        history.popUndo(repo)?.let { revert(it, redoing = false) }
    }

    /** Rehace lo último que se deshizo en [repo]. Lo pide `⌘⇧Z` en la lista. */
    fun redo(repo: RepoKey) {
        if (isReadOnly(repo)) return
        history.popRedo(repo)?.let { revert(it, redoing = true) }
    }

    /**
     * Qué hacer con lo que cambie [command]: guardarlo en la pila, si es un gesto sobre las
     * tareas. Crear, cambiar y borrar lo son. La configuración, el catálogo, reasignar un
     * estado desde los ajustes o arrastrar las anclas de un fichero renombrado, no: nadie
     * los hizo desde la lista, y los ajustes tienen su propio *Cancel*.
     */
    private fun recorder(command: TaskCommand): ((Change) -> Unit)? = when (command) {
        is TaskCommand.ForgetRepo -> null
        is RepoScoped, is TaskCommand.Batch -> { change -> if (record(command, change) == null) notifyForGood(change) }
        else -> null
    }

    /**
     * Guarda un gesto. El paso es de los repositorios de sus tareas y del que estaba
     * abierto: ver [UndoHistory].
     *
     * @return el paso, o `null` si era demasiado grande para guardarlo.
     */
    private fun record(command: TaskCommand?, change: Change): UndoHistory.Step? {
        val snapshot = _snapshot.value
        val label = UndoLabels.of(command, change, snapshot.config)
        val text = TasklaneBundle.message(label.key, *label.args.toTypedArray())
        val step = UndoHistory.Step(change, text, change.repos + snapshot.activeRepo)
        return step.takeIf(history::record)
    }

    /**
     * Deshace o rehace [step], y deja en la otra pila lo que hace falta para volver.
     *
     * Con muchas filas, en segundo plano como cualquier lote, y **sin** cancelar: dejar a
     * medias un deshacer sería un segundo cambio que nadie pidió. Lo que se deshizo se dice
     * en la barra de estado, porque puede no estar a la vista: deshacer un *Move To* trae
     * de vuelta tareas que estaban en otra pestaña.
     */
    private fun revert(step: UndoHistory.Step, redoing: Boolean) {
        notice.get()?.takeIf { it.step === step }?.let { if (notice.compareAndSet(it, null)) it.notification.expire() }
        val command = TaskCommand.Revert(step.change)
        var reverted = false
        val keep: (Change) -> Unit = { inverse ->
            reverted = true
            val back = UndoHistory.Step(inverse, step.label, step.repos)
            if (redoing) history.redone(back) else history.undone(back)
        }
        if (step.change.size <= BULK_INLINE) {
            metrics.time(TasklaneMetrics.Op.COMMAND) { synchronized(gate) { applyLocked(command, onChange = keep) } }
            tell(step.label, redoing, reverted)
            return
        }
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(
                project,
                TasklaneBundle.message(if (redoing) "redo.progress" else "undo.progress", step.change.size),
                false,
            ) {
                override fun run(indicator: ProgressIndicator) {
                    metrics.time(TasklaneMetrics.Op.COMMAND) {
                        synchronized(gate) { inBulk { applyLocked(command, onChange = keep) } }
                    }
                }

                override fun onFinished() = tell(step.label, redoing, reverted)
            },
        )
    }

    /**
     * «Undone: move 3 tasks to Done», en la barra de estado. Si no quedaba nada que
     * deshacer —las tareas cambiaron o se borraron después—, se dice también: un `⌘Z` que
     * no hace nada visible y no dice nada parece roto.
     */
    private fun tell(label: String, redoing: Boolean, reverted: Boolean) {
        val key = when {
            reverted && redoing -> "redo.done"
            reverted -> "undo.done"
            redoing -> "redo.stale"
            else -> "undo.stale"
        }
        val text = TasklaneBundle.message(key, label)
        ApplicationManager.getApplication().invokeLater({ StatusBar.Info.set(text, project) }, project.disposed)
    }

    /**
     * El aviso de un borrado, con *Undo*. Deshace **ese** borrado, aunque después se hayan
     * hecho otras cosas: lo que se hizo después no se toca, porque deshacer va campo a campo
     * y un borrado sólo devuelve lo que no está.
     *
     * Dice de qué repositorio salieron cuando son de uno solo, que es casi siempre: todo
     * Tasklane habla del repositorio activo, y un aviso que no lo nombra obliga a adivinar.
     */
    private fun offerUndo(step: UndoHistory.Step) {
        val count = step.change.rows.size
        val content = step.change.repos.singleOrNull()?.let { repo ->
            TasklaneBundle.message("undo.deleted.repo", count, displayName(repo))
        } ?: TasklaneBundle.message("undo.deleted", count)

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(content, NotificationType.INFORMATION)
        notification.addAction(
            NotificationAction.createSimpleExpiring(TasklaneBundle.message("undo.action")) {
                if (history.take(step)) revert(step, redoing = false)
            },
        )
        notice.getAndSet(Notice(step, notification))?.notification?.expire()
        notification.notify(project)
    }

    /** Un gesto demasiado grande para guardarlo: se dice, en vez de callar que no hay vuelta. */
    private fun notifyForGood(change: Change) = notifyForGood(change.size, deleted = change.rows.all { it.after == null })

    private fun notifyForGood(count: Int, deleted: Boolean) {
        notice.getAndSet(null)?.notification?.expire()
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                TasklaneBundle.message(if (deleted) "undo.tooMany" else "undo.tooMany.changed", count, UNDO_LIMIT),
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    private fun displayName(repo: RepoKey): String =
        _snapshot.value.repositories.firstOrNull { it.key == repo }?.displayName ?: repo.value

    private fun <T> inBulk(block: () -> T): T = store?.bulk(block = block) ?: block()

    /**
     * @param onChange recibe, **tras confirmarse la transacción**, lo que el comando cambió:
     *   cada tarea tocada como estaba y como quedó. Es lo que permite deshacerlo (2.16.0; el
     *   borrado, desde la 2.9.0). Lo de antes se lee dentro de la misma transacción que lo
     *   escribe, así que no hay una versión intermedia de la tarea que pueda colarse entre
     *   leerla y cambiarla. `null` es no guardar nada, y entonces no cuesta nada.
     */
    private fun applyLocked(
        command: TaskCommand,
        progress: (Int, Int) -> Unit = { _, _ -> },
        onChange: ((Change) -> Unit)? = null,
    ) {
        val before = _snapshot.value
        val view = reducer.view(before, command)
        val store = store

        if (store == null || store.readOnly || recovering || damaged || isReadOnly(command)) {
            if (view !== before) _snapshot.value = view
            return
        }

        val repo = (command as? RepoScoped)?.repo ?: before.activeRepo
        var change = Change.NONE
        var anchored: Set<String> = emptySet()

        // Leer el sujeto, planificar y escribir, todo dentro de la misma transacción.
        // Es lo que sustituye al bucle de compare-and-set: la atomicidad la da ahora
        // quien de verdad guarda el dato.
        val applied = metrics.time(TasklaneMetrics.Op.SAVE) {
            try {
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
                    if (onChange != null) {
                        // Dónde estaba lo que se va a reordenar, antes de moverlo. Ver
                        // `TaskStore.neighbours`: después ya no se sabe.
                        val moves = plan.mutations.filterIsInstance<Mutation.Place>().mapNotNull { place ->
                            store.neighbours(place.id)?.let { (above, below) ->
                                TaskCommand.Move(place.repo, place.id, above, below)
                            }
                        }
                        change = Change.of(subject.tasks, plan.mutations, moves)
                    }
                    anchored = plan.mutations.asSequence()
                        .filterIsInstance<Mutation.Upsert>()
                        .flatMap { it.tasks }
                        .flatMap { it.anchors }
                        .mapTo(HashSet()) { it.path }
                    if (plan.isEmpty) null else store.apply(plan.config, plan.mutations, progress)
                }
            } catch (e: Exception) {
                // Cancelar una operación masiva no es un fallo al guardar: la transacción
                // ya se deshizo y la barra ya dice que se canceló. Avisar de un error aquí
                // sería decirle al usuario que se perdió algo que él mismo pidió deshacer.
                if (e is ControlFlowException || e is CancellationException) throw e
                report(e)
                null
            }
        }

        val touched = applied != null && applied.rows > 0
        _snapshot.value = if (touched) view.touched() else view
        applied?.parked?.takeIf { it.count > 0 }?.let(::reportParked)
        if (touched && !change.isEmpty) onChange?.invoke(change)
        if (applied != null && anchored.isNotEmpty()) _anchorsWritten.tryEmit(anchored)
    }

    /** Un comando sobre un repositorio abierto en solo lectura no se aplica. */
    private fun isReadOnly(command: TaskCommand): Boolean = when (command) {
        is RepoScoped -> command.repo in readOnly
        // Un lote con **una** fila de un repositorio en solo lectura no se aplica entero:
        // aplicar el resto dejaría la selección a medias sin decir por qué.
        is TaskCommand.Batch -> command.commands.any { it.repo in readOnly }
        is TaskCommand.Revert -> command.change.repos.any { it in readOnly }
        else -> false
    }

    fun isReadOnly(repo: RepoKey): Boolean = repo in readOnly || store?.readOnly == true || recovering || damaged

    // ------------------------------------------------------------- lectura

    /**
     * De dónde saca la ventana sus filas **en este repintado**.
     *
     * El reparto es la decisión central de la fase: SQL para lo que no cabe —la lista de
     * una pestaña— y Kotlin para lo que está acotado por su propia naturaleza. Ver
     * `SqlitePager` y `MemoryPager`.
     */
    internal fun pager(found: SearchResults, filter: TaskFilter, now: Instant, archivedBefore: Instant? = null): TaskPager {
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

            // `detach` es lo que deja a la exportación congelar la lista: ver
            // `TaskPager.snapshot`.
            // El archivo de lo terminado sólo recorta la lista: buscando se ve todo —es la
            // forma de llegar a lo archivado— y las vencidas nunca están cerradas.
            else -> SqlitePager(
                db.reader,
                snapshot.activeRepo,
                snapshot.config,
                filter,
                now,
                detach = db::openReader,
                archivedBefore = archivedBefore,
            )
        }
    }

    /**
     * Las tareas abiertas de [repo] que vencen antes de [until], las ya vencidas primero.
     * Lo pide el aviso de vencimientos —ver [DueReminders]— con [until] en el final de hoy.
     */
    fun dueBy(repo: RepoKey, until: Instant): List<Task> = store?.overdue(repo, until).orEmpty()

    /** Cuántas de [repo] están vencidas y cuándo vence la siguiente. Ver [StatusCounts]. */
    fun dueCount(repo: RepoKey, now: Instant): DueCount = store?.dueCount(repo, now) ?: DueCount(0, null)

    /**
     * Cuántas tareas hay en cada estado de [repo], como las cuentan las pestañas con el
     * filtro *All tasks*: de `counter` —una fila por estado—, y con el archivo de lo
     * terminado aplicado si [archivedBefore] lo dice. Lo pide la barra de estado.
     */
    internal fun stateCounts(repo: RepoKey, now: Instant, archivedBefore: Instant?): Map<StateId, Int> {
        val db = db ?: return emptyMap()
        return SqlitePager(db.reader, repo, _snapshot.value.config, TaskFilter.ALL, now, archivedBefore = archivedBefore)
            .counts()
    }

    /**
     * Las primeras [limit] tareas de [stateId] en [repo], en el orden en que las pinta la
     * lista —marcadas arriba, luego prioridad y fecha, o el orden manual—, y cuántas hay en
     * total en ese estado.
     *
     * Es lo que piden las herramientas MCP (2.12.0): un agente que pregunta «qué hay por
     * hacer» tiene que ver lo mismo que el usuario ve arriba de su lista, y de cualquier
     * repositorio, no sólo del que está abierto en la ventana. Sin archivo ni filtro de
     * vista: son preferencias de la ventana, no de los datos.
     */
    internal fun listed(repo: RepoKey, stateId: StateId, limit: Int, now: Instant): Pair<List<Task>, Int> {
        val db = db ?: return emptyList<Task>() to 0
        val pager = SqlitePager(db.reader, repo, _snapshot.value.config, TaskFilter.ALL, now)
        val total = pager.counts()[stateId] ?: 0
        if (total == 0 || limit <= 0) return emptyList<Task>() to total
        return pager.page(PageQuery(stateId, limit = limit)).items.take(limit) to total
    }

    /** Una tarea por id. La pide la ventana para saber si una petición de enseñar es suya. */
    fun task(id: TaskId): Task? = store?.task(id)

    /**
     * ¿Le quedan tareas a este repositorio en la base?
     *
     * Lo pregunta el catálogo para decidir si un repositorio que ya no se detecta sigue
     * mereciendo un sitio en el selector. Hasta la 2.1 lo decidía mirando si existía su
     * `tasks.xml`, y desde la Fase 3 ese fichero se renombra a `.migrated` al importarlo:
     * un repositorio renombrado o borrado del disco **desaparecía del selector con sus
     * tareas dentro**, y con él la única puerta —«Exportar y quitar»— para sacarlas.
     */
    fun hasTasks(repo: RepoKey): Boolean = store?.hasTasks(repo) == true

    /** Cuántas tareas tiene un repositorio. De `counter`: cinco filas, no un recorrido. */
    fun countOf(repo: RepoKey): Int = store?.countOf(repo) ?: 0

    /**
     * Un repositorio entero, congelado, para sacarlo de la base. Ver [RepoSnapshot].
     *
     * @return `null` si no hay base.
     */
    internal fun <T> snapshotOf(repo: RepoKey, block: (RepoSnapshot) -> T): T? {
        val db = db ?: return null
        return RepoSnapshot.open(db, repo, _snapshot.value.config, block)
    }

    /**
     * Apunta que el `tasks.xml` de un repositorio ya está dentro de la base.
     *
     * Lo pide la exportación a XML, y arregla algo que la 2.0 dejó roto: exportar un
     * repositorio **creado después de la migración** —que no tiene fila en `imported`—
     * dejaba un `tasks.xml` que la siguiente detección de repositorios tomaba por uno
     * pendiente de importar. Importarlo chocaba con las tareas que ya estaban, el
     * fichero acababa en cuarentena como corrupto y el usuario recibía un error por
     * haber exportado.
     */
    fun markExported(repo: RepoKey, tasks: Int) {
        val store = store ?: return
        if (store.readOnly) return
        runCatching {
            store.write {
                if (!store.isImported(repo)) {
                    store.markImported(repo, TasksCodec.CURRENT_VERSION, tasks, complete = true, at = nowMillis())
                }
            }
        }.onFailure { thisLogger().warn("Tasklane: no se pudo apuntar la exportación de $repo", it) }
    }

    /** Qué tareas cuelgan de un fichero. Es el §3.6: un salto de índice, no el modelo entero. */
    fun anchorsIn(path: String): List<AnchoredTask> = store?.anchorsIn(path).orEmpty()

    /**
     * Las anclas se van con el fichero que se movió o se renombró (2.13.0). Lo llama
     * `AnchorFiles`, fuera del EDT.
     *
     * Las tareas se buscan aquí y no en el reducer porque para eso hace falta el índice:
     * el reducer sólo recibe las tareas que el comando nombra. Las de un repositorio en
     * solo lectura se quedan como están, igual que con cualquier otro comando.
     */
    fun relinkAnchors(moves: List<AnchorMove>) {
        if (moves.isEmpty()) return
        val store = store ?: return
        val ids = store.anchoredUnder(moves.map { it.from })
            .filter { (_, repo) -> repo !in readOnly }
            .map { it.first }
        if (ids.isNotEmpty()) apply(TaskCommand.RelinkAnchors(ids, moves))
    }

    /**
     * Las rutas ancladas distintas —todas, o las que caen en [under]—, o `null` si todavía
     * no hay base que preguntar: se está recuperando, y quien pregunta tiene que volver a
     * hacerlo cuando esté. Ver `TaskStore.anchorPaths`.
     */
    fun anchorPaths(under: Collection<String>? = null): List<String>? = store?.anchorPaths(under)

    /** Ver [TasklaneSnapshot.brokenAnchors]. Lo escribe `AnchorFiles`. */
    fun setBrokenAnchors(paths: Set<String>) {
        synchronized(gate) {
            val current = _snapshot.value
            if (current.brokenAnchors != paths) _snapshot.value = current.copy(brokenAnchors = paths)
        }
    }

    /**
     * Cuáles de estos adjuntos sigue nombrando alguna tarea. Lo pregunta el recolector,
     * por tandas: ver `TaskStore.referencedAmong`.
     */
    fun referencedAmong(repo: RepoKey, ids: List<AttachmentId>): Set<String> =
        store?.referencedAmong(repo, ids).orEmpty()

    /**
     * ¿Hay un `tasks.xml` de este repositorio que todavía no está entero dentro de la base?
     *
     * Mientras lo haya, lo que hay en la base es **una parte** del repositorio: exportarlo
     * a XML escribiría encima del original con esa parte, y quitarlo se llevaría por
     * delante lo que no llegó a importarse.
     */
    fun migrationPending(repo: RepoKey): Boolean {
        val store = store ?: return false
        val layout = layout ?: return false
        return repo in importing || (!store.isImported(repo) && Files.exists(layout.tasksFile(repo)))
    }

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
        if (recoveryHolds(store, layout)) return false
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

    fun blobStatsOf(repo: RepoKey): BlobStats = store?.blobStatsOf(repo) ?: BlobStats()

    /**
     * Una tanda de filas de `blob` de un repositorio, fuera. Es la mitad de base de «borrar
     * todas las imágenes»: ver `BlobSweeper.deleteAll`.
     *
     * @return cuántas; cero es que ya no queda ninguna.
     */
    fun forgetBlobBatch(repo: RepoKey): Int {
        val store = store ?: return 0
        if (store.readOnly) return 0
        return store.forgetBlobRows(repo)
    }

    /**
     * Pide a la ventana que se vuelva a pintar sin que haya cambiado ninguna tarea.
     *
     * Lo necesita quien cambia lo que las tarjetas **enseñan** sin pasar por un comando:
     * borrar las imágenes de un repositorio deja las mismas tareas apuntando a ficheros
     * que ya no están, y la lista tiene que enterarse para pintar el hueco.
     */
    fun refreshView() {
        synchronized(gate) { _snapshot.value = _snapshot.value.touched() }
    }

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

    fun tagCounts(repo: RepoKey): List<TagCount> = store?.tagCounts(repo).orEmpty()

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

    // ------------------------------------------------------ quitar un repositorio

    /**
     * Pone un repositorio en solo lectura mientras se exporta para quitarlo.
     *
     * Es la garantía de «Exportar y quitar»: lo que se borra es **exactamente** lo que se
     * exportó. Sin esto, una tarea creada en ese repositorio mientras se escribe el
     * fichero —son minutos con un millón— se borraría sin haber salido. La pestaña ve el
     * repositorio en solo lectura y apaga lo que escribe, que es lo que tiene que ver.
     *
     * @return `false` si ya lo estaba —otra operación en curso, o una base del futuro—, y
     *   entonces no se toca.
     */
    fun beginRemoval(repo: RepoKey): Boolean {
        if (store?.readOnly != false) return false
        return readOnly.add(repo)
    }

    /** Lo devuelve a su estado si la exportación falló o se canceló: no se borró nada. */
    fun cancelRemoval(repo: RepoKey) {
        readOnly -= repo
        synchronized(gate) { _snapshot.value = _snapshot.value.touched() }
    }

    /**
     * Saca un repositorio de la base y del disco. Es la mitad destructiva de «Exportar y
     * quitar», y quien llama ya ha confirmado, ha exportado y ha comprobado que salió
     * todo. **Bloqueante**: llamar desde una tarea de fondo.
     *
     * **Por tandas**, que es lo que lo hace posible con un millón de tareas: cada tanda es
     * su transacción —ver [TaskStore.forgetBatch]—, así que ningún otro comando espera más
     * de lo que dura una, y la lista se va vaciando mientras ocurre en vez de congelarse.
     * Después, las filas de `blob` y el directorio del repositorio, que se recorre con
     * memoria constante aunque tenga diez millones de capturas.
     *
     * [onProgress] recibe la fase y cuánto va hecho de ella. **No se cancela a medias**:
     * lo que se pidió ya está a salvo fuera, y parar a mitad dejaría un repositorio con
     * parte de sus tareas y un fichero exportado que ya no dice lo que queda.
     */
    fun removeRepo(repo: RepoKey, onProgress: (Removal, Long) -> Unit = { _, _ -> }) {
        val store = store
        if (store != null && !store.readOnly) {
            forgetTasks(store, repo) { onProgress(Removal.TASKS, it) }
            store.bulk(TaskStore.REMOVAL_CHECKPOINT_PAGES) {
                do {
                    val rows = store.forgetBlobRows(repo)
                } while (rows > 0)
            }
            forgetChore(AttachmentChore.relocation(repo.value))
            forgetChore(AttachmentChore.reconcile(repo.value))
        }

        importing -= repo
        readOnly -= repo
        apply(TaskCommand.ForgetRepo(repo))
        if (workspace.selectedRepo == repo.value) {
            workspace.selectedRepo = _snapshot.value.activeRepo.value
        }
        runCatching { runBlocking { files?.delete(repo) { onProgress(Removal.FILES, it) } } }.onFailure { e ->
            thisLogger().warn("Tasklane: no se pudo borrar el directorio de $repo", e)
        }
        // Sin datos en disco ni en la base, el catálogo deja de conservar la entrada.
        registry.refresh()
    }

    /** En qué va [removeRepo]. */
    enum class Removal { TASKS, FILES }

    /**
     * Vacía un repositorio: **borra todas sus tareas** y lo deja en la lista, sin nada.
     *
     * Es la mitad destructiva de *Delete All Tasks in Repository* (2.3.0), y a diferencia
     * de [removeRepo] no se lleva el repositorio: sigue en el disco, sigue en el selector,
     * y lo siguiente que se cree en él empieza de cero. Quien llama ya ha preguntado y ya
     * ha puesto el repositorio en solo lectura con [beginRemoval], para que una tarea
     * creada mientras tanto no se borre sin que nadie la haya visto; aquí se le quita.
     *
     * **Sólo las tareas.** Las capturas que nombraban se quedan sin referencias y las
     * recoge el mantenimiento de siempre —con su periodo de gracia, ver
     * `AttachmentService.collectGarbage`—: borrarlas aquí sería adelantarse a un
     * recolector que ya sabe hacerlo sin pisarse con el traslado ni con la
     * reconciliación, que pueden estar corriendo en ese momento.
     *
     * Por tandas y **bloqueante**, como [removeRepo]: llamar desde una tarea de fondo.
     *
     * @return cuántas tareas se borraron.
     */
    fun clearRepo(repo: RepoKey, onProgress: (Long) -> Unit = {}): Long {
        try {
            val store = store ?: return 0
            if (store.readOnly) return 0
            return forgetTasks(store, repo, onProgress)
        } finally {
            cancelRemoval(repo)
        }
    }

    /**
     * Las tareas de un repositorio fuera de la base, por tandas —ver
     * [TaskStore.forgetBatch]—, repintando después de cada una: la lista se va vaciando
     * mientras ocurre en vez de congelarse.
     *
     * Y su historia de `⌘Z`, antes que nada (2.16.0): vaciarlo no se deshace —lo dice la
     * confirmación—, y deshacer después un borrado de antes devolvería unas pocas tareas
     * a un repositorio que el usuario acaba de pedir vacío.
     */
    private fun forgetTasks(store: TaskStore, repo: RepoKey, onProgress: (Long) -> Unit): Long {
        history.forget(setOf(repo))
        notice.get()?.takeIf { repo in it.step.repos }?.let { if (notice.compareAndSet(it, null)) it.notification.expire() }
        var removed = 0L
        // Con la caché grande y el volcado del diario espaciado: son cientos de
        // transacciones pequeñas seguidas, justo el caso de `TaskStore.bulk`.
        store.bulk(TaskStore.REMOVAL_CHECKPOINT_PAGES) {
            while (true) {
                val batch = store.forgetBatch(repo)
                if (batch == 0) break
                removed += batch
                onProgress(removed)
                synchronized(gate) { _snapshot.value = _snapshot.value.touched() }
            }
        }
        return removed
    }

    // ------------------------------------------------------ mantenimiento (Fase 5)

    /** Lo que dijo una comprobación de integridad. */
    data class Integrity(val problems: List<String>)

    /**
     * Comprueba la base si hay motivo —ver [StoreMaintenance.checkIntegrity]— y apunta el
     * resultado. **Bloqueante y caro**: lo lanza el mantenimiento en segundo plano.
     *
     * @return `null` si no hacía falta.
     */
    fun checkIntegrityIfNeeded(): Integrity? {
        val db = db ?: return null
        val store = store ?: return null
        if (store.readOnly) return null
        val pending = store.chore(StoreMaintenance.INTEGRITY_PENDING)
        val last = store.chore(StoreMaintenance.INTEGRITY)
        if (!StoreMaintenance.checkIntegrity(db.dirty, pending, last)) return null

        val problems = db.checkIntegrity()
        runCatching {
            store.write {
                store.saveChore(StoreMaintenance.INTEGRITY, nowMillis(), problems.size.toLong())
                store.forgetChore(StoreMaintenance.INTEGRITY_PENDING)
            }
        }.onFailure { thisLogger().warn("Tasklane: no se pudo apuntar la comprobación de la base", it) }
        // Hasta la 2.3 esto sólo avisaba. Desde la Fase 6 se deja de escribir y la apertura
        // siguiente la recupera.
        problems.firstOrNull()?.let(::markDamaged)
        return Integrity(problems)
    }

    /** Lo que hizo [backupIfDue]: dónde, cuánto, o por qué no. */
    internal data class Backup(val file: Path, val bytes: Long = 0, val skipped: StoreMaintenance.Skip? = null)

    /**
     * La copia diaria de la base, si toca. Ver [StoreMaintenance.backup] para cuándo toca
     * y [TaskDb.backupTo] para cómo se hace. **Bloqueante**: segundo plano.
     */
    internal fun backupIfDue(): Backup? {
        val db = db ?: return null
        val store = store ?: return null
        // Una base bajo sospecha no se copia: la copia sustituye a la anterior, y es de la
        // anterior de la que va a tirar la recuperación.
        if (store.readOnly || damaged || recovering) return null
        val target = backupFile() ?: return null

        val backupAt = runCatching { Files.getLastModifiedTime(target).toMillis() }.getOrNull()
        val modifiedAt = maxOf(modified(db.file), modified(TaskDb.walOf(db.file)))
        val dbBytes = runCatching { Files.size(db.file) }.getOrDefault(0L)
        val free = runCatching { Files.getFileStore(db.file.parent).usableSpace }.getOrDefault(Long.MAX_VALUE)
        val healthy = StoreMaintenance.healthy(
            store.chore(StoreMaintenance.INTEGRITY_PENDING),
            store.chore(StoreMaintenance.INTEGRITY),
        )

        StoreMaintenance.backup(backupAt, modifiedAt, nowMillis(), healthy, free, dbBytes)?.let {
            return Backup(target, skipped = it)
        }
        return Backup(target, db.backupTo(target))
    }

    /**
     * `tasklane.db.backup`. Ver [StoreMaintenance.BACKUP_SUFFIX]. Sale del directorio y no de
     * la base abierta: la recuperación la necesita justo cuando no hay base abierta.
     */
    fun backupFile(): Path? = layout?.root?.resolve("${TaskDb.FILE_NAME}${StoreMaintenance.BACKUP_SUFFIX}")

    /** La última comprobación de integridad que terminó, y si hay una pendiente. Para el informe. */
    fun integrityState(): Pair<Chore?, Boolean> =
        store?.let { it.chore(StoreMaintenance.INTEGRITY) to (it.chore(StoreMaintenance.INTEGRITY_PENDING) != null) }
            ?: (null to false)

    /** Cómo está la recuperación de la base, para el informe. */
    internal data class RecoveryState(
        /** Cuándo se recuperó por última vez, o `null` si nunca hizo falta. */
        val recoveredAt: Long?,
        /** La última recuperación tuvo huecos y su fichero dañado sigue ahí: el recolector espera. */
        val holdingImages: Boolean,
        /** Hay daño visto y la recuperación está pendiente de reabrir, o en marcha. */
        val pending: Boolean,
    )

    internal fun recoveryState(): RecoveryState {
        val store = store
        val layout = layout
        return RecoveryState(
            recoveredAt = store?.chore(StoreRecovery.RECOVERED)?.at,
            holdingImages = store != null && layout != null && recoveryHolds(store, layout),
            pending = recovering || damaged,
        )
    }

    // ------------------------------------------------------ recuperación (Fase 6)

    /**
     * Recupera la base dañada y la pone en su sitio. **En segundo plano y sin cancelar**:
     * son sentencias que la plataforma no deja interrumpir, y una recuperación a medias se
     * rehace entera en la apertura siguiente —ver `StoreRecovery`—, así que cancelarla sólo
     * serviría para dejar el proyecto en solo lectura más tiempo.
     */
    private fun recoverStore() {
        val layout = layout ?: return
        notify(
            TasklaneBundle.message("recovery.started.title"),
            TasklaneBundle.message("recovery.started.content"),
            NotificationType.WARNING,
        )
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("recovery.progress"), false) {
                private var outcome: StoreRecovery.Outcome? = null
                private var failure: Throwable? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    try {
                        outcome = StoreRecovery.recover(layout.root, backupFile()) { phase ->
                            indicator.text = TasklaneBundle.message("recovery.phase.$phase")
                        }
                        when (val reopened = TaskDb.openChecked(layout.root)) {
                            is TaskDb.Opening.Ready -> bind(reopened.db)
                            is TaskDb.Opening.Damaged -> error("la base recuperada vuelve a estar dañada: ${reopened.reason}")
                            is TaskDb.Opening.Unavailable -> throw reopened.error
                        }
                    } catch (e: Throwable) {
                        if (e is ControlFlowException) throw e
                        failure = e
                    }
                }

                override fun onFinished() {
                    failure?.let {
                        thisLogger().error("Tasklane: no se pudo recuperar la base de tareas", it)
                        notify(
                            TasklaneBundle.message("recovery.failed.title"),
                            TasklaneBundle.message("recovery.failed.content", it.message.orEmpty(), layout.root),
                            NotificationType.ERROR,
                        )
                        return
                    }
                    outcome?.let(::reportRecovery)
                }
            },
        )
    }

    /** Pone una base ya abierta y sana en el servicio. Una sola vez: ver [db]. */
    private fun bind(opened: TaskDb) {
        synchronized(gate) {
            db = opened
            store = TaskStore(opened)
            index.bind(Fts5Index(opened.reader))
            recovering = false
            _snapshot.value = _snapshot.value.touched()
        }
        importPending()
    }

    /**
     * Lo que salió de la recuperación, con el mismo criterio con el que se avisaba de un
     * `tasks.xml` ilegible —ver [StoreRecovery.Outcome.alert]—.
     */
    private fun reportRecovery(outcome: StoreRecovery.Outcome) {
        thisLogger().warn("Tasklane: base de tareas recuperada: $outcome")
        val where = outcome.quarantined?.toString() ?: TasklaneBundle.message("recovery.noFile")
        val (title, content, type) = when (outcome.alert()) {
            is LoadAlert.Repaired -> Triple(
                TasklaneBundle.message("recovery.repaired.title"),
                TasklaneBundle.message("recovery.repaired.content", outcome.tasks, where),
                NotificationType.INFORMATION,
            )
            is LoadAlert.Lost -> Triple(
                TasklaneBundle.message("recovery.lost.title"),
                TasklaneBundle.message("recovery.lost.content", where),
                NotificationType.ERROR,
            )
            else -> {
                val backup = outcome.backupAt?.let {
                    TasklaneBundle.message("recovery.fromBackup", outcome.restored, DateFormat.getDateTimeInstance().format(Date(it)))
                } ?: TasklaneBundle.message("recovery.noBackup")
                val partial = if (outcome.partial > 0) " " + TasklaneBundle.message("recovery.partial", outcome.partial) else ""
                Triple(
                    TasklaneBundle.message("recovery.recovered.title"),
                    TasklaneBundle.message("recovery.recovered.content", outcome.salvaged, backup, partial, where),
                    NotificationType.WARNING,
                )
            }
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .apply {
                outcome.quarantined?.let { file ->
                    addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openFile(file) })
                }
            }
            .notify(project)
    }

    /**
     * Daño visto **con la base abierta**. Se deja de escribir, se apunta para que la apertura
     * siguiente la recupere y se ofrece reabrir ya. Una vez por sesión.
     *
     * No se recupera en caliente, y es a propósito: la conexión la tienen la lista, la
     * búsqueda y las marcas del editor, y cambiarles el fichero por debajo es mucho más
     * frágil que reabrir el proyecto, que es exactamente el camino que ya está probado.
     */
    internal fun markDamaged(reason: String) {
        val layout = layout ?: return
        synchronized(gate) {
            if (damaged) return
            damaged = true
            _snapshot.value = _snapshot.value.touched()
        }
        runCatching { StoreRecovery.markDamaged(layout.root, reason) }
            .onFailure { thisLogger().warn("Tasklane: no se pudo apuntar que la base está dañada", it) }
        thisLogger().warn("Tasklane: la base de tareas está dañada: $reason")

        val backup = backupFile()?.takeIf { Files.exists(it) }
        val where = backup?.let {
            TasklaneBundle.message("notification.integrity.backup", DateFormat.getDateTimeInstance().format(Date(Files.getLastModifiedTime(it).toMillis())))
        } ?: TasklaneBundle.message("notification.integrity.noBackup")
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                TasklaneBundle.message("notification.integrity.title"),
                TasklaneBundle.message("notification.integrity.content", reason, where),
                NotificationType.ERROR,
            )
            .addAction(
                NotificationAction.createSimpleExpiring(TasklaneBundle.message("notification.integrity.repair")) {
                    ProjectManager.getInstance().reloadProject(project)
                },
            )
            .notify(project)
    }

    /**
     * ¿Hay que dejar de recolectar imágenes por una recuperación con huecos? Mientras su
     * fichero dañado siga en disco, sí —ver [StoreRecovery.INCOMPLETE]—. Cuando el usuario lo
     * borra, se olvida la marca y el recolector vuelve.
     */
    private fun recoveryHolds(store: TaskStore, layout: StorageLayout): Boolean {
        val incomplete = store.chore(StoreRecovery.INCOMPLETE) ?: return false
        if (Files.exists(StoreRecovery.quarantineOf(layout.root, incomplete.at))) return true
        if (!store.readOnly) runCatching { store.write { store.forgetChore(StoreRecovery.INCOMPLETE) } }
        return false
    }

    private fun modified(file: Path): Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)

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

        // El bucle es `TaskImport`, sin IDE, que es el que la prueba de caída del §6.1 mata
        // a mitad y comprueba que continúa.
        val outcome = metrics.time(TasklaneMetrics.Op.LOAD) {
            TaskImport.run(store, file, repo, config, ::nowMillis, cancelled = { indicator.isCanceled }) { written ->
                indicator.fraction = written.toDouble() / total
                // La ventana se va llenando mientras se importa: cada tanda confirmada es
                // una lista un poco más larga, no una pantalla en blanco de un minuto.
                synchronized(gate) { _snapshot.value = _snapshot.value.touched() }
            }
        }
        val result = outcome.result
        val written = outcome.written

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
        if (StoreRecovery.isCorruption(error)) {
            thisLogger().warn("Tasklane: la base de tareas falló por daño al escribir", error)
            markDamaged(error.message ?: error.javaClass.simpleName)
            return
        }
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

        /**
         * Hasta cuántas filas se aplica un lote en el acto, sin barra. Ver [applyAll].
         *
         * **Diez y no una página, y lo decidió el banco.** La primera versión decía
         * cincuenta —«lo que cabe en una selección sin desplazarse»— y medido sobre el
         * corpus de la especificación un lote de cincuenta son 23 ms a 10.000 tareas y
         * 31 ms a 100.000: fuera del presupuesto de 16 del EDT antes de repintar nada.
         * Veinte cabían a 10.000 (4,1 ms, p99 12) y dejaban de caber con holgura a 100.000
         * (p99 de 14 a 17 ms), porque escribir filas repartidas por la base cuesta más
         * cuanto menos cabe en la caché. Diez son 2,3 ms y p99 9 a 100.000. Por encima, la
         * barra: aparecer un instante cuesta menos que congelar.
         */
        const val BULK_INLINE = 10

        /** Hasta cuántas tareas borradas se guardan para deshacer. Ver [deleteGroup]. */
        const val UNDO_LIMIT = 10_000

        fun getInstance(project: Project): TaskService = project.service()
    }
}

/**
 * El índice de búsqueda que el servicio entrega, **con su implementación puesta después**.
 *
 * `SearchService` pide el índice una vez al crearse. Con una base que se está recuperando no
 * hay todavía de dónde leer, así que recibe esto: busca sobre nada hasta que la base está en
 * su sitio, y a partir de ahí delega en `Fts5Index` con el último contexto que se le dio.
 */
private class LateIndex(initial: TaskSearchIndex) : TaskSearchIndex {
    @Volatile
    private var target: TaskSearchIndex = initial

    @Volatile
    private var corpus: SearchCorpus? = null

    fun bind(index: TaskSearchIndex) {
        corpus?.let(index::setCorpus)
        target = index
    }

    override fun setCorpus(corpus: SearchCorpus) {
        this.corpus = corpus
        target.setCorpus(corpus)
    }

    override fun search(query: TaskQuery, scope: SearchScope): List<ScoredTask> = target.search(query, scope)
}
