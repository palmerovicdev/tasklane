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
     * El lado mayor de una miniatura (§4.4).
     *
     * Existe para que **la lista no descodifique nunca el original**. Una tarjeta
     * desplegada con diez imágenes son diez `BufferedImage` en memoria a la vez; a
     * 1600 px son 10,2 MB cada una, a 400 px son 640 KB y a 256 px son 260 KB. Y no es
     * una cifra teórica: el disco de quien ya usaba el plugin está lleno de capturas de
     * 1600 px que esta fase decide **no tocar** —reescalarlas cambiaría su SHA, que es
     * su nombre, y con él todas las referencias de los cuerpos—, así que la miniatura es
     * lo que hace que esas capturas dejen de pesar aunque sigan ahí.
     *
     * 256 y no 400: una tarjeta de la lista mide lo que mide la tool window, y ahí una
     * vista previa más grande no se ve mejor, sólo ocupa más. Para mirar de cerca está
     * el popup, que sí lee el original.
     */
    const val THUMB_SIZE = 256

    /**
     * A partir de qué tamaño una miniatura **se paga a sí misma**.
     *
     * Una miniatura no es gratis: es un fichero más por blob —o sea, el doble de
     * ficheros en el árbol— y un 45 % más de disco sobre una captura de 400 px. Con el
     * tope nuevo, el original ya está a un factor 2,4 de su miniatura en memoria
     * (640 KB contra 262 KB), y a cambio de ese factor se duplicaría justo lo que esta
     * fase viene a acotar.
     *
     * Con el doble del tope de la miniatura, la cuenta cambia de signo: una captura de
     * 1600 px —las que puede haber ya guardadas— cuesta 10,2 MB descodificada contra
     * 262 KB, cuarenta veces más. **Ahí sí**, y por eso la miniatura existe.
     */
    const val THUMB_THRESHOLD = THUMB_SIZE * 2

    /** ¿Merece la pena una miniatura para una imagen de este tamaño? Ver [THUMB_THRESHOLD]. */
    fun needsThumbnail(width: Int, height: Int): Boolean = maxOf(width, height) > THUMB_THRESHOLD

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

    /**
     * La miniatura de una imagen ya guardada, en bytes PNG.
     *
     * @return `null` cuando no merece la pena —ver [THUMB_THRESHOLD]—, que con el tope
     *   nuevo de 400 px es el caso de **todo lo que se pegue a partir de ahora**. Un
     *   fichero que no se escribe es un fichero que no hay que recolectar, reconciliar
     *   ni contar, y quien lea sabe caer al original.
     */
    fun thumbnail(source: BufferedImage, maxSize: Int = THUMB_SIZE): ByteArray? {
        if (!needsThumbnail(source.width, source.height)) return null
        val out = ByteArrayOutputStream(INITIAL_BUFFER)
        ImageIO.write(scale(source, maxSize), EXTENSION, out)
        return out.toByteArray()
    }

    /** El tamaño de un PNG, en píxeles. */
    data class Size(val width: Int, val height: Int)

    /**
     * Las dimensiones de un PNG **sin descodificarlo**: se leen de la cabecera.
     *
     * La cabecera `IHDR` de un PNG está siempre en el mismo sitio —ocho bytes de firma,
     * ocho de longitud y tipo, y ahí el ancho y el alto en big-endian—, así que esto son
     * veinticuatro bytes en vez de los diez megas que ocupa descodificar una captura de
     * 1600 px. Importa porque quien lo llama es el reconciliador del §4.3, que puede
     * estar adoptando **millones** de ficheros de una tacada.
     *
     * @return `null` si no es un PNG o si está truncado. Ausente no es excepcional: por
     *   ahí pasa cualquier cosa que alguien haya dejado caer en el directorio.
     */
    fun dimensions(header: ByteArray): Size? {
        if (header.size < IHDR_END) return null
        for (i in SIGNATURE.indices) if (header[i] != SIGNATURE[i]) return null
        return Size(readInt(header, IHDR_WIDTH), readInt(header, IHDR_WIDTH + 4))
    }

    /** Cuántos bytes hay que leer de un fichero para que [dimensions] pueda contestar. */
    const val HEADER_BYTES = 24

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

    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val IHDR_WIDTH = 16
    private const val IHDR_END = 24

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
