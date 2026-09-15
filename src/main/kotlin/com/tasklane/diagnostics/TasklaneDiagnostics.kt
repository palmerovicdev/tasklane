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
        /**
         * El umbral de aviso del §4.5, o `0` si está apagado. **Por repositorio** desde la
         * 2.3: es lo que enseñan los ajustes y sobre lo que actúan sus botones.
         */
        val quotaBytes: Long = 0,
        /** La copia diaria y la última comprobación de la base (Fase 5). */
        val store: StoreHealth = StoreHealth(),
    ) {
        val tasks: Int get() = repos.sumOf { it.tasks }
        val blobCount: Int get() = repos.sumOf { it.blobCount }
        val blobBytes: Long get() = repos.sumOf { it.blobBytes }
        val missingBlobs: Int get() = repos.sumOf { it.blobs.missing }
        /** Con la copia diaria de la base: también es sitio que ocupa `.idea/tasklane`. */
        val totalBytes: Long get() = repos.sumOf { it.totalBytes } + store.backupBytes
        val pending: Boolean get() = repos.any { !it.reconciled }
        val overQuota: Boolean get() = repos.any(::overQuota)

        fun overQuota(repo: RepoReport): Boolean = quotaBytes > 0 && repo.blobBytes >= quotaBytes
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
        metrics: List<TasklaneMetrics.Sample> = emptyList(),
        store: StoreHealth = StoreHealth(),
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
            store = store,
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
 * tres números que SQLite saca de la tabla, contra `Files.list` más un
 * `readAttributes` por fichero. Y es lo que enseñan los ajustes desde la 2.3.
 */
data class BlobStats(
    val count: Int = 0,
    /** Lo que ocupan en disco: las filas ausentes no pesan. */
    val bytes: Long = 0,
    /** Filas cuyo fichero ya no está. Lo detecta la reconciliación del §4.3. */
    val missing: Int = 0,
) {
    /** Los que de verdad están en disco. */
    val present: Int get() = (count - missing).coerceAtLeast(0)
}

/**
 * Cómo está la base: su copia diaria y su última comprobación (Fase 5).
 *
 * Va en el informe porque son las dos cosas que alguien necesita saber **antes** de
 * tocar nada a mano en `.idea/tasklane`: si hay una copia y de cuándo, y si la base se
 * dio por sana la última vez que hubo motivo para dudar.
 */
data class StoreHealth(
    /** Cuándo se escribió `tasklane.db.backup`, o `null` si todavía no hay. */
    val backupAt: Long? = null,
    val backupBytes: Long = 0,
    /** Cuándo terminó la última comprobación de integridad, o `null` si nunca hizo falta. */
    val checkedAt: Long? = null,
    /** Cuántos problemas encontró. */
    val problems: Long = 0,
    /** Hay una pendiente: la última sesión no cerró la base y todavía no se ha mirado. */
    val pending: Boolean = false,
)

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
