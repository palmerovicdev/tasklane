package com.tasklane.diagnostics

import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.model.RepoKey
import java.nio.file.Files
import java.nio.file.Path

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
 * **Puro salvo por el disco, y a propósito.** [collect] recibe las cuentas ya hechas
 * —desde la Fase 3 las da el almacén en agregados, no un recorrido de las tareas— y
 * sólo va al sistema de ficheros a pesar; así lo que decide qué se cuenta se prueba sin
 * IDE y sin proyecto, que es lo mismo que se hizo con
 * [com.tasklane.data.attachment.AttachmentGc] por la misma razón: una cifra que se
 * enseña al usuario tiene que ser una cifra que un test pueda fijar.
 *
 * **Lo que la Fase 4 quitó de aquí.** Hasta la 2.0 esto pesaba el directorio de
 * adjuntos recorriéndolo, con un tope de 200.000 ficheros y un «+» cuando lo alcanzaba:
 * era la única forma de saber cuánto ocupaban, y era justamente el recorrido que el
 * §1.6 dice que no termina. Ahora las imágenes las cuenta la tabla `blob` —§4.2—, así
 * que la cifra es exacta, es instantánea y ya no hay nada que truncar. Al disco sólo se
 * va a pesar tres ficheros: la base, su diario y lo que quede del formato XML.
 *
 * **Bloqueante**, aunque mucho menos que antes: llamar desde `Dispatchers.IO`.
 */
