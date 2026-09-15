package com.tasklane.bench

import com.tasklane.data.attachment.BlobLayout
import com.tasklane.domain.model.AttachmentId
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Los blobs sintéticos de la Fase 0 (`docs/plan-escala.md` §0.2): imágenes que **pesan
 * lo que pesa una captura de verdad**, que es la única forma de que la aritmética del
 * §1.6 —4 TB a 1600 px, 400 GB con deduplicación 10:1— se pueda comprobar en vez de
 * creer.
 *
 * **Por qué no vale una imagen cualquiera.** Un PNG de ruido aleatorio es
 * incompresible y un 1600×1600 se va a ~7 MB; uno de color plano se queda en 5 KB. Los
 * dos medirían un disco que nadie tiene. Una captura de pantalla está justo en medio y
 * por una razón concreta: grandes zonas planas —el fondo, los paneles— salpicadas de
 * detalle fino que no comprime, que es el texto. [png] dibuja exactamente eso y, ya
 * calibrado, da las cifras con las que hay que hacer la aritmética del disco:
 *
 *     1600 px (el tope de hoy)          ~410 KB   →  10M blobs = 3,9 TB   ·  dedup 10:1 = 390 GB
 *      400 px (el tope de la Fase 4)     ~33 KB   →  10M blobs = 315 GB   ·  dedup 10:1 =  31 GB
 *
 * Es decir: el tope de 400 px que se decidió para la Fase 4 divide el problema del
 * §1.6 por doce y medio. Sigue sin caber en un `.idea/` sin una política —de ahí la
 * cuota con aviso—, pero deja de ser una cifra de otro orden de magnitud.
 *
 * **El nombre no es el hash del contenido, y es a propósito.** En producción manda
 * `AttachmentStore.put`, que nombra cada fichero con el SHA-256 de sus bytes; aquí el
 * cuerpo de la tarea tiene que referenciar la imagen **antes** de que exista, así que
 * [id] es una función pura del índice —un SHA-256 de verdad, pero de una semilla, no
 * del PNG—. Lo que el banco mide con esto es volumen y disposición en disco: cuántos
 * ficheros hay, cuánto pesan, cuánto tarda `Files.list` sobre ellos y cuánto el GC. El
 * direccionamiento por contenido tiene sus propios tests y no se toca aquí.
 */
object SyntheticBlobs {

    /**
     * Lo que ocupa una captura útil una vez el usuario decidió el nuevo tope: la
     * política de la Fase 4 escala todo a 400 px de lado mayor. El banco mide también
     * el tamaño viejo, que es lo que hay en el disco de quien ya usaba el plugin.
     */
    const val DEFAULT_SIZE = 400
    const val LEGACY_SIZE = 1600

    /**
     * Amplitud del grano por píxel. Es la perilla que lleva el fichero al peso de una
     * captura de verdad; ver el comentario en [png]. Fijada midiendo: con este valor
     * un 1600 px sale en los cientos de KB que razona el §1.6.
     */
    private const val GRAIN = 4

    /**
     * Uno de cada cuántos píxeles lleva grano. La densidad importa tanto como la
     * amplitud, y por la misma razón por la que este generador existe: en una captura
     * real el ruido no está repartido, está **en los bordes** —el suavizado del texto,
     * las sombras—, y el resto es plano. Con grano en todos los píxeles los filtros
     * del PNG se rinden y un 1600 px se va a 2,6 MB aunque la amplitud sea de un solo
     * nivel. Los dos valores están calibrados midiendo, no estimados.
     */
    private const val GRAIN_DENSITY = 28

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * El ID del blob número [n]. Estable, y por eso el corpus puede referenciarlo
     * antes de que el fichero exista. Ver el KDoc de la clase.
     */
    fun id(n: Int): AttachmentId {
        val digest = MessageDigest.getInstance("SHA-256").digest("tasklane-synthetic-blob:$n".toByteArray())
        val hex = CharArray(64)
        for (i in digest.indices) {
            hex[i * 2] = HEX[(digest[i].toInt() shr 4) and 0xF]
            hex[i * 2 + 1] = HEX[digest[i].toInt() and 0xF]
        }
        return AttachmentId(String(hex))
    }

