package com.tasklane.data.attachment

import com.tasklane.domain.model.AttachmentId
import java.time.Duration
import java.time.Instant

/**
 * Qué blobs sobran. Decisión pura: recibe la lista de lo que hay, el conjunto de lo
 * que está referenciado y la hora, y devuelve lo que se puede borrar. El borrado en
 * sí lo hace [com.tasklane.service.AttachmentService], que es quien puede tocar disco.
 *
 * Separar la decisión del efecto no es ceremonia: esto **borra ficheros del usuario**,
 * y una política que se puede fijar con un test es la diferencia entre confiar en
 * ella y no atreverse a ejecutarla.
 *
 * **El periodo de gracia es la pieza que importa.** Un blob recién escrito todavía no
 * tiene por qué estar referenciado: el diálogo de edición puede seguir abierto, el
 * usuario puede haber deshecho el pegado y volver a rehacerlo, y el volcado a disco
 * va con debounce. Borrar por «no lo veo referenciado ahora mismo» convertiría un
 * `⌘Z` en una imagen perdida. Con la gracia, lo que se recoge es lo que lleva horas
 * sin que nadie lo nombre.
 */
object AttachmentGc {

    /** Suficiente para cubrir una sesión de trabajo entera con el diálogo abierto. */
    val DEFAULT_GRACE: Duration = Duration.ofHours(24)

    /**
     * @param blobs lo que hay en el directorio de adjuntos del repositorio.
     * @param referenced los IDs que aparecen en el cuerpo de alguna tarea **de ese
     *   mismo repositorio**. Pasar aquí un conjunto incompleto borra datos, así que
     *   quien llama sólo debe invocar esto con repositorios ya cargados.
     */
    fun collectible(
        blobs: List<AttachmentStore.Blob>,
        referenced: Set<AttachmentId>,
        now: Instant,
        grace: Duration = DEFAULT_GRACE,
    ): List<AttachmentStore.Blob> = blobs.filter { blob ->
        blob.id !in referenced && !blob.modified.isAfter(now.minus(grace))
    }
}
