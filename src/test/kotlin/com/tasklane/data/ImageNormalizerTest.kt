package com.tasklane.data

import com.tasklane.data.attachment.ImageNormalizer
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
 * píxeles, mismos bytes, mismo SHA** —y, desde la 2.3, que nada se reescala—. Si eso deja de cumplirse, la deduplicación se
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
        val decoded = decode(ImageNormalizer.encode(image(40, 20)))
        assertNotNull(decoded)
        assertEquals(40, decoded.width)
        assertEquals(20, decoded.height)
    }

    /**
     * **Desde la 2.3 nada se reescala.** Una captura enorme sale con el tamaño con el que
     * entró: lo que ocupa se gestiona desde los ajustes, no degradándola al guardar.
     */
    @Test
    fun `una captura enorme se guarda a su tamano`() {
        val decoded = decode(ImageNormalizer.encode(image(4000, 2000)))
        assertEquals(4000, decoded.width)
        assertEquals(2000, decoded.height)
    }

    @Test
    fun `los mismos pixeles producen los mismos bytes`() {
        val first = ImageNormalizer.encode(image(64, 64))
        val second = ImageNormalizer.encode(image(64, 64))
        assertEquals(ImageNormalizer.sha256(first), ImageNormalizer.sha256(second))
    }

    @Test
    fun `dos imagenes distintas no comparten sha`() {
        val red = ImageNormalizer.encode(image(64, 64, Color.RED))
        val blue = ImageNormalizer.encode(image(64, 64, Color.BLUE))
        assertTrue(ImageNormalizer.sha256(red) != ImageNormalizer.sha256(blue))
    }

    /** Un `Image` que no es `INT_ARGB` —lo normal en el portapapeles— se rasteriza sin perder tamaño. */
    @Test
    fun `los pixeles de cualquier imagen conservan su tamano`() {
        val rgb = BufferedImage(300, 120, BufferedImage.TYPE_INT_RGB)
        val pixels = ImageNormalizer.pixels(rgb)
        assertEquals(BufferedImage.TYPE_INT_ARGB, pixels.type)
        assertEquals(300, pixels.width)
        assertEquals(120, pixels.height)
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
     * Una miniatura cuesta **un fichero más por blob**, así que sólo se escribe cuando de
     * verdad ahorra: por encima del doble de su tamaño. Sin tope de escalado, eso es casi
     * cualquier captura.
     */
    @Test
    fun `solo hay miniatura cuando se paga a si misma`() {
        assertNull(ImageNormalizer.thumbnail(image(200, 100)))
        assertNull(ImageNormalizer.thumbnail(image(400, 300)))
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
        val bytes = ImageNormalizer.encode(image(640, 360))
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

    /**
     * Un fichero soltado se guarda con su formato desde la 2.3, así que su tamaño hay que
     * poder leerlo aunque no sea un PNG — sin descodificarlo entero.
     */
    @Test
    fun `el tamano de un jpeg tambien se lee sin descodificar`() {
        val jpeg = java.io.ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(321, 123, BufferedImage.TYPE_INT_RGB), "jpg", it)
        }.toByteArray()
        assertNull("no es un PNG", ImageNormalizer.dimensions(jpeg))
        assertEquals(ImageNormalizer.Size(321, 123), ImageNormalizer.sizeOf(jpeg))
    }

    @Test
    fun `lo que no es una imagen no tiene tamano por ningun camino`() {
        assertNull(ImageNormalizer.sizeOf("hola, no soy una imagen".toByteArray()))
    }

    @Test
    fun `se conserva el canal alfa`() {
        val source = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val decoded = decode(ImageNormalizer.encode(source))
        assertEquals(0, decoded.getRGB(5, 5) ushr 24)
    }
}
