package com.tasklane.data

import com.tasklane.data.attachment.ImageNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * El normalizador de imágenes.
 *
 * Lo que se fija aquí es la propiedad de la que depende todo el almacén: **mismos
 * píxeles, mismos bytes, mismo SHA**. Si eso deja de cumplirse, la deduplicación se
 * rompe en silencio y cada pegado de la misma captura escribe un fichero nuevo.
 */
class ImageNormalizerTest {

    private fun image(width: Int, height: Int, color: Color = Color.RED): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
            it.createGraphics().run {
                setColor(color)
                fillRect(0, 0, width, height)
                dispose()
            }
        }

    private fun decode(bytes: ByteArray): BufferedImage =
        ByteArrayInputStream(bytes).use(ImageIO::read)

    @Test
    fun `lo que sale es un PNG legible`() {
        val decoded = decode(ImageNormalizer.normalize(image(40, 20), 1600))
        assertNotNull(decoded)
        assertEquals(40, decoded.width)
        assertEquals(20, decoded.height)
    }

    @Test
    fun `una imagen que ya cabe no se toca`() {
        val decoded = decode(ImageNormalizer.normalize(image(100, 50), 200))
        assertEquals(100, decoded.width)
        assertEquals(50, decoded.height)
    }

    @Test
    fun `una captura enorme se reescala conservando la proporcion`() {
        val decoded = decode(ImageNormalizer.normalize(image(4000, 2000), 800))
        assertEquals(800, decoded.width)
        assertEquals(400, decoded.height)
    }

    @Test
    fun `el lado que manda es el mayor, sea cual sea`() {
        val decoded = decode(ImageNormalizer.normalize(image(500, 2000), 1000))
        assertEquals(1000, decoded.height)
        assertEquals(250, decoded.width)
    }

    @Test
    fun `una imagen muy alargada nunca queda con un lado a cero`() {
        val decoded = decode(ImageNormalizer.normalize(image(4000, 3), 100))
        assertTrue("el lado corto se quedo en cero", decoded.width > 0 && decoded.height > 0)
    }

    @Test
    fun `los mismos pixeles producen los mismos bytes`() {
        val first = ImageNormalizer.normalize(image(64, 64), 1600)
        val second = ImageNormalizer.normalize(image(64, 64), 1600)
        assertEquals(ImageNormalizer.sha256(first), ImageNormalizer.sha256(second))
    }

    @Test
    fun `dos imagenes distintas no comparten sha`() {
        val red = ImageNormalizer.normalize(image(64, 64, Color.RED), 1600)
        val blue = ImageNormalizer.normalize(image(64, 64, Color.BLUE), 1600)
        assertTrue(ImageNormalizer.sha256(red) != ImageNormalizer.sha256(blue))
    }

    @Test
    fun `el sha tiene la pinta de un sha`() {
        val hash = ImageNormalizer.sha256(byteArrayOf(1, 2, 3))
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /** La transparencia de una ventana con esquinas redondeadas no puede volverse negra. */
    @Test
    fun `se conserva el canal alfa`() {
        val source = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val decoded = decode(ImageNormalizer.normalize(source, 1600))
        assertEquals(0, decoded.getRGB(5, 5) ushr 24)
    }
}
