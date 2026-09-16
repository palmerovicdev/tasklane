package com.tasklane.ui

import com.tasklane.domain.model.AttachmentId
import com.tasklane.ui.toolwindow.CardImageView
import com.tasklane.ui.toolwindow.RowStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Qué se cae de una fila que viene más corta de lo que pide su contenido, y qué no.
 *
 * El alto de una fila lo mide el árbol una vez y lo guarda; el contenido se monta
 * contra el ancho de cada momento. Basta con que aparezca o desaparezca la barra de
 * desplazamiento entre una cosa y la otra para que las dos medidas dejen de coincidir,
 * y entonces [RowStack] decide qué se queda sin sitio. Aquí se fija esa decisión:
 *
 * - **un renglón de texto se cae** —es el trato de siempre, y el mismo texto se lee
 *   entero desplegando la tarjeta—,
 * - **una vista previa encoge** (2.6.0). Caerse era todo lo que la tarjeta desplegada
 *   tenía que enseñar, y por un píxel: el fallo de «a veces se ve la imagen y a veces
 *   no» era exactamente esto.
 *
 * Se mide sobre componentes de tamaño fijo y no sobre la fila de verdad para que el
 * resultado no dependa de la fuente de la máquina que corre el test.
 */
class RowStackTest {

    /** Una línea de texto: pide lo que pide y no sabe conformarse con menos. */
    private fun text(height: Int) = object : JComponent() {
        override fun getPreferredSize() = Dimension(WIDTH, height)
        override fun getMinimumSize() = preferredSize
    }

    private fun preview(side: Int) = CardImageView().apply {
        state = CardImageView.State.READY
        image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        id = AttachmentId(SHA)
    }

    private fun row(vararg lines: JComponent, short: Int) = JPanel(RowStack()).apply {
        lines.forEach(::add)
        val natural = preferredSize
        setSize(natural.width, natural.height - short)
        doLayout()
    }

    @Test
    fun `la vista previa encoge en vez de caerse`() {
        val title = text(20)
        val image = preview(120)
        val meta = text(16)
        row(title, image, meta, short = 10)

        assertTrue("el titulo se queda donde estaba", title.height == 20 && title.y == 0)
        assertTrue("la imagen tiene que seguir viendose", image.width > 0 && image.height > 0)
        assertEquals("y con diez pixeles menos", image.preferredSize.height - 10, image.height)
        assertEquals("los distintivos, justo debajo y pegados al fondo", image.y + image.height, meta.y)
    }

    /** Lo que ya hacía: un renglón de texto que no cabe se va, y la última se queda. */
    @Test
    fun `un renglon de texto que no cabe si se cae`() {
        val title = text(20)
        val second = text(20)
        val meta = text(16)
        row(title, second, meta, short = 10)

        assertEquals("la segunda linea es la que sobra", 0, second.width)
        assertTrue("la ultima no se cae nunca", meta.height == 16 && meta.width > 0)
    }

    /**
     * Encoger tiene suelo: por debajo de él no es una captura, es una franja, y
     * entonces sí vale más el sitio para lo que se lee.
     */
    @Test
    fun `una vista previa sin sitio de verdad tambien se cae`() {
        val title = text(20)
        val image = preview(120)
        val meta = text(16)
        row(title, image, meta, short = image.preferredSize.height)

        assertEquals(0, image.width)
    }

    /** Cuando la fila mide lo que tiene que medir, nada de esto cambia un píxel. */
    @Test
    fun `una fila con su sitio se apila de arriba abajo`() {
        val title = text(20)
        val image = preview(120)
        val meta = text(16)
        row(title, image, meta, short = 0)

        assertEquals(0, title.y)
        assertEquals(20, image.y)
        assertEquals(image.preferredSize.height, image.height)
        assertEquals(20 + image.height, meta.y)
    }

    private companion object {
        const val WIDTH = 200

        /** Un SHA-256 de mentira, con la longitud exacta que exige `ImageRefParser`. */
        const val SHA = "abc123def456abc123def456abc123def456abc123def456abc123def4561234"

        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
