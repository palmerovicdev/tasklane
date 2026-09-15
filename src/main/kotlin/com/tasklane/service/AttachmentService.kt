package com.tasklane.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.tasklane.data.attachment.AttachmentGc
import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.data.attachment.ImageNormalizer
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.text.ImageRefParser
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Instant

/**
 * Las imágenes de las tareas: guardar lo que se pega, servir lo que se pinta y tirar
 * lo que ya no referencia nadie.
 *
 * Es al [AttachmentStore] lo que [TaskService] es al almacén de tareas: la UI no toca
 * disco, habla con esto. La diferencia es que aquí no hay snapshot que mantener —el
 * cuerpo de la tarea ya es la única fuente de verdad sobre qué imágenes existen—, así
 * que este servicio no guarda estado más allá de dos cachés.
 *
 * Todo lo que lee o escribe es **bloqueante y no debe llamarse desde el EDT**; quien
 * llama decide el hilo, que es justo lo que necesita el pegado: normalizar en segundo
 * plano y volver al EDT sólo para insertar el texto dentro del comando de deshacer.
 */
@Service(Service.Level.PROJECT)
class AttachmentService(private val project: Project) {

    private val layout = StorageLayout.forProject(project)
    private val store = layout?.let(::AttachmentStore)

    /**
     * Imágenes ya decodificadas y sus versiones escaladas.
     *
     * Con caché porque el renderer de un inlay se ejecuta en cada repintado del
     * editor: sin esto, mover el cursor por el texto releería el PNG del disco.
     *
     * **Acotadas por bytes y no por número de entradas**: ver [ByteBoundedCache], que
     * es donde está el porqué.
     */
    private val decoded = ByteBoundedCache<Key, Holder>(DECODED_BYTES) { it.bytes }
    private val scaled = ByteBoundedCache<ScaledKey, BufferedImage>(SCALED_BYTES, ::imageBytes)

    // ------------------------------------------------------------- escritura

    /**
     * Normaliza y guarda una imagen. **Bloqueante**: llamar fuera del EDT.
     *
     * @return el ID con el que referenciarla, o `null` si no hay dónde escribir
     *   (proyecto sin directorio base) o si falló el disco.
     */
    fun attach(repo: RepoKey, image: Image): AttachmentId? {
        val store = store ?: return null
        val maxSize = TaskService.getInstance(project).snapshot.value.config.imageMaxSize
        return try {
            val id = store.put(repo, ImageNormalizer.normalize(image, maxSize))
            // Que el blob acabe de escribirse no significa que la caché esté vacía:
            // la misma imagen pudo pegarse antes, borrarse y volver.
            decoded.remove(Key(repo, id))
            id
        } catch (e: Exception) {
            thisLogger().warn("Tasklane: no se pudo guardar la imagen pegada", e)
            null
        }
    }

    /** El texto que se inserta en el cuerpo para referenciar [id]. */
    fun reference(id: AttachmentId): String = ImageRefParser.reference(id)

    // -------------------------------------------------------------- lectura

    fun file(repo: RepoKey, id: AttachmentId): Path? =
        store?.path(repo, id)?.takeIf { store.exists(repo, id) }

    /**
     * @return la imagen, o `null` si el blob no está. Ausente no es un error: el
     *   cuerpo puede referenciar algo que ya se recogió, y el inlay lo pinta como
     *   marcador de posición **sin borrar la referencia** — que el texto siga
     *   diciendo que ahí había una imagen es información, no basura.
     */
    fun image(repo: RepoKey, id: AttachmentId): BufferedImage? {
        val store = store ?: return null
        val key = Key(repo, id)
        decoded.get(key)?.let { return it.image }
        val image = store.load(repo, id)
        decoded.put(key, Holder(image))
        return image
    }

    /**
     * La imagen encogida para que quepa en [maxWidth] × [maxHeight], o la original si
     * ya cabe. Nunca se amplía: una captura pequeña ampliada sólo se ve borrosa.
     *
     * El límite de **alto** es tan importante como el de ancho: una captura de página
     * entera escalada sólo por el ancho daría una vista previa de miles de píxeles que
     * echaría el texto de la tarea fuera de la pantalla.
     *
     * La caché va por `(repo, id, ancho resultante)` porque el ancho depende del ancho
     * del editor: redimensionar el diálogo reescala una vez, no en cada repintado.
     */
    fun preview(repo: RepoKey, id: AttachmentId, maxWidth: Int, maxHeight: Int): BufferedImage? {
        if (maxWidth <= 0 || maxHeight <= 0) return null
        val source = image(repo, id) ?: return null

        val factor = minOf(
            1.0,
            maxWidth.toDouble() / source.width,
            maxHeight.toDouble() / source.height,
        )
        if (factor >= 1.0) return source

        val width = (source.width * factor).toInt().coerceAtLeast(1)
        val height = (source.height * factor).toInt().coerceAtLeast(1)

        val key = ScaledKey(repo, id, width)
        scaled.get(key)?.let { return it }

        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        target.createGraphics().run {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(source, 0, 0, width, height, null)
            dispose()
        }
        scaled.put(key, target)
        return target
    }

