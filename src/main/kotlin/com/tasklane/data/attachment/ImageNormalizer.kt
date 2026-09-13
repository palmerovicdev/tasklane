package com.tasklane.data.attachment

import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Lo que entra por el portapapeles → los bytes que se guardan.
 *
 * Dos cosas, y las dos existen por el mismo motivo: que el ID sea el contenido.
 *
 * - **Re-codificar a PNG.** Una captura de macOS llega como TIFF, un pantallazo de
 *   Windows como DIB y un arrastre desde el navegador como JPEG. Guardar el original
 *   dejaría el mismo dibujo bajo tres SHA distintos y rompería la deduplicación que
 *   es la razón de direccionar por contenido. PNG y no JPEG porque lo que se pega en
 *   una tarea son capturas de pantalla: texto, bordes y líneas finas, justo donde el
 *   JPEG se ve mal.
 * - **Reescalar antes de guardar**, no al pintar. Un monitor 5K produce capturas de
 *   varios megapíxeles; el `.idea` del usuario no es sitio para eso, y la resolución
 *   que no se va a mirar no vale lo que ocupa.
 *
 * Sin `com.intellij.util.ui.ImageUtil` a propósito: aquí no hay HiDPI que respetar
 * —esto escribe un fichero, no pinta— y con `Graphics2D` pelado la clase se testea
 * sin arrancar un IDE.
 */
object ImageNormalizer {

    const val EXTENSION = "png"

    /**
     * Suelo de cordura, no la política. Cuánto se reescala lo decide el usuario y lo
     * acota [com.tasklane.domain.model.TasklaneConfig.normalized]: tener el rango en
     * un solo sitio es lo que evita que el fichero de configuración y esta clase
     * discrepen sobre qué valor es válido.
     */
    private const val FLOOR = 16

    /**
     * @param maxSize lado mayor admitido; por encima se reescala conservando la
     *   proporción.
     * @return los bytes PNG listos para guardar.
     */
    fun normalize(image: Image, maxSize: Int): ByteArray {
        val source = toBuffered(image)
        val scaled = scale(source, maxSize.coerceAtLeast(FLOOR))
        val out = ByteArrayOutputStream(INITIAL_BUFFER)
        // ImageIO escribe PNG siempre: es uno de los formatos obligatorios del JDK,
        // así que no hace falta comprobar si hay un writer.
        ImageIO.write(scaled, EXTENSION, out)
        return out.toByteArray()
    }

    /** SHA-256 en hexadecimal: el nombre del fichero y el ID del adjunto. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * El portapapeles entrega un [Image] cualquiera —a menudo un `ToolkitImage` sin
     * rasterizar—, y `ImageIO` necesita un [BufferedImage]. ARGB y no RGB: una
     * captura de una ventana con esquinas redondeadas trae transparencia, y pasarla
     * a RGB la pintaría de negro.
     */
    private fun toBuffered(image: Image): BufferedImage {
        if (image is BufferedImage && image.type == BufferedImage.TYPE_INT_ARGB) return image
        val width = image.getWidth(null).coerceAtLeast(1)
        val height = image.getHeight(null).coerceAtLeast(1)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
            target.createGraphics().run {
                drawImage(image, 0, 0, null)
                dispose()
            }
        }
    }

    private fun scale(source: BufferedImage, maxSize: Int): BufferedImage {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSize) return source

        val factor = maxSize.toDouble() / longest
        // coerceAtLeast(1): una imagen muy alargada podría redondear su lado corto a
        // cero y BufferedImage no admite dimensión cero.
        val width = (source.width * factor).toInt().coerceAtLeast(1)
        val height = (source.height * factor).toInt().coerceAtLeast(1)

        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
            target.createGraphics().run {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                drawImage(source, 0, 0, width, height, null)
                dispose()
            }
        }
    }

    private const val INITIAL_BUFFER = 64 * 1024
}
