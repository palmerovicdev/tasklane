package com.tasklane.data.attachment

import com.tasklane.domain.model.AttachmentId
import java.time.Duration
import java.time.Instant

/**
 * Qué blobs sobran. Decisión pura: recibe los candidatos, el conjunto de lo que está
 * referenciado y la hora, y devuelve lo que se puede borrar. El borrado en sí lo hace
 * [com.tasklane.service.AttachmentService], que es quien puede tocar disco.
 *
 * Separar la decisión del efecto no es ceremonia: esto **borra ficheros del usuario**,
 * y una política que se puede fijar con un test es la diferencia entre confiar en
 * ella y no atreverse a ejecutarla.
 *
 * **Lo que cambió en la Fase 4** (§4.2): los candidatos ya no son «todo lo que hay en
 * el directorio» —un `Files.list` que con diez millones de entradas no termina— sino
 * las filas que la tabla `blob` da por no referenciadas, a tandas y entrando por
 * índice. La forma de esta función es la misma a propósito: la consulta ya filtra por
 * referencias y por gracia, y esto lo vuelve a comprobar **con las referencias leídas
 * después, y sólo las de la tanda**. Dos veces la misma pregunta, sí; es una consulta a
 * un `HashSet` de mil entradas y protege del único fallo que no se puede deshacer.
 *
 * **El periodo de gracia es la pieza que importa.** Un blob recién escrito todavía no
 * tiene por qué estar referenciado: el diálogo de edición puede seguir abierto y el
 * usuario puede haber deshecho el pegado y volver a rehacerlo. Borrar por «no lo veo
 * referenciado ahora mismo» convertiría un `⌘Z` en una imagen perdida. Con la gracia,
 * lo que se recoge es lo que lleva horas sin que nadie lo nombre.
 */
object AttachmentGc {

    /** Suficiente para cubrir una sesión de trabajo entera con el diálogo abierto. */
    val DEFAULT_GRACE: Duration = Duration.ofHours(24)

    /**
     * @param blobs los candidatos, tal y como los da `TaskStore.collectibleBlobs`.
     * @param referenced de entre [blobs], los que aparecen en el cuerpo de alguna tarea
     *   **de ese mismo repositorio**. Pasar aquí un conjunto incompleto borra datos, así
     *   que quien llama sólo debe invocar esto con repositorios cuya importación haya
     *   terminado — ver `AttachmentService.collectGarbage`.
     */
    fun collectible(
        blobs: List<BlobRecord>,
        referenced: Set<AttachmentId>,
        now: Instant,
        grace: Duration = DEFAULT_GRACE,
    ): List<BlobRecord> = blobs.filter { blob ->
        blob.id !in referenced && !blob.createdAt.isAfter(now.minus(grace))
    }
}
