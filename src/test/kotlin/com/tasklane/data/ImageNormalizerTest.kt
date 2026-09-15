package com.tasklane.data

import com.tasklane.data.attachment.ImageNormalizer
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    // ----------------------------------------------- miniaturas y cabeceras (Fase 4)

    @Test
    fun `la miniatura encoge al tope y conserva la proporcion`() {
        val thumb = ImageNormalizer.thumbnail(image(1600, 800))
        assertNotNull(thumb)
        val decoded = decode(thumb!!)
        assertEquals(ImageNormalizer.THUMB_SIZE, decoded.width)
        assertEquals(ImageNormalizer.THUMB_SIZE / 2, decoded.height)
    }

    /**
     * Una miniatura cuesta **un fichero más por blob** y un 45 % de disco sobre una
     * captura de 400 px, así que sólo se escribe cuando de verdad ahorra. Con el tope
     * nuevo eso quiere decir: nunca para lo que se pegue ahora, siempre para las
     * capturas de 1600 px que ya estuvieran guardadas.
     */
    @Test
    fun `solo hay miniatura cuando se paga a si misma`() {
        assertNull(ImageNormalizer.thumbnail(image(200, 100)))
        assertNull(ImageNormalizer.thumbnail(image(TasklaneConfig.DEFAULT_IMAGE_MAX_SIZE, 300)))
        assertNull(ImageNormalizer.thumbnail(image(ImageNormalizer.THUMB_THRESHOLD, 10)))
        assertNotNull(ImageNormalizer.thumbnail(image(ImageNormalizer.THUMB_THRESHOLD + 1, 10)))
        assertNotNull(ImageNormalizer.thumbnail(image(1600, 900)))
    }

    /**
     * Las dimensiones salen de la cabecera, no de descodificar: es lo que hace que la
     * reconciliación pueda adoptar millones de ficheros leyendo veinticuatro bytes de
     * cada uno.
     */
    @Test
    fun `el tamano se lee de la cabecera sin descodificar`() {
        val bytes = ImageNormalizer.normalize(image(640, 360), 1600)
        assertEquals(ImageNormalizer.Size(640, 360), ImageNormalizer.dimensions(bytes))
        // Y con lo justo: la cabecera de un PNG cabe en los primeros bytes del fichero.
        assertEquals(
            ImageNormalizer.Size(640, 360),
            ImageNormalizer.dimensions(bytes.copyOf(ImageNormalizer.HEADER_BYTES)),
        )
    }

    @Test
    fun `lo que no es un png no tiene tamano`() {
        assertNull(ImageNormalizer.dimensions("no soy un png en absoluto".toByteArray()))
        assertNull(ImageNormalizer.dimensions(ByteArray(4)))
    }

    @Test
    fun `se conserva el canal alfa`() {
        val source = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val decoded = decode(ImageNormalizer.normalize(source, 1600))
        assertEquals(0, decoded.getRGB(5, 5) ushr 24)
    }
}
