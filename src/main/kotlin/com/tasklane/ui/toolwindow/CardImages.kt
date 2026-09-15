package com.tasklane.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.service.AttachmentService
import java.awt.image.BufferedImage

/**
 * Lo único que el renderer necesita saber de una imagen: en qué estado está y, si ya
 * está, cómo se ve encogida.
 *
 * Es una interfaz y no la clase directamente por el mismo motivo que [TaskTreeRenderer]
 * inyecta el formato de fecha y el reloj: la implementación de verdad necesita la
 * `Application` del IDE, y sin este hueco la única parte de la UI con aritmética de
 * posiciones no se podría medir en un test normal.
 */
internal interface CardPreviews {

    fun stateOf(repo: RepoKey, id: AttachmentId): CardImageView.State

    /**
     * La imagen encogida para que quepa. Sólo debe llamarse con una imagen ya cargada
     * —[stateOf] devolviendo `READY`—: con cualquier otra, la implementación real iría
     * al disco desde el EDT.
     */
    fun preview(repo: RepoKey, id: AttachmentId, maxWidth: Int, maxHeight: Int): BufferedImage?
}

/**
 * De dónde saca la lista las vistas previas de las tarjetas desplegadas.
 *
 * Existe para poner **una frontera entre el renderer y el disco**. El renderer se
 * ejecuta en cada repintado y en cada movimiento del ratón sobre la lista, así que no
 * puede permitirse una lectura de fichero: lo único que hace es preguntar en qué
 * estado está cada imagen —[stateOf]— y, si ya está, pedirla escalada, que a esas
 * alturas es un acierto de caché en `AttachmentService`.
 *
 * La primera vez que se ve una imagen se **decodifica en segundo plano** —su miniatura,
 * no el original— y se avisa con [onLoaded] cuando llega. El hueco no espera al disco:
 * hasta entonces la tarjeta pinta el marcador de carga, igual que el editor del diálogo.
 *
 * `ModalityState.any()` en la vuelta al EDT por lo mismo que en el diálogo: sin ella
 * la respuesta se quedaría en la cola mientras haya cualquier modal abierto, y el
 * usuario vería marcadores de carga eternos detrás de él.
 */
internal class CardImages(project: Project, private val onLoaded: () -> Unit) : CardPreviews {

    private val service = AttachmentService.getInstance(project)

    /** `true` = la imagen está; `false` = el blob no aparece; ausente = sin cargar. */
    private val loaded = HashMap<Key, Boolean>()
    private val loading = HashSet<Key>()

    override fun stateOf(repo: RepoKey, id: AttachmentId): CardImageView.State {
        val key = Key(repo, id)
        return when (loaded[key]) {
            true -> CardImageView.State.READY
            false -> CardImageView.State.MISSING
            null -> {
                load(key)
                CardImageView.State.LOADING
            }
        }
    }

    override fun preview(repo: RepoKey, id: AttachmentId, maxWidth: Int, maxHeight: Int): BufferedImage? =
        service.cardPreview(repo, id, maxWidth, maxHeight)

    /**
     * Olvida las que no estaban, para que vuelvan a intentarse.
     *
     * Lo llama la pestaña en cada repintado. Una imagen puede aparecer **después** de
     * haberse buscado —se pega una captura en una tarea que ya referenciaba ese
     * blob—, y sin esto la tarjeta se quedaría enseñando «Image not found» para
     * siempre. Reintentar es barato: el servicio cachea también lo que no encontró,
     * así que la comprobación es una consulta a un mapa.
     */
    fun forgetMissing() {
        loaded.values.removeAll { !it }
    }

    private fun load(key: Key) {
        if (!loading.add(key)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            // La MINIATURA, nunca el original (§4.4). Una tarjeta desplegada con diez
            // imágenes son diez `BufferedImage` vivos a la vez; con las capturas de
            // 1600 px que puede haber guardadas de antes eso son 102 MB, y con sus
            // miniaturas son 2,6. La primera vez que se ve una imagen grande, esta
            // llamada además la crea — una vez por blob, aquí, en el hilo de fondo.
            val present = service.thumbnail(key.repo, key.id) != null
            ApplicationManager.getApplication().invokeLater(
                {
                    loading -= key
                    loaded[key] = present
                    onLoaded()
                },
                ModalityState.any(),
            )
        }
    }

    private data class Key(val repo: RepoKey, val id: AttachmentId)
}
