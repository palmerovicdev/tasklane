package com.tasklane.ui.common

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.scale.JBUIScale
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.service.AttachmentService

/**
 * El tooltip que **enseña la captura** en vez de describirla.
 *
 * Lo pide el contador de imágenes de la fila (`1 img`): pararse encima tiene que
 * responder la pregunta —qué imagen es— sin desplegar la tarjeta ni abrir la tarea. Una
 * frase ahí no responde nada; es el único distintivo cuyo contenido *es* una imagen.
 *
 * Va como HTML de Swing y no como un componente porque el tooltip de un `JTree` es
 * texto: se pone en `toolTipText` y lo pinta la plataforma con el estilo de los demás
 * tooltips del IDE. Es la misma técnica que el tooltip del margen del editor
 * —`AnchorTooltip`—, incluidos sus dos requisitos:
 *
 * 1. **La ruta es absoluta** (`file:/…`): el HTML de un tooltip no tiene documento base
 *    contra el que resolver una relativa.
 * 2. **El ancho y el alto van escritos**. Sin ellos Swing mide el bloque antes de que la
 *    imagen termine de cargar, decide el tamaño del globo sin contar con ella y la
 *    vista previa sale recortada.
 *
 * **Bloqueante**: lee y mide blobs del disco, así que sólo desde un hilo de fondo. La
 * miniatura (§4.4) y nunca el original: pasar el ratón por una fila no puede costar
 * descodificar una captura de 1600 px.
 */
internal object ImageTooltip {

    /**
     * Las capturas de [ids], una debajo de otra, o `null` si no hay ninguna que
     * enseñar —la tarea referencia blobs que ya no están—. Un recuadro roto dentro de
     * un tooltip se lee como un fallo del plugin, así que lo que no está no deja hueco.
     */
    fun html(service: AttachmentService, repo: RepoKey, ids: List<AttachmentId>): String? {
        val shots = ids.take(MAX).mapNotNull { img(service, repo, it) }
        if (shots.isEmpty()) return null
        val more = ids.size - MAX
        val tail = if (more > 0) {
            "<br>" + StringUtil.escapeXmlEntities(TasklaneBundle.message("toolwindow.row.images.more", more))
        } else {
            ""
        }
        return shots.joinToString("<br>", prefix = "<html><body>", postfix = "$tail</body></html>")
    }

    private fun img(service: AttachmentService, repo: RepoKey, id: AttachmentId): String? {
        val image = service.thumbnail(repo, id) ?: return null
        val file = service.thumbnailFile(repo, id) ?: return null
        val factor = minOf(
            1.0,
            JBUIScale.scale(WIDTH).toDouble() / image.width,
            JBUIScale.scale(HEIGHT).toDouble() / image.height,
        )
        val width = (image.width * factor).toInt().coerceAtLeast(1)
        val height = (image.height * factor).toInt().coerceAtLeast(1)
        // La ruta se escapa como cualquier otro texto: un fichero puede llamarse
        // `a&b.png`, y sin escapar eso rompe el atributo.
        val src = StringUtil.escapeXmlEntities(file.toUri().toString())
        return "<img src=\"$src\" width=\"$width\" height=\"$height\">"
    }

    /**
     * Cuántas caben antes de que el tooltip deje de ser un tooltip. Las que no caben se
     * cuentan: para verlas está el popup, que es lo que abre ese mismo distintivo.
     */
    private const val MAX = 2

    private const val WIDTH = 280
    private const val HEIGHT = 180
}
