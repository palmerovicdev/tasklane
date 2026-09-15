package com.tasklane.diagnostics

import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * El retrato de un proyecto: cuántas tareas hay, cuánto ocupan y cuánto pesan sus
 * imágenes. Es la mitad en disco de la acción de diagnóstico del
 * `docs/plan-escala.md` §0.4; la de latencias es [TasklaneMetrics].
 *
 * **Para qué sirve, exactamente.** Para que un usuario que dice «va lento» pueda
 * decirlo con un número, y para que la cuota con aviso de la Fase 4 —§4.5— tenga de
 * dónde sacar el peso que vigila. Hasta ahora la única forma de saber si un proyecto
 * tenía 200 tareas o 200.000 era abrir el `tasks.xml` a mano.
 *
 * **Puro salvo por el disco, y a propósito.** [collect] recibe las tareas ya en
 * memoria y sólo va al sistema de ficheros a pesar; así lo que decide qué se cuenta
 * se prueba sin IDE y sin proyecto, que es lo mismo que se hizo con
 * [com.tasklane.data.attachment.AttachmentGc] por la misma razón: una cifra que se
 * enseña al usuario tiene que ser una cifra que un test pueda fijar.
 *
 * **Bloqueante.** Recorre directorios: llamar desde `Dispatchers.IO`, nunca del EDT.
 * Es justamente el recorrido que el §1.6 dice que no termina con diez millones de
 * blobs, así que [collect] lleva su propio tope y lo dice cuando lo alcanza.
 */
object TasklaneDiagnostics {

    /**
     * Cuántos ficheros de adjuntos se miran como mucho.
     *
     * Con diez millones en un directorio plano, `Files.list` más un `readAttributes`
     * por entrada es minutos de trabajo —el §1.6—, y una acción de diagnóstico que
     * cuelga el IDE es peor que no tener acción de diagnóstico. Al llegar al tope se
     * deja de contar y se **dice** que la cifra está truncada: un número redondo sin
     * aviso se leería como el total.
     */
    const val BLOB_SCAN_LIMIT = 200_000

    data class RepoReport(
        val repo: RepoKey,
        val tasks: Int,
        /** Tareas cuyo estado o prioridad ya no existen en la configuración. */
        val orphans: Int,
        val bodyChars: Long,
        val anchors: Int,
        val tags: Int,
        /** Referencias a imágenes desde el cuerpo, con repetidas. */
        val imageRefs: Int,
        /** IDs distintos: [imageRefs] menos lo que la deduplicación ahorra. */
        val distinctImages: Int,
        val tasksFileBytes: Long,
        val backupBytes: Long,
        val blobCount: Int,
        val blobBytes: Long,
        /** El directorio tenía más de [BLOB_SCAN_LIMIT] ficheros y se dejó de contar. */
        val blobsTruncated: Boolean,
    ) {
        /** Lo que este repositorio ocupa en `.idea`, tareas y capturas juntas. */
        val totalBytes: Long get() = tasksFileBytes + backupBytes + blobBytes

        /** Blobs que están en disco pero que ya no nombra ninguna tarea. Candidatos del GC. */
        val unreferencedBlobs: Int get() = (blobCount - distinctImages).coerceAtLeast(0)
    }

    data class Report(
        val repos: List<RepoReport>,
        val heapUsedBytes: Long,
        val heapMaxBytes: Long,
        val latencies: List<TasklaneMetrics.Sample>,
    ) {
        val tasks: Int get() = repos.sumOf { it.tasks }
        val blobCount: Int get() = repos.sumOf { it.blobCount }
        val blobBytes: Long get() = repos.sumOf { it.blobBytes }
        val totalBytes: Long get() = repos.sumOf { it.totalBytes }
        val truncated: Boolean get() = repos.any { it.blobsTruncated }
    }

    /**
     * @param tasksByRepo lo que hay **en memoria**. Un repositorio que aún no se ha
     *   leído no aparece: decir «0 tareas» de un fichero que no se ha abierto sería
     *   exactamente el mismo error que el §1.6 le prohíbe al recolector de basura.
     */
    fun collect(
        layout: StorageLayout?,
        tasksByRepo: Map<RepoKey, List<Task>>,
        orphansOf: (List<Task>) -> Int,
        metrics: List<TasklaneMetrics.Sample> = emptyList(),
    ): Report {
        val runtime = Runtime.getRuntime()
        return Report(
            repos = tasksByRepo.map { (repo, tasks) -> repoReport(layout, repo, tasks, orphansOf) },
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory(),
            latencies = metrics,
        )
    }

    private fun repoReport(
        layout: StorageLayout?,
        repo: RepoKey,
        tasks: List<Task>,
        orphansOf: (List<Task>) -> Int,
    ): RepoReport {
        val distinct = HashSet<String>()
        var refs = 0
        var chars = 0L
        var anchors = 0
        var tags = 0
        for (task in tasks) {
            chars += task.body.length
            anchors += task.anchors.size
            tags += task.tags.size
            refs += task.attachments.size
            for (ref in task.attachments) distinct += ref.id.value
        }

        val blobs = layout?.let { scanBlobs(it.attachmentsDir(repo)) } ?: BlobScan.EMPTY

        return RepoReport(
            repo = repo,
            tasks = tasks.size,
            orphans = orphansOf(tasks),
            bodyChars = chars,
            anchors = anchors,
            tags = tags,
            imageRefs = refs,
            distinctImages = distinct.size,
            tasksFileBytes = layout?.let { sizeOf(it.tasksFile(repo)) } ?: 0L,
            backupBytes = layout?.let { sizeOf(it.backupFile(repo)) } ?: 0L,
            blobCount = blobs.count,
            blobBytes = blobs.bytes,
            blobsTruncated = blobs.truncated,
        )
    }

    private data class BlobScan(val count: Int, val bytes: Long, val truncated: Boolean) {
        companion object {
            val EMPTY = BlobScan(0, 0L, false)
        }
    }

    /**
     * Cuenta y pesa el directorio de adjuntos, hasta [BLOB_SCAN_LIMIT].
     *
     * Con `Files.newDirectoryStream` y no con `Files.list(dir).toList()` —que es lo
     * que hace hoy `AttachmentStore.list`— justamente porque aquí sí se puede parar:
     * el `toList()` materializa el directorio entero antes de que nadie pueda decidir
     * que ya son demasiados.
     */
    private fun scanBlobs(dir: Path): BlobScan {
        if (!Files.isDirectory(dir)) return BlobScan.EMPTY
        var count = 0
        var bytes = 0L
        var truncated = false
        try {
            Files.newDirectoryStream(dir).use { entries ->
                for (path in entries) {
                    if (count >= BLOB_SCAN_LIMIT) {
                        truncated = true
                        break
                    }
                    val attrs = runCatching {
                        Files.readAttributes(path, BasicFileAttributes::class.java)
                    }.getOrNull() ?: continue
                    if (!attrs.isRegularFile) continue
                    count++
                    bytes += attrs.size()
                }
            }
        } catch (_: Exception) {
            // Un directorio ilegible no es motivo para no dar el resto del informe.
            return BlobScan(count, bytes, truncated)
        }
        return BlobScan(count, bytes, truncated)
    }

    private fun sizeOf(file: Path): Long = runCatching { Files.size(file) }.getOrDefault(0L)

    // ------------------------------------------------------------------- formato

    /** `1,4 GB`, `286 MB`, `12 KB`. Una cifra de peso que nadie va a sumar a mano. */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
    }
}
