package com.tasklane.data.store

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.JDOMUtil
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * El lado **XML** del almacén: el formato de intercambio, no el almacén.
 *
 * Hasta la 1.6.0 esto era el almacén entero —se leía el fichero al abrir y se reescribía
 * completo en cada volcado—. Desde la Fase 3 las tareas viven en `tasklane.db` y esta
 * clase conserva dos trabajos, los dos vivos:
 *
 * - **[write] es la exportación.** Es lo que respalda *Export Repository to XML* y la
 *   promesa del §3.3 del plan de escala: que el dato pueda salir a un fichero de texto
 *   es lo que hace que mudarse a una base no sea un viaje de ida.
 * - **[delete] borra el directorio de un repositorio**, que es la mitad destructiva de
 *   «Exportar y quitar».
 *
 * [read] ya no la llama nadie en producción —importar un `tasks.xml` se hace en
 * *streaming* con `TasksXmlReader`, porque un fichero de 2,9 GB no cabe en un DOM— y se
 * queda a propósito: es el decodificador de referencia con el que sus tests comprueban
 * que lo que [write] escribe se vuelve a leer igual. Una exportación que no se pueda
 * volver a importar no es una exportación.
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
     *
     * **Con memoria constante desde la Fase 5.** Hasta la 2.1 esto era `Files.walk` más
     * `sorted(reverseOrder())` —la forma corta de borrar hijos antes que padres—, y
     * ordenar un *stream* es **materializarlo**: con diez millones de capturas, una lista
     * de diez millones de `Path` antes de borrar la primera. `walkFileTree` entrega cada
     * directorio **después** de sus hijos sin tener que recordar nada más que la rama en
     * la que está, que en el árbol de adjuntos son tres niveles.
     *
     * [onFile] recibe cuántos ficheros van borrados, para la barra de progreso.
     */
    suspend fun delete(repo: RepoKey, onFile: (Long) -> Unit = {}) = lockFor(repo).withLock {
        val dir = layout.repoDir(repo)
        if (!Files.exists(dir)) return@withLock
        var files = 0L
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    remove(file)
                    onFile(++files)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    thisLogger().warn("Tasklane: no se pudo visitar $file", exc)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    remove(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun remove(path: Path) {
        runCatching { Files.deleteIfExists(path) }
            .onFailure { thisLogger().warn("Tasklane: no se pudo borrar $path", it) }
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
