package com.tasklane.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.ui.scale.JBUIScale
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.service.AttachmentService
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D

/**
 * La vista previa de una imagen, pintada como un bloque bajo la línea que la
 * referencia.
 *
 * Es un [com.intellij.openapi.editor.EditorCustomElementRenderer] y no un componente
 * Swing metido en el editor: el inlay se mueve solo con el texto, se recoloca al
 * ajustar líneas y no interfiere con la selección ni con el cursor.
 *
 * El renderer **no lee disco**. Recibe en qué estado está la imagen y, si está
 * lista, pide a [AttachmentService] la versión ya escalada al ancho actual —que va
 * cacheada por `(repo, id, ancho)`—, porque `paint` se ejecuta en cada repintado del
 * editor y reescalar ahí un PNG de 1600 px se notaría al escribir.
 */
internal class ImageInlayRenderer(
    private val service: AttachmentService,
    private val repo: RepoKey,
    private val id: AttachmentId,
    private val state: State,
) : com.intellij.openapi.editor.EditorCustomElementRenderer {

    /** Qué se puede pintar ahora mismo. Cargar es asíncrono; el hueco no espera. */
    enum class State { LOADING, READY, MISSING }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int = availableWidth(inlay)

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = when (state) {
        State.READY -> scaled(inlay)?.height?.plus(MARGIN * 2) ?: placeholderHeight()
        else -> placeholderHeight()
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, region: Rectangle, attributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val x = region.x + MARGIN
            val y = region.y + MARGIN

            val image = if (state == State.READY) scaled(inlay) else null
            if (image != null) {
                g2.drawImage(image, x, y, null)
                // Un borde tenue: sin él, una captura de fondo claro sobre un tema
                // claro se funde con el editor y no se ve dónde acaba.
                g2.color = JBColor.border()
                g2.stroke = BasicStroke(1f)
                g2.draw(
                    RoundRectangle2D.Float(
                        x - 0.5f,
                        y - 0.5f,
                        image.width + 1f,
                        image.height + 1f,
                        ARC.toFloat(),
                        ARC.toFloat(),
                    ),
                )
            } else {
                paintPlaceholder(g2, x, y)
            }
        } finally {
            g2.dispose()
        }
    }

    /**
     * El hueco mientras la imagen carga, y el marcador de la que ya no está.
     *
     * Un adjunto ausente **no borra la referencia**: el texto sigue diciendo que ahí
     * había una imagen, que es información, y borrarlo por su cuenta sería el plugin
     * editando la tarea del usuario sin que nadie se lo pida.
     */
    private fun paintPlaceholder(g2: Graphics2D, x: Int, y: Int) {
        val icon = if (state == State.MISSING) AllIcons.General.Warning else AllIcons.General.InlineRefresh
        icon.paintIcon(null, g2, x, y)
        g2.color = JBColor.GRAY
        val text = TasklaneBundle.message(
            if (state == State.MISSING) "editor.image.missing" else "editor.image.loading",
        )
        val metrics = g2.fontMetrics
        g2.drawString(text, x + icon.iconWidth + JBUIScale.scale(4), y + metrics.ascent)
    }

    private fun scaled(inlay: Inlay<*>) = service.preview(
        repo,
        id,
        availableWidth(inlay) - MARGIN * 2,
        JBUIScale.scale(MAX_PREVIEW_HEIGHT),
    )

    /**
     * El ancho útil del editor. Se mide en el componente y no en la línea porque el
     * bloque ocupa el ancho entero: así redimensionar el diálogo reescala la vista
     * previa en vez de recortarla.
     */
    private fun availableWidth(inlay: Inlay<*>): Int =
        (inlay.editor.contentComponent.width - JBUI.scale(GUTTER_SLACK)).coerceAtLeast(MIN_WIDTH)

    private fun placeholderHeight(): Int = JBUIScale.scale(PLACEHOLDER_HEIGHT)

    private companion object {
        val MARGIN: Int get() = JBUIScale.scale(6)
        val ARC: Int get() = JBUIScale.scale(4)
        const val GUTTER_SLACK = 24
        const val MIN_WIDTH = 64

        /** Una vista previa más alta que esto echaría el texto fuera del diálogo. */
        const val MAX_PREVIEW_HEIGHT = 320
        const val PLACEHOLDER_HEIGHT = 22
    }
}