    /**
     * Un PNG con la pinta —y el peso— de una captura de pantalla: fondo claro, unos
     * paneles y muchas líneas de «texto». Determinista a partir de [n].
     */
    fun png(n: Int, size: Int = DEFAULT_SIZE, grain: Int = GRAIN, density: Int = GRAIN_DENSITY): ByteArray {
        val random = Random(n.toLong() * 2654435761L)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()

        // El fondo y los paneles: las zonas planas, que son las que comprimen.
        g.color = Color(0xF7, 0xF8, 0xFA)
        g.fillRect(0, 0, size, size)
        repeat(3) {
            g.color = Color(random.nextInt(0xD0, 0xFF), random.nextInt(0xD0, 0xFF), random.nextInt(0xD0, 0xFF))
            val w = random.nextInt(size / 4, size)
            val h = random.nextInt(size / 8, size / 2)
            g.fillRect(random.nextInt(size), random.nextInt(size), w, h)
        }

        // Y el detalle fino: renglones de rectangulitos oscuros que imitan texto. Es
        // lo que NO comprime, y lo que lleva el fichero al peso de una captura real.
        val lineHeight = (size / 45).coerceAtLeast(2)
        var y = lineHeight
        while (y < size - lineHeight) {
            var x = random.nextInt(size / 16 + 1)
            val grey = random.nextInt(0x20, 0x70)
            g.color = Color(grey, grey, grey)
            while (x < size - lineHeight) {
                val word = random.nextInt(lineHeight, lineHeight * 6)
                if (x + word >= size) break
                g.fillRect(x, y, word, (lineHeight * 2 / 3).coerceAtLeast(1))
                x += word + lineHeight
            }
            y += lineHeight * 2
        }
        g.dispose()

        // Y el grano. Sin esto el PNG se queda en ~40 KB a 1600 px: rectángulos de
        // color plano son exactamente lo que los filtros de PNG comprimen mejor, y el
        // fichero mediría un disco que nadie tiene. Una captura real trae texto
        // suavizado, sombras y degradados —miles de tonos casi iguales— y eso es lo
        // que le da su peso. Una perturbación pequeña por píxel reproduce ese efecto
        // sin que la imagen deje de parecerse a una captura.
        val pixels = image.raster
        val rgb = IntArray(4)
        for (y2 in 0 until size) {
            for (x2 in 0 until size) {
                if (random.nextInt(density) != 0) continue
                pixels.getPixel(x2, y2, rgb)
                for (c in 0 until 3) rgb[c] = (rgb[c] + random.nextInt(-grain, grain + 1)).coerceIn(0, 255)
                pixels.setPixel(x2, y2, rgb)
            }
        }

        val out = ByteArrayOutputStream(256 * 1024)
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /**
     * Escribe [count] blobs en [dir] con los nombres que el corpus referencia.
     *
     * @param dir el directorio de adjuntos del repositorio.
     * @param sharded `true` para el árbol `ab/cd` de la Fase 4; `false` para el
     *   directorio plano de hasta la 2.0, que es lo que hay que poder medir para saber
     *   de qué se viene.
     * @return los bytes escritos en total.
     */
    fun writeAll(
        dir: Path,
        count: Int,
        size: Int = DEFAULT_SIZE,
        sharded: Boolean = false,
        onProgress: (Int) -> Unit = {},
    ): Long {
        Files.createDirectories(dir)
        var bytes = 0L
        for (n in 0 until count) {
            val id = id(n)
            val file = if (sharded) {
                val (first, second) = BlobLayout.shardsOf(id)!!
                dir.resolve(first).resolve(second).also(Files::createDirectories).resolve("${id.value}.png")
            } else {
                dir.resolve("${id.value}.png")
            }
            if (!Files.exists(file)) {
                val data = png(n, size)
                Files.write(file, data)
                bytes += data.size
            } else {
                bytes += Files.size(file)
            }
            if ((n + 1) % 1000 == 0) onProgress(n + 1)
        }
        return bytes
    }
}
