package com.tasklane.code

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.scale.JBUIScale
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * La marca **dentro del texto**, en la columna exacta que se ancló: `[✓ TODO]`.
 *
 * Es lo único que puede decir que la nota era sobre `load(key)` y no sobre la línea
 * entera. A cambio empuja el código a la derecha, que es justo por lo que hay que
 * elegirla en los ajustes en vez de venir puesta: ver [com.tasklane.domain.model.AnchorMarkerStyle].
 *
 * **Lleva la palabra, no sólo el icono.** Dentro de una línea de código un icono suelto
 * se lee como un carácter raro y hay que pasar el ratón para saber qué es; con el nombre
 * del estado al lado, la marca se entiende sin tocarla y se lee como lo que es, un
 * `TODO` — que es justo la convención que lleva décadas significando esto en un fichero
 * de código—. Y es **el nombre del estado**, no un `TODO` cableado: los estados son
 * configurables y son el vocabulario del plugin —lo que dicen sus pestañas—, así que una
 * tarea en *Doing* dice `DOING` en vez de mentir. En mayúsculas porque un marcador de
 * código va en mayúsculas; con la configuración de fábrica sale exactamente `TODO`.
 *
 * Un `EditorCustomElementRenderer` y no un componente Swing metido en el editor, por lo
 * mismo que la vista previa de las imágenes: el inlay se mueve con el texto, sobrevive
 * a que se reajuste la línea y no se mete con la selección ni con el cursor.
 *
 * El fondo redondeado no es adorno: separa la marca de lo que tiene a los lados y dice
 * que eso no es texto del fichero.
 */
internal class AnchorChip(
    private val icon: Icon,
    private val label: String,
    private val color: Color,
    private val background: Color,
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = metrics(inlay.editor)
        return JBUIScale.scale(PAD) * 2 + icon.iconWidth + JBUIScale.scale(GAP) + metrics.stringWidth(label)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, region: Rectangle, attributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val pad = JBUIScale.scale(PAD)
            val gap = JBUIScale.scale(GAP)
            val font = font(inlay.editor)
            val metrics = inlay.editor.contentComponent.getFontMetrics(font)

            // La pastilla se ajusta al mayor de los dos, icono o texto: con una fuente
            // de editor grande el texto pasa del icono, y al revés con una pequeña.
            val height = maxOf(icon.iconHeight, metrics.height) + pad
            val width = calcWidthInPixels(inlay)
            // Centrada en la línea: el inlay recibe el alto de la línea entera, que con
            // interlineado es más que lo que se pinta.
            val y = region.y + (region.height - height) / 2f

            g2.color = background
            val arc = JBUIScale.scale(ARC).toFloat()
            g2.fill(RoundRectangle2D.Float(region.x.toFloat(), y, width.toFloat(), height.toFloat(), arc, arc))

            icon.paintIcon(null, g2, region.x + pad, (y + (height - icon.iconHeight) / 2f).toInt())

            g2.font = font
            g2.color = color
            // Por la línea base y no por la caja: dos textos centrados por su caja se
            // ven descuadrados cuando uno tiene descendentes y el otro no.
            val baseline = y + (height + metrics.ascent - metrics.descent) / 2f
            g2.drawString(label, (region.x + pad + icon.iconWidth + gap).toFloat(), baseline)
        } finally {
            g2.dispose()
        }
    }

    private fun metrics(editor: Editor): FontMetrics = editor.contentComponent.getFontMetrics(font(editor))

    /**
     * La fuente del editor, más pequeña y en negrita.
     *
     * Del editor y no de la interfaz porque la marca vive **entre el código**: con la
     * fuente de los menús desentonaría en cuanto alguien cambiara una de las dos. Más
     * pequeña para que ocupe lo menos posible —está robándole sitio a la línea— y en
     * negrita para que a ese tamaño siga leyéndose.
     */
    private fun font(editor: Editor): Font {
        val editorFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return editorFont.deriveFont(Font.BOLD, maxOf(MIN_FONT, editorFont.size2D * FONT_SCALE))
    }

    companion object {
        private const val PAD = 3
        private const val GAP = 3
        private const val ARC = 5

        private const val FONT_SCALE = 0.82f
        private const val MIN_FONT = 9f

        /**
         * Hasta dónde se enseña el nombre del estado. Uno configurado como «Waiting for
         * review» son dieciocho caracteres metidos dentro de una línea de código: la
         * marca dejaría de ser una marca. El nombre entero sigue estando en el tooltip.
         */
        const val MAX_LABEL = 12

        /**
         * El texto de la pastilla: el nombre del estado en mayúsculas y recortado.
         *
         * Aquí y no en quien pinta porque es una regla, y las reglas se prueban. En
         * mayúsculas por la convención de los marcadores de código —`TODO`, `FIXME`—, que
         * es exactamente lo que esta marca es.
         */
        fun labelOf(state: String): String {
            val name = state.trim().uppercase()
            return if (name.length <= MAX_LABEL) name else name.take(MAX_LABEL - 1).trimEnd() + "…"
        }
    }
}
