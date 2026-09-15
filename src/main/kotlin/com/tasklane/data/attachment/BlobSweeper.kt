package com.tasklane.data.attachment

import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import java.time.Duration
import java.time.Instant

/**
 * Quitar imágenes: el bucle del recolector y el vaciado de un repositorio, **sin IDE**.
 *
 * Hasta la 2.2 el bucle vivía dentro de `AttachmentService.collectGarbage`, que es un
 * servicio de proyecto y no se puede instanciar en un test. Con dos botones nuevos en
 * los ajustes que borran ficheros del usuario a petición suya, el bucle sale aquí para
 * que lo que decide qué se borra —y en qué orden— se pruebe sobre una base y un
 * directorio de verdad.
 *
 * **Todo es de un repositorio.** Nada de aquí recorre «el proyecto»: quien llama dice
 * cuál, y en los ajustes es siempre el activo.
 */
internal class BlobSweeper(private val files: AttachmentStore, private val ledger: Ledger) {

    /** Lo que el recolector necesita de la base. Lo implementa el almacén de tareas. */
    interface Ledger {
        /** Los candidatos: sin referencias y escritos antes de [before]. Ver `TaskStore.collectibleBlobs`. */
        fun collectible(repo: RepoKey, before: Instant, limit: Int): List<BlobRecord>

        /** De entre [ids], los que alguna tarea sigue nombrando **ahora mismo**. */
        fun referencedAmong(repo: RepoKey, ids: List<AttachmentId>): Set<AttachmentId>

        fun forget(repo: RepoKey, ids: List<AttachmentId>)

        /** Una tanda de filas de `blob` del repositorio, fuera. @return cuántas; cero es que no quedan. */
        fun forgetBatch(repo: RepoKey): Int
    }

    /** Lo que se quitó. [complete] es `false` si se canceló a medias. */
    data class Swept(val files: Int, val bytes: Long, val complete: Boolean)

    /**
     * Borra los blobs de [repo] que no nombra ninguna tarea y tienen más de [grace].
     *
     * Con la gracia de siempre —24 horas— es el recolector del mantenimiento. Con gracia
     * cero es «borrar las no usadas» de los ajustes: ahí el usuario lo pide sabiendo lo
     * que pide, y el caso que la gracia protege —una imagen pegada en un diálogo que
     * todavía no se ha guardado— no puede darse, porque los ajustes y el diálogo de una
     * tarea son modales y no se abren a la vez.
     *
     * La doble comprobación de referencias de la Fase 4 se queda: la consulta ya excluye
     * lo referenciado, y **cada tanda vuelve a preguntar por sus candidatos** justo antes
     * de borrar. Es lo único que se hace con el `blob_ref` de ese instante, y lo que
     * protege no se puede deshacer.
     *
     * @param flatToo si hay que mirar también el directorio plano de antes de la 2.1.
     * @param onDeleted cada blob que se fue, para que quien cachea lo olvide.
     */
    fun collect(
        repo: RepoKey,
        now: Instant,
        grace: Duration = AttachmentGc.DEFAULT_GRACE,
        flatToo: Boolean = true,
        cancelled: () -> Boolean = { false },
        onDeleted: (AttachmentId) -> Unit = {},
    ): Swept {
        val cutoff = now.minus(grace)
        var deleted = 0
        var bytes = 0L
        while (true) {
            if (cancelled()) return Swept(deleted, bytes, complete = false)
            val batch = ledger.collectible(repo, cutoff, BATCH)
            if (batch.isEmpty()) break

            val referenced = ledger.referencedAmong(repo, batch.map { it.id })
            val collectible = AttachmentGc.collectible(batch, referenced, now, grace)
            for (blob in collectible) {
                if (files.delete(repo, blob.id, flatToo)) {
                    deleted++
                    bytes += blob.bytes
                }
                onDeleted(blob.id)
            }
            // Las filas **después** de los ficheros: si se corta aquí, lo que queda son
            // filas de ficheros que ya no están, que la tanda siguiente vuelve a encontrar
            // y a quitar. Al revés quedarían ficheros sin fila, que nadie volvería a nombrar.
            ledger.forget(repo, collectible.map { it.id })
            // Si la tanda entera sobrevivió a la comprobación de referencias, la consulta
            // siguiente devolvería lo mismo: se para en vez de dar vueltas. Sólo pasa si la
            // tabla y las referencias discrepan.
            if (collectible.size < batch.size) break
            if (batch.size < BATCH) break
        }
        return Swept(deleted, bytes, complete = true)
    }

    /**
     * Borra **todas** las imágenes de [repo], se usen o no: el directorio entero y sus
     * filas en `blob`.
     *
     * Las referencias de los cuerpos (`blob_ref`) **no se tocan**: siguen diciendo la
     * verdad —esa tarea nombraba una imagen— y la tarjeta ya sabe pintar la que falta.
     * Quitarlas sería editar las tareas del usuario, y eso no es lo que pidió.
     *
     * **Primero los ficheros y después las filas.** Cancelado a medias, lo que queda son
     * filas de ficheros que ya no están, o ficheros con fila: nunca peso invisible. Aun
     * así la tabla deja de cuadrar con el disco, y por eso [onIncomplete] existe: quien
     * llama pide ahí que la reconciliación vuelva a pasar.
     */
    fun deleteAll(
        repo: RepoKey,
        cancelled: () -> Boolean = { false },
        onFile: (Long) -> Unit = {},
        onIncomplete: () -> Unit = {},
    ): Swept {
        val wipe = files.deleteAll(repo, cancelled, onFile)
        if (!wipe.complete) {
            onIncomplete()
            return Swept(wipe.files.toInt(), wipe.bytes, complete = false)
        }
        while (ledger.forgetBatch(repo) > 0) Unit
        return Swept(wipe.files.toInt(), wipe.bytes, complete = true)
    }

    companion object {
        /** Cuántos candidatos por tanda. Es el `LIMIT 1000` del §4.2. */
        const val BATCH = 1_000
    }
}
