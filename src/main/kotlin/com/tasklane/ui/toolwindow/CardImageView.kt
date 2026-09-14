package com.tasklane.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AttachmentId
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * Una vista previa dentro de una tarjeta desplegada.
 *
 * Es la misma imagen, con el mismo borde y el mismo marcador de carga que pinta el
 * diálogo —[com.tasklane.ui.editor.ImageInlayRenderer]—, porque son la misma cosa
 * vista en dos sitios: si la lista la enseñara de otra forma, abrir la tarea parecería
 * enseñar otra imagen. Lo que cambia es el soporte: allí es un inlay del editor y aquí
 * un componente más de la pila de líneas de la fila, que es lo único que [RowStack]
 * sabe apilar.
 *
 * **No lee disco y no escala.** Recibe la imagen ya escalada al ancho de la tarjeta
 * —[CardImages] la pide a la caché del servicio— porque este componente se pinta en
 * cada repintado del árbol, y reescalar ahí un PNG de 1600 px se notaría al mover el
 * ratón por la lista.
 *
 * Como el resto del renderer, el componente se **reutiliza**: el pozo de vistas vive
 * en [TaskTreeRenderer] y cada fila le pone encima su imagen.
 */
internal class CardImageView : JComponent() {

    /** Qué se puede pintar ahora mismo. Cargar es asíncrono; el hueco no espera. */
    enum class State { LOADING, READY, MISSING }

    var state: State = State.LOADING

    /** La imagen ya escalada, o `null` si todavía no está o no aparece. */
    var image: BufferedImage? = null

    /**
     * Qué adjunto se está pintando, **sólo** cuando se ve de verdad. Es lo que
     * devuelve el *hit testing* de la fila para ampliarla de un clic; un marcador de
     * carga no lleva a ningún sitio, así que ahí no hay nada que pulsar.
     */
    val attachment: AttachmentId?
        get() = id.takeIf { state == State.READY && image != null }

    var id: AttachmentId? = null

    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension {
        val ready = image.takeIf { state == State.READY }
            ?: return Dimension(0, JBUIScale.scale(PLACEHOLDER_HEIGHT))
        val margin = JBUIScale.scale(MARGIN)
        return Dimension(ready.width + margin * 2, ready.height + margin * 2)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val margin = JBUIScale.scale(MARGIN)
            val ready = image.takeIf { state == State.READY }
            if (ready == null) {
                paintPlaceholder(g2, margin, margin)
                return
            }
            g2.drawImage(ready, margin, margin, null)
            // Un borde tenue: sin él, una captura de fondo claro sobre un tema claro
            // se funde con la tarjeta y no se ve dónde acaba.
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1f)
            val arc = JBUIScale.scale(ARC).toFloat()
            g2.draw(
                RoundRectangle2D.Float(
                    margin - 0.5f,
                    margin - 0.5f,
                    ready.width + 1f,
                    ready.height + 1f,
                    arc,
                    arc,
                ),
            )
        } finally {
            g2.dispose()
        }
    }

    /**
     * El hueco mientras la imagen carga, y el marcador de la que ya no está.
     *
     * Un adjunto ausente **no se esconde**: el cuerpo sigue diciendo que ahí había una
     * imagen, que es información. Es la misma regla que en el editor.
     */
    private fun paintPlaceholder(g2: Graphics2D, x: Int, y: Int) {
        val icon = if (state == State.MISSING) AllIcons.General.Warning else AllIcons.General.InlineRefresh
        icon.paintIcon(null, g2, x, y)
        g2.color = JBColor.GRAY
        g2.font = font
        val text = TasklaneBundle.message(
            if (state == State.MISSING) "editor.image.missing" else "editor.image.loading",
        )
        g2.drawString(text, x + icon.iconWidth + JBUIScale.scale(GAP), y + g2.fontMetrics.ascent)
    }

    companion object {
        private const val MARGIN = 6
        private const val ARC = 4
        private const val GAP = 4
        private const val PLACEHOLDER_HEIGHT = 22

        /**
         * Una vista previa más alta que esto convertiría la tarjeta en una pared y
         * echaría de la pantalla las tareas de debajo. El tope de **alto** es tan
         * necesario como el de ancho: una captura de página entera escalada sólo por
         * el ancho daría miles de píxeles.
         */
        private const val MAX_HEIGHT = 280

        /** Lo que le queda a la imagen dentro de [available] píxeles de fila. */
        fun contentWidth(available: Int): Int = available - JBUIScale.scale(MARGIN) * 2

        fun contentHeight(): Int = JBUIScale.scale(MAX_HEIGHT)
    }
}
