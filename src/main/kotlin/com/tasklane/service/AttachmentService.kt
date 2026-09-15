package com.tasklane.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.data.attachment.AttachmentChore
import com.tasklane.data.attachment.AttachmentGc
import com.tasklane.data.attachment.AttachmentQuota
import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.attachment.BlobSweeper
import com.tasklane.data.attachment.ImageNormalizer
import com.tasklane.data.store.StorageLayout
import com.tasklane.diagnostics.TasklaneDiagnostics
import com.tasklane.diagnostics.showDiagnostics
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.ui.settings.TasklaneConfigurable
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Las imágenes de las tareas: guardar lo que se pega, servir lo que se pinta, tirar lo
 * que ya no referencia nadie y **llevar la cuenta de lo que hay**.
 *
 * Es al [AttachmentStore] lo que [TaskService] es al almacén de tareas: la UI no toca
 * disco, habla con esto.
 *
 * ## Lo que la Fase 4 cambió aquí
 *
 * 1. **La contabilidad vive en la base** (§4.2). Cada blob escrito deja su fila en
 *    `blob`, y recoger deja de ser «listar el directorio y descartar lo referenciado»
 *    —el `Files.list` que con diez millones de entradas no termina— para ser una
 *    consulta por índice, a tandas y cancelable.
 * 2. **Las miniaturas** (§4.4). La lista pide [thumbnail] y **nunca** descodifica el
 *    original; el original sólo se lee al ampliar. Es lo que hace que las capturas de
 *    1600 px que ya estén guardadas dejen de pesar aunque no se toquen.
 * 3. **Mantenimiento**: trasladar lo que quedara en el directorio plano (§4.1),
 *    reconciliar tabla y disco (§4.3) y avisar de la cuota (§4.5). Lo dispara
 *    [com.tasklane.startup.MaintenanceActivity], no la UI.
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
     * Imágenes ya descodificadas y sus versiones escaladas.
     *
     * Con caché porque el renderer de un inlay se ejecuta en cada repintado del
     * editor: sin esto, mover el cursor por el texto releería el PNG del disco.
     *
     * **Acotadas por bytes y no por número de entradas**: ver [ByteBoundedCache], que
     * es donde está el porqué. Y **separadas** originales de miniaturas, que es lo que
     * impide que abrir una captura grande en el diálogo eche de la caché las treinta
     * miniaturas que la lista está pintando detrás.
     */
    private val decoded = ByteBoundedCache<Key, Holder>(DECODED_BYTES) { it.bytes }
    private val masters = ByteBoundedCache<Key, Holder>(MASTER_BYTES) { it.bytes }
    private val thumbnails = ByteBoundedCache<Key, Holder>(THUMBNAIL_BYTES) { it.bytes }
    private val scaled = ByteBoundedCache<ScaledKey, BufferedImage>(SCALED_BYTES, ::imageBytes)

    // ------------------------------------------------------------- escritura

    /**
     * Guarda una imagen **a su tamaño**. **Bloqueante**: llamar fuera del EDT.
     *
     * Es el camino de lo que llega como píxeles —una captura en el portapapeles—, que hay
     * que codificar a PNG. Hasta la 2.2 se reescalaba antes al tope de los ajustes; desde
     * la 2.3 no, ver [ImageNormalizer]. La miniatura se saca **de los píxeles que ya están
     * en memoria**, que cuesta un par de milisegundos, en vez de dejársela a la lista, que
     * tendría que volver a descodificar el original para hacerla.
     *
     * Deja en disco el blob, su miniatura si hace falta y el `.gitignore` de los datos, y
     * en la base la fila de contabilidad. La fila va **después** del fichero a propósito:
     * una fila sin fichero es una imagen que la lista pinta como ausente y la
     * reconciliación arregla; un fichero sin fila es peso que nadie contaría.
     *
     * @return el ID con el que referenciarla, o `null` si no hay dónde escribir
     *   (proyecto sin directorio base) o si falló el disco.
     */
    fun attach(repo: RepoKey, image: Image): AttachmentId? {
        val store = store ?: return null
        return try {
            val now = Instant.now()
            val pixels = ImageNormalizer.pixels(image)
            val bytes = ImageNormalizer.encode(pixels)
            val id = store.put(repo, bytes)
            runCatching { ImageNormalizer.thumbnail(pixels)?.let { store.putThumbnail(repo, id, it) } }
                .onFailure { thisLogger().warn("Tasklane: no se pudo crear la miniatura de ${id.value}", it) }
            record(repo, id, bytes.size.toLong(), ImageNormalizer.Size(pixels.width, pixels.height), now)
            id
        } catch (e: Exception) {
            thisLogger().warn("Tasklane: no se pudo guardar la imagen pegada", e)
            null
        }
    }

    /**
     * Guarda un fichero de imagen **tal cual**: sus bytes, sin descodificarlo ni volver a
     * codificarlo. Es el camino de soltar, elegir y pegar un fichero copiado.
     *
     * Lo único que se mira del contenido es la cabecera, por dos razones: saber que es una
     * imagen que se podrá pintar —un `.webp` sin lector en esta JVM se guardaría para
     * enseñarse siempre como ausente— y apuntar su tamaño en la tabla. La miniatura no se
     * hace aquí: haría falta descodificar el original entero, y la lista ya la hace la
     * primera vez que la pinta, en segundo plano.
     *
     * @return `null` si no es una imagen legible o si falló el disco.
     */
    fun attachFile(repo: RepoKey, file: Path): AttachmentId? {
        val store = store ?: return null
        return try {
            val now = Instant.now()
            val bytes = Files.readAllBytes(file)
            val size = ImageNormalizer.sizeOf(bytes)
            if (size == null) {
                thisLogger().warn("Tasklane: ${file.fileName} no es una imagen que se pueda leer")
                return null
            }
            val id = store.put(repo, bytes)
            record(repo, id, bytes.size.toLong(), size, now)
            id
        } catch (e: Exception) {
            thisLogger().warn("Tasklane: no se pudo guardar la imagen ${file.fileName}", e)
            null
        }
    }

    private fun record(repo: RepoKey, id: AttachmentId, bytes: Long, size: ImageNormalizer.Size, now: Instant) {
        TaskService.getInstance(project).recordBlob(repo, BlobRecord(id, bytes, size.width, size.height, now), now)
        // Que el blob acabe de escribirse no significa que la caché esté vacía: la misma
        // imagen pudo pegarse antes, borrarse y volver.
        forget(repo, id)
    }

    /** El texto que se inserta en el cuerpo para referenciar [id]. */
    fun reference(id: AttachmentId): String = ImageRefParser.reference(id)

    // -------------------------------------------------------------- lectura

    fun file(repo: RepoKey, id: AttachmentId): Path? = store?.locate(repo, id)

    /**
     * El fichero de la miniatura si existe, y si no el del original.
     *
     * Lo pide el tooltip del margen, que pinta la captura con un `<img src>` y por tanto
     * necesita **una ruta** y no una imagen. Que prefiera la miniatura es lo que evita
     * que pasar el ratón por un ancla mande a Swing a descodificar una captura de
     * 1600 px para enseñarla a 200.
     */
    fun thumbnailFile(repo: RepoKey, id: AttachmentId): Path? =
        store?.let { it.locate(repo, id, thumbnail = true) ?: it.locate(repo, id) }

    /**
     * La imagen **original**, descodificada. **Nunca desde el EDT**: desde la 2.3 un
     * original es una captura a su tamaño —33 MB descodificada a 2880 px, y 45 ms de
     * disco y CPU—.
     *
     * La pide un solo sitio, el popup de ampliar, y la pide en segundo plano. El diálogo
     * pinta desde [master] y la lista desde [thumbnail].
     *
     * @return `null` si el blob no está. Ausente no es un error: el cuerpo puede
     *   referenciar algo que ya se recogió, y quien pinta lo resuelve con un marcador
     *   **sin borrar la referencia** — que el texto siga diciendo que ahí había una
     *   imagen es información, no basura.
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
     * La imagen a un tamaño **acotado** —[MASTER_SIZE] de lado mayor—: de donde saca el
     * diálogo de la tarea sus vistas previas. **Bloqueante la primera vez**: la descodifica
     * `ImageInlays` en segundo plano antes de marcarla como lista.
     *
     * Existe por la 2.3. Con el tope de 400 px, el diálogo pintaba escalando el original
     * en el EDT y no pasaba nada: un original eran 640 KB y cabían cien en la caché. Con
     * las capturas a su tamaño, un original son 33 MB, en la caché de 64 caben dos, y
     * repintar un diálogo con tres imágenes volvería a descodificar del disco **en el
     * EDT** en cada pasada. Una copia de 1280 px son 4 MB, caben ocho, y escalar desde ella
     * al ancho del diálogo son milisegundos.
     *
     * No se guarda en disco: se deriva al vuelo, sólo de lo que un diálogo abierto enseña.
     */
    fun master(repo: RepoKey, id: AttachmentId): BufferedImage? {
        val store = store ?: return null
        val key = Key(repo, id)
        masters.get(key)?.let { return it.image }
        // Directo del disco y no por [image]: meter en la caché de originales una captura
        // de 33 MB sólo para encogerla echaría de ahí lo que el popup acaba de abrir.
        val original = store.load(repo, id)
        val master = original?.let { shrink(it, MASTER_SIZE) }
        masters.put(key, Holder(master))
        return master
    }

    /**
     * La miniatura: lo que pinta la lista (§4.4).
     *
     * Tres caminos, en este orden:
     *
     * 1. El fichero `<sha>.thumb.png`, que es el caso normal.
     * 2. **No hay miniatura porque no merecía la pena** —el original no llega a
     *    [ImageNormalizer.THUMB_THRESHOLD]—: se devuelve el original. Un fichero que no
     *    se escribe es un fichero que no hay que recolectar ni contar.
     * 3. **No hay miniatura y el original es grande**: se descodifica una vez, se
     *    escribe la miniatura y a partir de ahí manda el camino 1. Es la generación
     *    perezosa que el §4.4 pide para lo que ya estaba guardado, y desde la 2.3 también
     *    el camino normal de un fichero soltado, que se guarda sin descodificar. Ocurre
     *    **una vez por blob y en segundo plano**, nunca en el EDT.
     */
    fun thumbnail(repo: RepoKey, id: AttachmentId): BufferedImage? {
        val store = store ?: return null
        val key = Key(repo, id)
        thumbnails.get(key)?.let { return it.image }

        var image = store.load(repo, id, thumbnail = true)
        if (image == null) {
            val original = store.load(repo, id)
            image = when {
                original == null -> null
                !ImageNormalizer.needsThumbnail(original.width, original.height) -> original
                else -> {
                    val bytes = runCatching { ImageNormalizer.thumbnail(original) }
                        .onFailure { thisLogger().warn("Tasklane: no se pudo crear la miniatura de $id", it) }
                        .getOrNull()
                    if (bytes != null) {
                        runCatching { store.putThumbnail(repo, id, bytes) }
                        store.load(repo, id, thumbnail = true) ?: original
                    } else {
                        original
                    }
                }
            }
        }
        thumbnails.put(key, Holder(image))
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
     *
     * Parte de [master], no del original: es lo que pinta el diálogo, en el EDT.
     */
    fun preview(repo: RepoKey, id: AttachmentId, maxWidth: Int, maxHeight: Int): BufferedImage? =
        fit(master(repo, id), repo, id, maxWidth, maxHeight)

    /**
     * Lo mismo pero **desde el original**: lo que abre el popup de ampliar, que es donde se
     * quiere cada píxel. **Bloqueante**: el popup la pide en segundo plano.
     */
    fun fullPreview(repo: RepoKey, id: AttachmentId, maxWidth: Int, maxHeight: Int): BufferedImage? =
        fit(image(repo, id), repo, id, maxWidth, maxHeight)

    /**
     * Lo mismo, pero **partiendo de la miniatura**: es lo que pide la lista.
     *
     * Que salga más pequeña que el hueco de la tarjeta es exactamente lo que se quiere:
     * una vista previa de 256 px en una tarjeta ancha se ve igual de bien y cuesta
     * cuarenta veces menos que descodificar una captura de 1600. Para mirar de cerca
     * está el popup, que sí va al original.
     */
    fun cardPreview(repo: RepoKey, id: AttachmentId, maxWidth: Int, maxHeight: Int): BufferedImage? =
        fit(thumbnail(repo, id), repo, id, maxWidth, maxHeight)

    /** [source] encogida a [maxSize] de lado mayor, o ella misma si ya cabe. */
    private fun shrink(source: BufferedImage, maxSize: Int): BufferedImage {
        val factor = maxSize.toDouble() / maxOf(source.width, source.height)
        if (factor >= 1.0) return source
        val width = (source.width * factor).toInt().coerceAtLeast(1)
        val height = (source.height * factor).toInt().coerceAtLeast(1)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
            target.createGraphics().run {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(source, 0, 0, width, height, null)
                dispose()
            }
        }
    }

    private fun fit(
        source: BufferedImage?,
        repo: RepoKey,
        id: AttachmentId,
        maxWidth: Int,
        maxHeight: Int,
    ): BufferedImage? {
        if (maxWidth <= 0 || maxHeight <= 0 || source == null) return null

        val factor = minOf(
            1.0,
            maxWidth.toDouble() / source.width,
            maxHeight.toDouble() / source.height,
        )
        if (factor >= 1.0) return source

        val width = (source.width * factor).toInt().coerceAtLeast(1)
        val height = (source.height * factor).toInt().coerceAtLeast(1)

        val key = ScaledKey(repo, id, width, source.width)
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
     * del EDT. Lo dispara [com.tasklane.startup.MaintenanceActivity].
     *
     * Tres salvaguardas, y ninguna es paranoia — esto borra ficheros del usuario:
     *
     * 1. **Los candidatos salen de la tabla, no de un recorrido.** `blob_ref` la
     *    mantiene el almacén dentro de la misma transacción que la escritura de la
     *    tarea, así que no hay ventana en la que una imagen recién pegada todavía no
     *    esté referenciada para el recolector. Y sólo se recolectan los repositorios
     *    cuyas referencias están completas: ver `TaskService.referencesComplete`.
     *    Cada tanda vuelve a preguntar por sus mil candidatos antes de borrar nada —y
     *    por los suyos, no por todos: un conjunto de diez millones de referencias en
     *    memoria sería el mismo error que esta fase vino a corregir—.
     * 2. **Nunca los abiertos en solo lectura.** Vienen de una versión más nueva del
     *    plugin, que puede referenciar adjuntos de una forma que esta versión no sabe
     *    leer. Es la misma razón por la que no se reescribe su fichero.
     * 3. **Periodo de gracia**, en [AttachmentGc]: lo recién pegado sobrevive aunque
     *    todavía no lo nombre nadie.
     *
     * Va **a tandas** y mira [cancelled] entre una y otra: con diez millones de blobs,
     * una recolección que no se pudiera parar sería una recolección que hay que esperar
     * a que termine para cerrar el IDE.
     *
     * @return cuántos ficheros se borraron.
     */
    fun collectGarbage(cancelled: () -> Boolean = { false }): Int {
        val sweeper = sweeper() ?: return 0
        val tasks = TaskService.getInstance(project)
        val now = Instant.now()
        var deleted = 0

        for (ref in tasks.snapshot.value.repositories) {
            if (cancelled()) break
            val repo = ref.key
            if (!tasks.referencesComplete(repo)) continue
            deleted += sweeper.collect(repo, now, flatToo = flatToo(repo), cancelled = cancelled) { forget(repo, it) }.files
        }

        if (deleted > 0) thisLogger().info("Tasklane: $deleted adjunto(s) sin referencias borrados")
        return deleted
    }

    // ------------------------------------------------ limpiar desde los ajustes (2.3)

    /** Lo que hizo una limpieza pedida desde los ajustes. */
    sealed interface Cleanup {
        /** Se borraron [images] imágenes y se liberaron [bytes]. [complete] es `false` si se canceló. */
        data class Done(val images: Int, val bytes: Long, val complete: Boolean) : Cleanup

        /**
         * No se tocó nada: el repositorio se está importando, es de solo lectura o no hay
         * almacén. Borrar «lo que no usa nadie» con las referencias a medias sería borrar
         * imágenes que sí se usan.
         */
        data object Refused : Cleanup
    }

    /**
     * Borra **ya** las imágenes de [repo] que no nombra ninguna tarea: fichero, miniatura
     * y fila. Es el botón «borrar las no usadas» de los ajustes, y es siempre del
     * repositorio activo.
     *
     * Dos diferencias con el recolector del mantenimiento, y las dos son lo que el usuario
     * pide al pulsarlo:
     *
     * 1. **Sin periodo de gracia.** Ver `BlobSweeper.collect` para por qué aquí no hace
     *    falta.
     * 2. **Primero se reconcilia el árbol** de ese repositorio, sin esperar a la semana.
     *    Si no, lo que haya en disco sin fila —de una versión anterior, de una copia del
     *    `.idea`— no existiría para la limpieza, y «no usadas» dejaría peso sin tocar.
     *
     * Las mismas salvaguardas de siempre: sólo con las referencias del repositorio
     * completas, y la segunda comprobación por tanda antes de borrar.
     *
     * **Bloqueante**: con barra y fuera del EDT.
     */
    fun purgeUnused(repo: RepoKey, cancelled: () -> Boolean = { false }): Cleanup {
        val sweeper = sweeper() ?: return Cleanup.Refused
        val tasks = TaskService.getInstance(project)
        if (!tasks.referencesComplete(repo)) return Cleanup.Refused

        reconcile(force = true, only = repo, cancelled = cancelled)
        if (cancelled()) return Cleanup.Done(0, 0, complete = false)

        val swept = sweeper.collect(repo, Instant.now(), Duration.ZERO, flatToo(repo), cancelled) { forget(repo, it) }
        finish(repo)
        return Cleanup.Done(swept.files, swept.bytes, swept.complete)
    }

    /**
     * Borra **todas** las imágenes de [repo], se usen o no. Es el botón «borrar todas» de
     * los ajustes, siempre del repositorio activo y siempre tras confirmarlo.
     *
     * Las tareas no se tocan: sus cuerpos siguen nombrando las imágenes y la tarjeta pinta
     * el hueco. Ver `BlobSweeper.deleteAll`, que es donde está el orden y el porqué.
     *
     * [onFile] recibe cuántos ficheros van borrados.
     */
    fun purgeAll(repo: RepoKey, cancelled: () -> Boolean = { false }, onFile: (Long) -> Unit = {}): Cleanup {
        val sweeper = sweeper() ?: return Cleanup.Refused
        val tasks = TaskService.getInstance(project)
        if (tasks.isReadOnly(repo)) return Cleanup.Refused

        val images = tasks.blobStatsOf(repo).present
        val swept = sweeper.deleteAll(
            repo,
            cancelled,
            onFile,
            // A medias, la tabla ya no cuadra con el disco: que la reconciliación pase en
            // la próxima apertura en vez de esperar a que se cumpla la semana.
            onIncomplete = { tasks.forgetChore(AttachmentChore.reconcile(repo.value)) },
        )
        finish(repo)
        return Cleanup.Done(if (swept.complete) images else 0, swept.bytes, swept.complete)
    }

    /**
     * Lo que hay que hacer después de quitar imágenes a mano: olvidar lo descodificado,
     * que la lista vuelva a preguntar y que el aviso de cuota mire la cifra nueva.
     */
    private fun finish(repo: RepoKey) {
        forgetRepo(repo)
        epoch++
        TaskService.getInstance(project).refreshView()
        runCatching { checkQuota() }
    }

    /**
     * Sube cada vez que se quitan imágenes a mano. La lista lo mira en cada repintado: una
     * imagen que ya había cargado como presente no vuelve a preguntarse sola, y sin esto
     * una tarjeta seguiría creyendo que la tiene.
     */
    @Volatile
    var epoch: Long = 0L
        private set

    private fun sweeper(): BlobSweeper? {
        val store = store ?: return null
        val tasks = TaskService.getInstance(project)
        return BlobSweeper(
            store,
            object : BlobSweeper.Ledger {
                override fun collectible(repo: RepoKey, before: Instant, limit: Int) = tasks.collectibleBlobs(repo, before, limit)

                override fun referencedAmong(repo: RepoKey, ids: List<AttachmentId>) =
                    tasks.referencedAmong(repo, ids).mapTo(HashSet(), ::AttachmentId)

                override fun forget(repo: RepoKey, ids: List<AttachmentId>) = tasks.forgetBlobs(repo, ids)

                override fun forgetBatch(repo: RepoKey): Int = tasks.forgetBlobBatch(repo)
            },
        )
    }

    /**
     * Si hay que buscar también en el directorio plano. Con el traslado ya terminado, ahí
     * no queda nada y serían dos `unlink` por blob que no pueden encontrar nada.
     */
    private fun flatToo(repo: RepoKey): Boolean =
        TaskService.getInstance(project).chore(AttachmentChore.relocation(repo.value)) == null

    // -------------------------------------------------- traslado y reconciliación

    /**
     * Mueve al árbol fragmentado lo que quedara en el directorio plano (§4.1).
     *
     * Por tandas y con marca en `chore`: cuando no queda nada por mover, no se vuelve a
     * mirar. Mientras tanto, todo se sigue leyendo —[AttachmentStore.locate] prueba las
     * dos rutas—, así que pararlo a medias no rompe nada.
     *
     * @return cuántos ficheros se trasladaron.
     */
    fun relocate(cancelled: () -> Boolean = { false }): Int {
        val store = store ?: return 0
        val tasks = TaskService.getInstance(project)
        var moved = 0

        for (ref in tasks.snapshot.value.repositories) {
            val repo = ref.key
            val chore = AttachmentChore.relocation(repo.value)
            // La marca se pone **sólo cuando se terminó**, así que su mera presencia
            // quiere decir «este directorio ya está vacío»: no hay que volver a mirarlo.
            if (tasks.chore(chore) != null) continue

            var here = 0
            var remaining = true
            while (remaining && !cancelled()) {
                val pass = store.relocate(repo, cancelled = cancelled)
                here += pass.moved
                remaining = pass.remaining
                // Ni uno movido y todavía queda: algo impide moverlos —permisos, un
                // volumen de red— e insistir sería un bucle. Se deja para otra apertura.
                if (pass.moved == 0 && pass.remaining) break
            }
            moved += here
            if (!remaining) tasks.saveChore(chore, Instant.now(), here.toLong())
        }

        if (moved > 0) thisLogger().info("Tasklane: $moved adjunto(s) trasladados al árbol fragmentado")
        return moved
    }

    /** Lo que encontró una reconciliación. */
    data class Reconciliation(val adopted: Int, val missing: Int, val temporaries: Int)

    /**
     * Reconcilia disco y tabla (§4.3): lo que hay pero no se sabía, y lo que se sabía
     * pero ya no está.
     *
     * **Como mucho una vez por semana y en segundo plano.** Es el único sitio que vuelve
     * a mirar todos los ficheros, y lo que detecta es deriva causada **desde fuera del
     * plugin** —un `rm`, una copia de seguridad restaurada a medias, un `.idea`
     * sincronizado a mano—: nada de eso pasa a diario.
     *
     * En las dos direcciones:
     *
     * - **Blobs que la tabla no conoce**: se adoptan *con la fecha del fichero*, así que
     *   entran en el periodo de gracia como si siempre hubieran estado apuntados. Uno de
     *   hace un año que no nombra nadie se recoge en la pasada siguiente, que es lo que
     *   se quiere de lo que quedó suelto.
     * - **Filas cuyo fichero ya no está**: se marcan ausentes, que es lo que la tarjeta
     *   ya sabe pintar.
     * - **Temporales de una escritura interrumpida**: se borran si son viejos. Nunca los
     *   referencia nadie, porque no llegaron a tener nombre definitivo.
     *
     * Un recorrido **cancelado no concluye nada**: ni se marca lo ausente —no se ha
     * visto el árbol entero, así que «no lo he visto» no significa «no está»— ni se
     * apunta la marca semanal.
     */
    fun reconcile(force: Boolean = false, only: RepoKey? = null, cancelled: () -> Boolean = { false }): Reconciliation {
        val store = store ?: return Reconciliation(0, 0, 0)
        val tasks = TaskService.getInstance(project)
        val now = Instant.now()
        var adopted = 0
        var missing = 0
        var temporaries = 0

        val repos = only?.let(::listOf) ?: tasks.snapshot.value.repositories.map { it.key }
        for (repo in repos) {
            val chore = AttachmentChore.reconcile(repo.value)
            val last = tasks.chore(chore)
            if (!force && last != null &&
                now.toEpochMilli() - last.at < AttachmentChore.RECONCILE_EVERY_MILLIS
            ) {
                continue
            }

            val stamp = Instant.now()
            var here = 0
            var flat = 0
            val complete = store.scan(repo, cancelled = cancelled) { batch ->
                flat += batch.flat
                val ids = batch.blobs.map { it.id }
                val known = tasks.knownBlobs(repo, ids)
                val fresh = batch.blobs.filter { it.id.value !in known }.map { blob ->
                    // La cabecera del PNG, no la imagen: veinticuatro bytes por fichero
                    // en vez de descodificar millones de capturas.
                    val size = store.dimensionsOf(blob.path)
                    BlobRecord(
                        id = blob.id,
                        bytes = blob.size,
                        width = size?.width ?: 0,
                        height = size?.height ?: 0,
                        createdAt = blob.modified,
                    )
                }
                tasks.reconcileBlobs(repo, ids, fresh, stamp)
                here += fresh.size

                for (temp in batch.temporaries) {
                    if (temp.modified.isBefore(now.minus(AttachmentGc.DEFAULT_GRACE)) && store.delete(temp.path)) {
                        temporaries++
                    }
                }
            }

            adopted += here
            if (!complete) continue
            missing += tasks.markMissingBlobs(repo, stamp)
            tasks.saveChore(chore, now, here.toLong())

            // Y si han vuelto a aparecer blobs en el directorio plano, el traslado del
            // §4.1 ya no está terminado por mucho que su marca lo diga. La única forma de
            // que eso pase es que una versión anterior del plugin haya escrito sobre el
            // mismo proyecto — deriva desde fuera, que es justo lo que este recorrido
            // existe para detectar—. Se borra la marca y la apertura siguiente los mueve.
            if (flat > 0) {
                thisLogger().info("Tasklane: $flat adjunto(s) han vuelto al directorio plano de $repo")
                tasks.forgetChore(AttachmentChore.relocation(repo.value))
            }
        }

        return Reconciliation(adopted, missing, temporaries)
    }

    // ---------------------------------------------------------------- cuota

    /**
     * Mira lo que ocupan las imágenes **del repositorio activo** y avisa si cruza el
     * umbral (§4.5).
     *
     * **Por repositorio desde la 2.3**, como todo lo demás de las imágenes: el aviso lleva
     * a los ajustes, y lo que los ajustes enseñan y limpian es el repositorio activo. Un
     * aviso por el total del proyecto diría una cifra que no aparece en ningún sitio.
     *
     * **No borra nada, y ése es el punto.** Se enseña el peso, se dicen las salidas y
     * decide el usuario. El aviso no sale en cada apertura —ver [AttachmentQuota]—, y el
     * umbral se puede subir o apagar desde los ajustes.
     *
     * @return `true` si se avisó.
     */
    fun checkQuota(): Boolean {
        val tasks = TaskService.getInstance(project)
        val snapshot = tasks.snapshot.value
        val repo = snapshot.activeRepo
        val limit = AttachmentQuota.bytesOf(snapshot.config.imageQuotaMegabytes)
        val bytes = tasks.blobStatsOf(repo).bytes
        val chore = AttachmentChore.quota(repo.value)
        val warned = tasks.chore(chore)?.n ?: 0L

        return when (val decision = AttachmentQuota.decide(bytes, limit, warned)) {
            AttachmentQuota.Decision.Quiet -> false
            AttachmentQuota.Decision.Forget -> {
                tasks.saveChore(chore, Instant.now(), 0L)
                false
            }

            is AttachmentQuota.Decision.Warn -> {
                tasks.saveChore(chore, Instant.now(), decision.bytes)
                warn(snapshot.activeRepository?.displayName ?: repo.value, decision.bytes, limit)
                true
            }
        }
    }

    private fun warn(repo: String, bytes: Long, limit: Long) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(TaskService.NOTIFICATION_GROUP)
            .createNotification(
                TasklaneBundle.message("notification.quota.title", repo, TasklaneDiagnostics.humanBytes(bytes)),
                TasklaneBundle.message("notification.quota.content", TasklaneDiagnostics.humanBytes(limit)),
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimple(TasklaneBundle.message("notification.quota.settings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, TasklaneConfigurable::class.java)
                },
            )
            .addAction(
                NotificationAction.createSimple(TasklaneBundle.message("notification.quota.report")) {
                    showDiagnostics(project)
                },
            )
            .notify(project)
    }

    // ---------------------------------------------------------------- caché

    /**
     * Olvida lo descodificado de un blob.
     *
     * Lo escalado no: su clave lleva el ancho, así que no se puede quitar por prefijo —y
     * no hace falta, porque sale de lo descodificado y la caché lo acaba echando por
     * peso—.
     */
    private fun forget(repo: RepoKey, id: AttachmentId) {
        decoded.remove(Key(repo, id))
        masters.remove(Key(repo, id))
        thumbnails.remove(Key(repo, id))
    }

    /** Olvida todo lo descodificado de un repositorio, escalado incluido. */
    private fun forgetRepo(repo: RepoKey) {
        decoded.removeIf { it.repo == repo }
        masters.removeIf { it.repo == repo }
        thumbnails.removeIf { it.repo == repo }
        scaled.removeIf { it.repo == repo }
    }

    private data class Key(val repo: RepoKey, val id: AttachmentId)

    /**
     * [source] es el ancho del que se escaló: la misma imagen puede venir del original o
     * de su miniatura, y sin eso en la clave la lista se quedaría con la versión que
     * hubiera escalado antes el diálogo — o al revés, que es peor: una miniatura ampliada
     * donde se esperaba el original.
     */
    private data class ScaledKey(val repo: RepoKey, val id: AttachmentId, val width: Int, val source: Int)

    /** Envoltorio para poder cachear también el «no está»: si no, se releería siempre. */
    private class Holder(val image: BufferedImage?) {
        /** Un «no está» no ocupa nada, pero tiene que poder entrar en la caché igual. */
        val bytes: Long get() = image?.let(::imageBytes) ?: 0L
    }

    companion object {
        /**
         * Lo que puede ocupar el conjunto de imágenes **originales** descodificadas.
         *
         * 64 MB es el valor del §2.6. Con capturas a su tamaño —desde la 2.3, una de
         * 2880 px son 33 MB descodificada— dan para dos, y es exactamente el punto: lo
         * grande ocupa lo que ocupa, y sólo lo piden el diálogo y el popup de ampliar, que
         * enseñan una cada vez. La lista va por miniaturas y no pasa por aquí.
         */
        private const val DECODED_BYTES = 64L * 1024 * 1024

        /**
         * El lado mayor de la copia de la que pinta el diálogo. Ver [master]. 1280 px es
         * más de lo que mide el editor de una tarea en cualquier pantalla razonable, así que
         * la vista previa sale igual de nítida que desde el original.
         */
        const val MASTER_SIZE = 1280

        /** Ocho copias de 1280×800: más imágenes de las que caben en un diálogo abierto. */
        private const val MASTER_BYTES = 32L * 1024 * 1024

        /**
         * Y lo que pueden ocupar las miniaturas, aparte.
         *
         * 16 MB son ~64 miniaturas de 256 px, bastante más de lo que cabe en una lista.
         * Separadas de las originales para que abrir una captura grande en el diálogo no
         * eche de la caché lo que la lista está pintando por detrás.
         */
        private const val THUMBNAIL_BYTES = 16L * 1024 * 1024

        /** Las escaladas son las que se pintan, y son pequeñas por definición. */
        private const val SCALED_BYTES = 16L * 1024 * 1024

        /** Cuántos candidatos se recogen por tanda. Es el `LIMIT 1000` del §4.2. */
        const val GC_BATCH = BlobSweeper.BATCH

        /** `INT_ARGB`: cuatro bytes por píxel. No hay que estimar nada. */
        private fun imageBytes(image: BufferedImage): Long =
            image.width.toLong() * image.height.toLong() * 4L

        fun getInstance(project: Project): AttachmentService = project.service()
    }
}
