package com.tasklane.code

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.tasklane.domain.model.AnchorMove
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.service.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

/**
 * Las anclas frente al sistema de ficheros (2.13.0): se van con el fichero cuando se
 * renombra o se mueve, y se pintan rotas cuando el fichero ya no está.
 *
 * Hasta aquí [CodeAnchor] guardaba la ruta como texto y nadie la vigilaba.
 * [com.tasklane.domain.model.AnchorResolver] reencontraba la línea cuando el código se
 * movía **dentro** del fichero, pero renombrar el fichero —o mover el paquete entero con
 * una refactorización— dejaba el ancla apuntando a la nada, y la tarjeta seguía
 * enseñando `Auth.kt:42` en color de enlace hasta que alguien la pulsaba.
 *
 * ## Mover y renombrar
 *
 * El IDE avisa de los dos con un evento propio, y entonces las anclas se reescriben —ver
 * [AnchorMove] y `TaskService.relinkAnchors`—. Sólo el IDE: lo que se renombra desde el
 * terminal llega como un borrado y una creación sin nada que los una, y ahí lo honesto
 * es pintar el ancla como rota, no adivinar adónde fue.
 *
 * ## Rotas
 *
 * Qué rutas ancladas no llevan a ningún fichero es un dato **del disco**, no de la tarea,
 * así que no se guarda: se comprueba. Entero al abrir el proyecto —el disco cambia con el
 * IDE cerrado— y al terminar de importar o de recuperar la base; después, sólo lo que
 * toca cada evento y cada comando. El resultado va a
 * [com.tasklane.domain.model.TasklaneSnapshot.brokenAnchors].
 *
 * **Todo en una sola corrutina y en orden.** Reescribir las anclas de un fichero movido
 * y después comprobar si su ruta nueva existe no pueden adelantarse el uno al otro, y
 * con una cola de un solo consumidor eso no hay que pensarlo.
 */
@Service(Service.Level.PROJECT)
internal class AnchorFiles(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    private val tasks = TaskService.getInstance(project)

    /** Lo pendiente, en el orden en que llegó. Ver el KDoc de la clase. */
    private val work = Channel<Work>(Channel.UNLIMITED)

    /** Las rutas rotas que se conocen. Sólo las toca la corrutina que atiende [work]. */
    private var broken: Set<String> = emptySet()

    /**
     * La comprobación entera se pidió sin base que preguntar —se estaba recuperando—, y
     * hay que repetirla en cuanto la haya. Ver [watchScans].
     */
    @Volatile
    private var stale = false

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) = onEvents(events)
            },
        )
        scope.launch(Dispatchers.IO) {
            for (next in work) {
                try {
                    handle(next)
                } catch (e: Exception) {
                    if (e is ControlFlowException || e is CancellationException) throw e
                    // Un fallo aquí deja un ancla sin mover o sin marcar, no una tarea
                    // perdida: se apunta y se sigue con lo siguiente de la cola.
                    thisLogger().warn("Tasklane: no se pudieron revisar las anclas", e)
                }
            }
        }
        scope.launch { watchScans() }
        scope.launch { tasks.anchorsWritten.collect { work.trySend(Work.Check(it)) } }
    }

    /**
     * Cuándo hace falta mirarlas todas: al empezar, al terminar de importar el `tasks.xml`
     * de una versión anterior —escribe en la base sin pasar por los comandos— y cuando la
     * base aparece después de recuperarla.
     */
    private suspend fun watchScans() {
        var busy = true
        tasks.snapshot.collect { snapshot ->
            val idle = snapshot.loading.isEmpty()
            if (idle && (busy || stale)) {
                stale = false
                work.trySend(Work.Scan)
            }
            busy = !idle
        }
    }

    // -------------------------------------------------------------------- eventos

    /**
     * Lo que dice el sistema de ficheros, traducido a rutas de ancla.
     *
     * Corre en el EDT y dentro de la escritura que hizo el cambio, así que aquí sólo se
     * clasifica y se encola: nada de base ni de disco. Un borrado sólo puede **romper**
     * anclas, una creación sólo puede **arreglarlas** —un fichero que vuelve con
     * `git checkout`— y un movimiento hace las dos cosas.
     */
    private fun onEvents(events: List<VFileEvent>) {
        val base = project.basePath
        val moves = ArrayList<AnchorMove>()
        val gone = LinkedHashSet<String>()
        val born = LinkedHashSet<String>()
        for (event in events) {
            if (event.fileSystem !is LocalFileSystem) continue
            when (event) {
                is VFileMoveEvent -> moves += AnchorMove(CodeAnchor.pathOf(base, event.oldPath), CodeAnchor.pathOf(base, event.newPath))
                is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) {
                    moves += AnchorMove(CodeAnchor.pathOf(base, event.oldPath), CodeAnchor.pathOf(base, event.newPath))
                }

                is VFileDeleteEvent -> gone += CodeAnchor.pathOf(base, event.path)
                is VFileCreateEvent, is VFileCopyEvent -> born += CodeAnchor.pathOf(base, event.path)
                else -> Unit
            }
        }
        if (moves.isEmpty() && gone.isEmpty() && born.isEmpty()) return
        work.trySend(Work.Changed(moves, gone, born))
    }

    // ---------------------------------------------------------------------- cola

    private fun handle(next: Work) {
        val before = broken
        when (next) {
            Work.Scan -> {
                val paths = tasks.anchorPaths() ?: run {
                    stale = true
                    return
                }
                broken = paths.filterTo(HashSet()) { CodeAnchors.isMissing(project, it) }
            }

            is Work.Check -> broken = recheck(next.paths)

            is Work.Changed -> {
                tasks.relinkAnchors(next.moves)
                // Lo que puede haberse roto está en la base: las anclas que colgaban de lo
                // borrado, y las de un fichero movido que no se fueron con él —las de un
                // repositorio en solo lectura—. Lo que puede haberse arreglado ya se sabe
                // que estaba roto, así que basta con mirar entre las rotas.
                val lost = next.gone + next.moves.map { it.from }
                val found = next.born + next.moves.map { it.to }
                val candidates = LinkedHashSet<String>()
                if (lost.isNotEmpty()) candidates += tasks.anchorPaths(lost).orEmpty()
                broken.filterTo(candidates) { path -> found.any { path == it || path.startsWith("$it/") } }
                broken = recheck(candidates)
            }
        }
        if (broken != before) tasks.setBrokenAnchors(broken)
    }

    /** [broken] con [paths] vueltas a mirar en el disco. */
    private fun recheck(paths: Collection<String>): Set<String> {
        if (paths.isEmpty()) return broken
        val next = HashSet(broken)
        for (path in paths) {
            if (CodeAnchors.isMissing(project, path)) next += path else next -= path
        }
        return next
    }

    override fun dispose() {
        work.close()
    }

    private sealed interface Work {
        /** Todas las rutas ancladas, contra el disco. */
        data object Scan : Work

        /** Estas rutas, que un comando acaba de escribir. Ver `TaskService.anchorsWritten`. */
        data class Check(val paths: Collection<String>) : Work

        /** Lo que trajo una tanda de eventos del sistema de ficheros. Ver [onEvents]. */
        data class Changed(val moves: List<AnchorMove>, val gone: Set<String>, val born: Set<String>) : Work
    }

    companion object {
        fun getInstance(project: Project): AnchorFiles = project.service()
    }
}
