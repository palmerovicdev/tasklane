package com.tasklane.data.attachment

import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Lo que entra al adjuntar una imagen → los bytes que se guardan.
 *
 * **Desde la 2.3 no se reescala nada.** Hasta la 2.2 toda imagen pasaba por un tope de
 * lado mayor —1600 px, y 400 desde la Fase 4— antes de escribirse. La decisión se
 * revisó: una captura de código a 400 px no se lee, y lo que ocupan las imágenes se
 * gestiona mirando su peso y limpiándolas desde los ajustes, no degradándolas al entrar.
 *
 * Dos caminos, según lo que llegue:
 *
 * - **Un fichero** —soltado, elegido o copiado desde el explorador— **se guarda tal
 *   cual**, con sus bytes: ni se descodifica ni se vuelve a codificar. Es el camino
 *   barato —medido, descodificar una captura de 2880 px eran 54 ms antes de empezar a
 *   reescalarla— y el que conserva exactamente lo que el usuario tenía.
 * - **Píxeles** —lo que deja una captura de pantalla en el portapapeles, un `Image` sin
 *   fichero detrás— hay que codificarlos a algo, y se codifican a **PNG a tamaño
 *   original**. PNG porque no pierde: lo que se pega son capturas, con texto y líneas
 *   finas, justo donde el JPEG se ve mal. Con la compresión de siempre del codificador,
 *   que es sin pérdida: un PNG *sin* deflate pesaría 32 MB por captura de 2880 px y
 *   tardaría más en escribirse que en comprimirse.
 *
 * **El nombre sigue siendo el SHA-256 de los bytes guardados**, así que la misma captura
 * pegada dos veces es un fichero, y el mismo fichero soltado dos veces también. Lo que
 * ya no coincide es la misma imagen **por los dos caminos** —soltar un PNG y pegar sus
 * píxeles dan bytes distintos—, y se acepta: re-codificar todo fichero para unificarlo
 * era justo el coste que esta versión quita.
 *
 * Sin `com.intellij.util.ui.ImageUtil` a propósito: aquí no hay HiDPI que respetar
 * —esto escribe un fichero, no pinta— y con `Graphics2D` pelado la clase se testea
 * sin arrancar un IDE.
 */
object ImageNormalizer {

    /**
     * La extensión con la que se nombran los blobs, **sea cual sea su formato**.
     *
     * Un fichero soltado conserva sus bytes, así que un `<sha>.png` puede ser un JPEG por
     * dentro. No es un descuido: todo lo que lee un blob —`ImageIO`, el HTML del tooltip—
     * reconoce el formato por el contenido y no por el nombre, y una extensión por formato
     * obligaría a probar varias rutas en cada lectura y a cambiar el árbol de la Fase 4.
     */
    const val EXTENSION = "png"

    /**
     * El lado mayor de una miniatura (§4.4).
     *
     * Existe para que **la lista no descodifique nunca el original**, y desde la 2.3
     * más que nunca: una captura de 2880 px descodificada a `INT_ARGB` son 33 MB, y una
     * tarjeta con diez serían trescientos. A 256 px son 260 KB.
     *
     * 256 y no más: una tarjeta de la lista mide lo que mide la tool window, y ahí una
     * vista previa más grande no se ve mejor, sólo ocupa más. Para mirar de cerca está
     * el popup, que sí lee el original.
     */
    const val THUMB_SIZE = 256

    /**
     * A partir de qué tamaño una miniatura **se paga a sí misma**.
     *
     * Una miniatura es un fichero más por blob. Por debajo del doble de su tamaño, el
     * original ya está a un factor ~4 de ella en memoria y no merece la pena; por encima,
     * la diferencia se dispara —una captura de 1600 px son 10,2 MB descodificada contra
     * 262 KB—. Sin tope de escalado, casi toda captura nueva queda por encima.
     */
    const val THUMB_THRESHOLD = THUMB_SIZE * 2

    /** ¿Merece la pena una miniatura para una imagen de este tamaño? Ver [THUMB_THRESHOLD]. */
    fun needsThumbnail(width: Int, height: Int): Boolean = maxOf(width, height) > THUMB_THRESHOLD