    // ---------------------------------------------------------- recolección

    /**
     * Borra los blobs que no referencia ninguna tarea. **Bloqueante**: llamar fuera
     * del EDT. Lo dispara [com.tasklane.startup.AttachmentGcActivity] al abrir el
     * proyecto, que es cuando ya se sabe qué hay cargado.
     *
     * Tres salvaguardas, y ninguna es paranoia — esto borra ficheros del usuario:
     *
     * 1. **Las referencias salen de la tabla, no de un recorrido.** Desde la Fase 3 las
     *    mantiene `blob_ref` dentro de la misma transacción que la escritura de la
     *    tarea, así que no hay ventana en la que una imagen recién pegada todavía no
     *    esté referenciada para el recolector.
     * 2. **Nunca los abiertos en solo lectura.** Vienen de una versión más nueva del
     *    plugin, que puede referenciar adjuntos de una forma que esta versión no sabe
     *    leer. Es la misma razón por la que no se reescribe su fichero.
     * 3. **Periodo de gracia**, en [AttachmentGc]: lo recién pegado sobrevive aunque
     *    todavía no lo nombre nadie.
     *
     * @return cuántos ficheros se borraron.
     */
    fun collectGarbage(): Int {
        val store = store ?: return 0
        val tasks = TaskService.getInstance(project)
        val snapshot = tasks.snapshot.value
        val now = Instant.now()
        var deleted = 0

        for (ref in snapshot.repositories) {
            val repo = ref.key
            if (tasks.isReadOnly(repo)) continue
            // De la tabla `blob_ref`, que el almacén mantiene dentro de la misma
            // transacción que la escritura de la tarea. Antes salía de recorrer el
            // corpus en memoria, con la salvaguarda de no mirar los repositorios sin
            // leer; ahora no hay repositorios sin leer, porque no hay corpus.
            val referenced = tasks.referencedBlobs(repo).mapTo(HashSet(), ::AttachmentId)

            for (blob in AttachmentGc.collectible(store.list(repo), referenced, now)) {
                if (store.delete(blob.path)) {
                    decoded.remove(Key(repo, blob.id))
                    deleted++
                }
            }
            // Un `.tmp` sólo puede ser el residuo de una escritura interrumpida: no
            // lo referencia nadie porque nunca llegó a tener nombre definitivo.
            for (tmp in AttachmentGc.collectible(store.orphanTemporaries(repo), emptySet(), now)) {
                if (store.delete(tmp.path)) deleted++
            }
        }

        if (deleted > 0) thisLogger().info("Tasklane: $deleted adjunto(s) sin referencias borrados")
        return deleted
    }

    // ---------------------------------------------------------------- caché

    private data class Key(val repo: RepoKey, val id: AttachmentId)

    private data class ScaledKey(val repo: RepoKey, val id: AttachmentId, val width: Int)

    /** Envoltorio para poder cachear también el «no está»: si no, se releería siempre. */
    private class Holder(val image: BufferedImage?) {
        /** Un «no está» no ocupa nada, pero tiene que poder entrar en la caché igual. */
        val bytes: Long get() = image?.let(::imageBytes) ?: 0L
    }

    companion object {
        /**
         * Lo que puede ocupar el conjunto de imágenes descodificadas.
         *
         * 64 MB es el valor del §2.6, y con el tope de 400 px de la Fase 4 —640 KB por
         * imagen— dan para un centenar largo: mucho más de lo que cabe en una lista.
         * Con las capturas de 1600 px que puede haber ya en disco son seis, que es
         * exactamente el punto: seis imágenes grandes ocupan lo que ocupan, y antes
         * cabían dieciséis sin que nadie llevara la cuenta.
         */
        private const val DECODED_BYTES = 64L * 1024 * 1024

        /** Las escaladas son las que se pintan, y son pequeñas por definición. */
        private const val SCALED_BYTES = 16L * 1024 * 1024

        /** `INT_ARGB`: cuatro bytes por píxel. No hay que estimar nada. */
        private fun imageBytes(image: BufferedImage): Long =
            image.width.toLong() * image.height.toLong() * 4L

        fun getInstance(project: Project): AttachmentService = project.service()
    }
}
