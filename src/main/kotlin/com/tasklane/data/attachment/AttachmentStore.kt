package com.tasklane.data.attachment

import com.intellij.openapi.diagnostic.thisLogger
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import javax.imageio.ImageIO

/**
 * Los blobs de las imágenes, un directorio por repositorio bajo
 * `.idea/tasklane/repos/<repoKey>/attachments/`.
 *
 * **Direccionado por contenido**: el nombre del fichero es el SHA-256 de sus bytes.
 * Eso resuelve tres cosas de una vez —la misma captura pegada en dos tareas ocupa un
 * blob, no hay nombres que colisionen, y saber qué sobra es contar referencias— y es
 * la razón de que [put] sea idempotente: si el fichero ya está, no se reescribe.
 *
 * IO bloqueante, como [com.tasklane.data.store.TaskFileStore]: invocar desde
 * `Dispatchers.IO`. Tampoco pasa por la VFS, y por el mismo motivo — estos ficheros
 * son datos del plugin, no fuentes del proyecto, y no deben entrar en el índice.
 */
class AttachmentStore(private val layout: StorageLayout) {

    /** Un blob en disco. [modified] es lo que el recolector usa como periodo de gracia. */
    data class Blob(val id: AttachmentId, val path: Path, val modified: Instant, val size: Long)

    fun path(repo: RepoKey, id: AttachmentId): Path =
        layout.attachmentsDir(repo).resolve("${id.value}.${ImageNormalizer.EXTENSION}")

    fun exists(repo: RepoKey, id: AttachmentId): Boolean = Files.exists(path(repo, id))

    /**
     * Guarda los bytes y devuelve su ID.
     *
     * Se escribe a un temporal y se mueve, igual que el fichero de tareas: un fallo a
     * media escritura no puede dejar un blob truncado bajo un nombre que promete ser
     * su propio hash.
     */
    fun put(repo: RepoKey, bytes: ByteArray): AttachmentId {
        val id = AttachmentId(ImageNormalizer.sha256(bytes))
        val target = path(repo, id)
        // El contenido determina el nombre: si el fichero está, ya es este contenido.
        if (Files.exists(target)) return id

        Files.createDirectories(target.parent)
        layout.ensureIgnored()

        val tmp = target.resolveSibling("${id.value}.tmp")
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            thisLogger().warn("Tasklane: ATOMIC_MOVE no soportado en $target, se usa move normal", e)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return id
    }

    /**
     * @return la imagen decodificada, o `null` si el blob no está o no se puede leer.
     *   Ausente no es excepcional: el cuerpo puede referenciar una imagen que ya
     *   recogió el GC o que se borró a mano, y quien pinta lo resuelve con un
     *   marcador de posición.
     */
    fun load(repo: RepoKey, id: AttachmentId): BufferedImage? {
        val file = path(repo, id)
        if (!Files.exists(file)) return null
        return try {
            Files.newInputStream(file).use(ImageIO::read)
        } catch (e: Exception) {
            thisLogger().warn("Tasklane: no se pudo leer el adjunto $file", e)
            null
        }
    }

    /** Todo lo que hay en el directorio de adjuntos. Sólo lo llama el recolector. */
    fun list(repo: RepoKey): List<Blob> {
        val dir = layout.attachmentsDir(repo)
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { paths ->
                paths.toList().mapNotNull { path ->
                    val name = path.fileName.toString()
                    // Un `.tmp` de una escritura interrumpida no es un blob: se deja
                    // para el recolector, que sí sabe cuándo es viejo y se puede tirar.
                    val id = name.removeSuffix(".${ImageNormalizer.EXTENSION}")
                        .takeIf { it != name && it.length == SHA256_HEX }
                        ?: return@mapNotNull null
                    val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                    Blob(AttachmentId(id), path, attrs.lastModifiedTime().toInstant(), attrs.size())
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Tasklane: no se pudo listar $dir", e)
            emptyList()
        }
    }

    /** Los temporales que quedaron de una escritura interrumpida. */
    fun orphanTemporaries(repo: RepoKey): List<Blob> {
        val dir = layout.attachmentsDir(repo)
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { paths ->
                paths.toList()
                    .filter { it.fileName.toString().endsWith(".tmp") }
                    .map { path ->
                        val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                        Blob(AttachmentId(""), path, attrs.lastModifiedTime().toInstant(), attrs.size())
                    }
            }
        } catch (e: Exception) {
            thisLogger().warn("Tasklane: no se pudo listar $dir", e)
            emptyList()
        }
    }

    fun delete(path: Path): Boolean = runCatching { Files.deleteIfExists(path) }
        .onFailure { thisLogger().warn("Tasklane: no se pudo borrar el adjunto $path", it) }
        .getOrDefault(false)

    private companion object {
        const val SHA256_HEX = 64
    }
}