    /**
     * Los píxeles de cualquier [Image], listos para codificar o para sacarles miniatura.
     *
     * El portapapeles entrega un [Image] cualquiera —a menudo un `ToolkitImage` sin
     * rasterizar—, y `ImageIO` necesita un [BufferedImage]. ARGB y no RGB: una captura de
     * una ventana con esquinas redondeadas trae transparencia, y pasarla a RGB la
     * pintaría de negro.
     */
    fun pixels(image: Image): BufferedImage {
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

    /** Los píxeles en PNG, **a su tamaño**. Ver el KDoc de la clase. */
    fun encode(pixels: BufferedImage): ByteArray {
        // Del orden de lo que va a pesar: una captura con texto sale en ~0,15 bytes por
        // píxel, y empezar con el búfer cerca evita copiarlo media docena de veces.
        val out = ByteArrayOutputStream((pixels.width * pixels.height / 6).coerceIn(INITIAL_BUFFER, MAX_INITIAL_BUFFER))
        // ImageIO escribe PNG siempre: es uno de los formatos obligatorios del JDK,
        // así que no hace falta comprobar si hay un writer.
        ImageIO.write(pixels, EXTENSION, out)
        return out.toByteArray()
    }

    /**
     * La miniatura de una imagen, en bytes PNG.
     *
     * @return `null` cuando no merece la pena —ver [THUMB_THRESHOLD]—. Un fichero que no
     *   se escribe es un fichero que no hay que recolectar ni contar, y quien lea sabe
     *   caer al original.
     */
    fun thumbnail(source: BufferedImage, maxSize: Int = THUMB_SIZE): ByteArray? {
        if (!needsThumbnail(source.width, source.height)) return null
        val out = ByteArrayOutputStream(INITIAL_BUFFER)
        ImageIO.write(scale(source, maxSize), EXTENSION, out)
        return out.toByteArray()
    }

    /** El tamaño de una imagen, en píxeles. */
    data class Size(val width: Int, val height: Int)

    /**
     * Las dimensiones de un PNG **sin descodificarlo**: se leen de la cabecera.
     *
     * La cabecera `IHDR` de un PNG está siempre en el mismo sitio —ocho bytes de firma,
     * ocho de longitud y tipo, y ahí el ancho y el alto en big-endian—, así que esto son
     * veinticuatro bytes en vez de descodificar la captura entera. Importa porque quien lo
     * llama es el reconciliador del §4.3, que puede estar adoptando **millones** de
     * ficheros de una tacada.
     *
     * @return `null` si no es un PNG o si está truncado. Ausente no es excepcional: por
     *   ahí pasa cualquier cosa que alguien haya dejado caer en el directorio, y desde la
     *   2.3 también los ficheros que se guardan con su formato. Para esos está [sizeOf].
     */
    fun dimensions(header: ByteArray): Size? {
        if (header.size < IHDR_END) return null
        for (i in SIGNATURE.indices) if (header[i] != SIGNATURE[i]) return null
        return Size(readInt(header, IHDR_WIDTH), readInt(header, IHDR_WIDTH + 4))
    }

    /** Cuántos bytes hay que leer de un fichero para que [dimensions] pueda contestar. */
    const val HEADER_BYTES = 24

    /**
     * Las dimensiones de una imagen **en cualquier formato que `ImageIO` sepa leer**, sin
     * descodificar los píxeles: el PNG por su cabecera, y el resto preguntándole al lector
     * de su formato, que sólo lee la cabecera para contestar.
     *
     * @return `null` si ningún lector reconoce el contenido.
     */
    fun sizeOf(bytes: ByteArray): Size? = dimensions(bytes) ?: sizeOf(ByteArrayInputStream(bytes))

    /** Lo mismo desde un flujo. Quien lo abre lo cierra. */
    fun sizeOf(input: java.io.InputStream): Size? = runCatching {
        ImageIO.createImageInputStream(input)?.use { stream ->
            val reader = ImageIO.getImageReaders(stream).takeIf { it.hasNext() }?.next() ?: return null
            try {
                reader.setInput(stream, true, true)
                Size(reader.getWidth(0), reader.getHeight(0))
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    /** SHA-256 en hexadecimal: el nombre del fichero y el ID del adjunto. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
    private const val MAX_INITIAL_BUFFER = 8 * 1024 * 1024

    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val IHDR_WIDTH = 16
    private const val IHDR_END = 24

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
