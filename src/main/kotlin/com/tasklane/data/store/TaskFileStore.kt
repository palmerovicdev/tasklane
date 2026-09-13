package com.tasklane.data.store

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.JDOMUtil
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Lectura y escritura de las tareas, un fichero por repositorio.
 *
 * Todo el IO de esta clase es bloqueante y debe invocarse desde `Dispatchers.IO`.
 * No usa la VFS a propósito: estos ficheros son datos del plugin, no fuentes del
 * proyecto, así que no deben participar en indexado, refactors ni write actions.
 */
class TaskFileStore(private val layout: StorageLayout) {

    /** Un único escritor por repositorio. */
    private val locks = mutableMapOf<RepoKey, Mutex>()

    private fun lockFor(repo: RepoKey): Mutex = synchronized(locks) { locks.getOrPut(repo) { Mutex() } }

    // ---------------------------------------------------------------- lectura

    fun read(repo: RepoKey): ReadResult {
        val file = layout.tasksFile(repo)
        if (!Files.exists(file)) return ReadResult.Empty

        readFile(file, repo)?.let { decoded ->
            return when {
                // Fichero de una versión futura: se abre en SOLO LECTURA en vez de
                // reescribirlo con un esquema viejo y degradarlo.
                decoded.version > TasksCodec.CURRENT_VERSION ->
                    ReadResult.FutureVersion(decoded.tasks, decoded.version)
                else -> ReadResult.Ok(decoded.tasks)
            }
        }

        // El principal falló. Se preserva para poder inspeccionarlo y se intenta el backup.
        val stamp = System.currentTimeMillis()
        val quarantined = runCatching {
            val target = layout.corruptFile(repo, stamp)
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
            target
        }.getOrNull()

        val backup = layout.backupFile(repo)
        val recovered = if (Files.exists(backup)) readFile(backup, repo) else null

        return ReadResult.Corrupt(
            recovered = recovered?.tasks.orEmpty(),
            recoveredFromBackup = recovered != null,
            quarantinedAt = quarantined,
        )
    }

    private fun readFile(file: Path, repo: RepoKey): TasksCodec.Decoded? = try {
        TasksCodec.decode(JDOMUtil.load(file), repo)
    } catch (e: Exception) {
        thisLogger().warn("Tasklane: no se pudo leer $file", e)
        null
    }

    // -------------------------------------------------------------- escritura

    /**
     * Escritura atómica:
     *  1. se respalda la versión actual en `.bak`,
     *  2. se vuelca a `.tmp` y se fuerza a disco,
     *  3. `ATOMIC_MOVE` sobre el destino.
     *
     * Si falla el paso 2 (disco lleno, permisos) el fichero bueno queda intacto.
     */
    suspend fun write(repo: RepoKey, tasks: List<Task>) = lockFor(repo).withLock {
        val dir = layout.repoDir(repo)
        Files.createDirectories(dir)
        layout.ensureIgnored()

        val target = layout.tasksFile(repo)
        val bytes = JDOMUtil.writeElement(TasksCodec.encode(repo, tasks)).toByteArray(StandardCharsets.UTF_8)

        if (Files.exists(target)) {
            runCatching {
                Files.copy(target, layout.backupFile(repo), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        val tmp = dir.resolve("${StorageLayout.TASKS_FILE}.tmp")
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(bytes))
            channel.force(true)
        }

        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Algunos sistemas de ficheros de red no soportan ATOMIC_MOVE.
            thisLogger().warn("Tasklane: ATOMIC_MOVE no soportado en $target, se usa move normal", e)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---------------------------------------------------------------- borrado

    /**
     * Borra el directorio entero de un repositorio: tareas, copia de seguridad,
     * ficheros en cuarentena y adjuntos.
     *
     * Sólo lo invoca «Exportar y quitar», y sólo después de que el texto esté en el
     * portapapeles. Toma el mismo cerrojo que la escritura para no borrar por debajo
     * de un volcado en curso.
     *
     * Se recorre el árbol en vez de usar `deleteRecursively` de la VFS por la misma
     * razón que el resto de la clase: esto no son fuentes del proyecto.
     */
    suspend fun delete(repo: RepoKey) = lockFor(repo).withLock {
        val dir = layout.repoDir(repo)
        if (!Files.exists(dir)) return@withLock
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
                    .onFailure { thisLogger().warn("Tasklane: no se pudo borrar $path", it) }
            }
        }
    }

    sealed interface ReadResult {
        val tasks: List<Task>

        /** No hay fichero todavía: repositorio nuevo, no es un error. */
        data object Empty : ReadResult {
            override val tasks: List<Task> get() = emptyList()
        }

        data class Ok(override val tasks: List<Task>) : ReadResult

        /** Escrito por una versión más nueva del plugin. No se debe sobrescribir. */
        data class FutureVersion(override val tasks: List<Task>, val version: Int) : ReadResult

        data class Corrupt(
            val recovered: List<Task>,
            val recoveredFromBackup: Boolean,
            val quarantinedAt: Path?,
        ) : ReadResult {
            override val tasks: List<Task> get() = recovered
        }
    }
}