object TasklaneDiagnostics {

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
        /** Lo que la tabla `blob` sabe de este repositorio. Ver [BlobStats]. */
        val blobs: BlobStats = BlobStats(),
        /**
         * Si la reconciliación del §4.3 ya pasó por este repositorio.
         *
         * Importa decirlo: hasta que pasa la primera vez, la tabla sólo conoce los blobs
         * que se hayan escrito con la 2.1 en adelante, así que las cifras de imágenes de
         * un proyecto recién actualizado están **incompletas**, no a cero. Un informe que
         * dijera «0 imágenes» de un directorio lleno sería exactamente el mismo error que
         * el §1.6 le prohíbe al recolector.
         */
        val reconciled: Boolean = false,
    ) {
        val blobCount: Int get() = blobs.count
        val blobBytes: Long get() = blobs.bytes

        /** Lo que este repositorio ocupa en `.idea`, tareas y capturas juntas. */
        val totalBytes: Long get() = tasksFileBytes + backupBytes + blobBytes

        /**
         * Blobs que están en disco pero que ya no nombra ninguna tarea. Candidatos del GC.
         *
         * Sale de restar lo que hay menos lo que se nombra, y las dos cifras vienen ya de
         * la base: `blob` contra `blob_ref`. Antes la primera había que ir a buscarla al
         * directorio.
         */
        val unreferencedBlobs: Int get() = (blobs.present - distinctImages).coerceAtLeast(0)
    }

    data class Report(
        val repos: List<RepoReport>,
        val heapUsedBytes: Long,
        val heapMaxBytes: Long,
        val latencies: List<TasklaneMetrics.Sample>,
        /** El umbral de aviso del §4.5, o `0` si está apagado. */
        val quotaBytes: Long = 0,
        /** El tope de escalado vigente: es contra él contra lo que se cuenta lo sobredimensionado. */
        val imageMaxSize: Int = 0,
    ) {
        val tasks: Int get() = repos.sumOf { it.tasks }
        val blobCount: Int get() = repos.sumOf { it.blobCount }
        val blobBytes: Long get() = repos.sumOf { it.blobBytes }
        val missingBlobs: Int get() = repos.sumOf { it.blobs.missing }
        val oversizedBlobs: Int get() = repos.sumOf { it.blobs.oversized }
        val oversizedBytes: Long get() = repos.sumOf { it.blobs.oversizedBytes }
        val totalBytes: Long get() = repos.sumOf { it.totalBytes }
        val pending: Boolean get() = repos.any { !it.reconciled }
        val overQuota: Boolean get() = quotaBytes > 0 && blobBytes >= quotaBytes
    }

    /**
     * @param stats las cuentas de cada repositorio, tal y como las da el almacén. Un
     *   repositorio que no esté en el mapa no aparece en el informe: decir «0 tareas»
     *   de algo que no se ha mirado sería exactamente el mismo error que el §1.6 le
     *   prohíbe al recolector de basura.
     */
    fun collect(
        layout: StorageLayout?,
        stats: Map<RepoKey, TaskStats>,
        blobs: Map<RepoKey, BlobStats> = emptyMap(),
        reconciled: Set<RepoKey> = emptySet(),
        quotaBytes: Long = 0,
        imageMaxSize: Int = 0,
        metrics: List<TasklaneMetrics.Sample> = emptyList(),
    ): Report {
        val runtime = Runtime.getRuntime()
        // La base es **un fichero por proyecto** (§2.3), así que su peso se le atribuye
        // al primer repositorio del informe en vez de repetirse en todos: el total de
        // abajo suma las filas, y contarla N veces mentiría por un factor N.
        var db = layout?.let(::dbBytes) ?: 0L
        return Report(
            repos = stats.map { (repo, counts) ->
                repoReport(layout, repo, counts, db, blobs[repo] ?: BlobStats(), repo in reconciled)
                    .also { db = 0L }
            },
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory(),
            latencies = metrics,
            quotaBytes = quotaBytes,
            imageMaxSize = imageMaxSize,
        )
    }

    private fun repoReport(
        layout: StorageLayout?,
        repo: RepoKey,
        stats: TaskStats,
        dbBytes: Long,
        blobs: BlobStats,
        reconciled: Boolean,
    ): RepoReport {
        return RepoReport(
            repo = repo,
            tasks = stats.tasks,
            orphans = stats.orphans,
            bodyChars = stats.bodyChars,
            anchors = stats.anchors,
            tags = stats.tags,
            imageRefs = stats.imageRefs,
            distinctImages = stats.distinctImages,
            // Lo que ocupa la base entera, no el `tasks.xml`: desde la Fase 3 el fichero
            // sólo queda como `.migrated`, y lo que pesa es `tasklane.db` más su diario.
            tasksFileBytes = dbBytes,
            // Y lo que queda del formato viejo, junto: el original archivado por la
            // migración y la copia de seguridad que escribía el volcado. Es espacio que
            // el usuario puede recuperar en cuanto se fíe, y por eso se dice.
            backupBytes = layout?.let { xmlBytes(it, repo) } ?: 0L,
            blobs = blobs,
            reconciled = reconciled,
        )
    }

    /** Lo que queda del formato anterior: el archivado, el original y su copia. */
    private fun xmlBytes(layout: StorageLayout, repo: RepoKey): Long =
        sizeOf(layout.tasksFile(repo)) +
            sizeOf(layout.migratedFile(repo)) +
            sizeOf(layout.backupFile(repo))

    /** La base y su diario: lo que de verdad ocupan las tareas desde la Fase 3. */
    private fun dbBytes(layout: StorageLayout): Long {
        val base = layout.root.resolve(StorageLayout.DB_FILE)
        return sizeOf(base) +
            sizeOf(base.resolveSibling("${StorageLayout.DB_FILE}-wal")) +
            sizeOf(base.resolveSibling("${StorageLayout.DB_FILE}-shm"))
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

/**
 * El retrato de un repositorio **en agregados**, tal y como lo devuelve el almacén.
 *
 * Vive aquí y no dentro del almacén por una razón de capas: `TaskStore` es interno de la
 * Fase 3 y el informe de diagnóstico es público. Pero sobre todo por una de forma: hasta
 * la Fase 2 estas cifras salían de recorrer las tareas en memoria —sumar la longitud de
 * un millón de cuerpos para decir cuánto ocupan—, y ahora salen de siete `count(*)` y un
 * `sum(length(body))` que SQLite resuelve sin construir una sola `Task`.
 */
/**
 * Lo que la tabla `blob` sabe de un repositorio, en agregados (§4.2).
 *
 * Es el sustituto exacto del recorrido del directorio que hacía el informe hasta la 2.0:
 * cinco números que SQLite saca de un índice, contra `Files.list` más un
 * `readAttributes` por fichero.
 *
 * [oversized] es la cifra que la Fase 4 se debe a sí misma. Al bajar el tope de escalado
 * de 1600 a 400 px se decidió **no tocar lo ya guardado** —el nombre de un blob es el
 * SHA de sus bytes, así que reescalarlo cambiaría su nombre y habría que reescribir
 * todos los cuerpos que lo nombran—, y lo mínimo que se le debe a quien tenga tres gigas
 * de capturas antiguas es decirle cuántas son y cuánto ocupan.
 */
data class BlobStats(
    val count: Int = 0,
    val bytes: Long = 0,
    /** Filas cuyo fichero ya no está. Lo detecta la reconciliación del §4.3. */
    val missing: Int = 0,
    /** Blobs guardados por encima del tope de escalado de hoy. */
    val oversized: Int = 0,
    val oversizedBytes: Long = 0,
) {
    /** Los que de verdad están en disco. */
    val present: Int get() = (count - missing).coerceAtLeast(0)
}

data class TaskStats(
    val tasks: Int = 0,
    val bodyChars: Long = 0,
    val anchors: Int = 0,
    val tags: Int = 0,
    /** Referencias a imágenes desde el cuerpo, con repetidas. */
    val imageRefs: Int = 0,
    /** IDs distintos: [imageRefs] menos lo que la deduplicación ahorra. */
    val distinctImages: Int = 0,
    /** Tareas cuyo estado o prioridad ya no existen en la configuración. */
    val orphans: Int = 0,
)
