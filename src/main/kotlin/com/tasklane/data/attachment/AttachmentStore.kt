package com.tasklane.data.attachment

import com.intellij.openapi.diagnostic.thisLogger
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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
 * **Fragmentado desde la Fase 4** (§4.1): `attachments/ab/cd/<sha>.png`, y el porqué
 * está en [BlobLayout]. Lo que quedara en plano de versiones anteriores **se sigue
 * leyendo** —[locate] prueba las dos rutas— y se traslada en segundo plano con
 * [relocate]. Ningún camino de lectura depende de que el traslado haya terminado.
 *
 * **Y con miniatura al lado** (§4.4): `<sha>.thumb.png`, a [ImageNormalizer.THUMB_SIZE]
 * píxeles, que es lo que pinta la lista para no descodificar nunca el original.
 *
 * IO bloqueante, como [com.tasklane.data.store.TaskFileStore]: invocar desde
 * `Dispatchers.IO`. Tampoco pasa por la VFS, y por el mismo motivo — estos ficheros
 * son datos del plugin, no fuentes del proyecto, y no deben entrar en el índice.
 */
class AttachmentStore(private val layout: StorageLayout) {

    /** Un blob **en disco**. [modified] es lo que el recolector usa como periodo de gracia. */
    data class Blob(val id: AttachmentId, val path: Path, val modified: Instant, val size: Long)

    /**
     * Una tanda del recorrido del §4.3: lo que hay, y lo que quedó a medio escribir.
     *
     * [flat] son los que estaban **fuera del árbol**, en la raíz del directorio. Importa
     * porque el traslado del §4.1 se marca como terminado y no se vuelve a mirar: si
     * aparecen blobs en plano después de eso —una versión anterior del plugin escribiendo
     * sobre el mismo proyecto es la única forma—, hay que deshacer esa marca. Es deriva
     * desde fuera del plugin, que es exactamente lo que este recorrido detecta.
     */
    data class Scan(val blobs: List<Blob>, val temporaries: List<Blob>, val flat: Int = 0)

    /** Lo que hizo una pasada de [relocate]: cuántos movió y si quedan más. */
    data class Relocation(val moved: Int, val remaining: Boolean)

    // ------------------------------------------------------------------ rutas

    /** Dónde **se escribe** un blob: la ruta fragmentada. */
    fun path(repo: RepoKey, id: AttachmentId): Path {
        val dir = layout.attachmentsDir(repo)
        val shards = BlobLayout.shardsOf(id) ?: return dir.resolve(BlobLayout.fileName(id))
        return dir.resolve(shards.first).resolve(shards.second).resolve(BlobLayout.fileName(id))
    }

    fun thumbnailPath(repo: RepoKey, id: AttachmentId): Path {
        val dir = layout.attachmentsDir(repo)
        val shards = BlobLayout.shardsOf(id) ?: return dir.resolve(BlobLayout.fileName(id, thumbnail = true))
        return dir.resolve(shards.first).resolve(shards.second)
            .resolve(BlobLayout.fileName(id, thumbnail = true))
    }

    /** Dónde lo dejaban las versiones anteriores a la 2.1: el directorio plano. */
    fun legacyPath(repo: RepoKey, id: AttachmentId, thumbnail: Boolean = false): Path =
        layout.attachmentsDir(repo).resolve(BlobLayout.fileName(id, thumbnail))

    /**
     * El fichero de verdad, mire donde mire, o `null` si no está.
     *
     * **Primero la ruta fragmentada y después la plana**, que es el orden del §4.1: lo
     * normal es que ya esté trasladado, y así el caso normal cuesta una comprobación.
     * Mientras el traslado no termine —o si se canceló a medias— la caída a la ruta
     * plana es lo que hace que las capturas de siempre se sigan viendo.
     */
    fun locate(repo: RepoKey, id: AttachmentId, thumbnail: Boolean = false): Path? {
        val sharded = if (thumbnail) thumbnailPath(repo, id) else path(repo, id)
        if (Files.exists(sharded)) return sharded
        val flat = legacyPath(repo, id, thumbnail)
        return if (flat != sharded && Files.exists(flat)) flat else null
    }

    fun exists(repo: RepoKey, id: AttachmentId): Boolean = locate(repo, id) != null

    // --------------------------------------------------------------- escritura

    /**
     * Guarda los bytes y devuelve su ID.
     *
     * Se escribe a un temporal y se mueve, igual que el fichero de tareas: un fallo a
     * media escritura no puede dejar un blob truncado bajo un nombre que promete ser
     * su propio hash. El temporal va **en la misma hoja** que el destino para que el
     * movimiento sea atómico: entre dos sistemas de ficheros no lo sería.
     */
    fun put(repo: RepoKey, bytes: ByteArray): AttachmentId {
        val id = AttachmentId(ImageNormalizer.sha256(bytes))
        val target = path(repo, id)
        // El contenido determina el nombre: si el fichero está, ya es este contenido.
        if (Files.exists(target)) return id
        // Y si está en plano, tampoco hay que reescribirlo: el traslado ya lo moverá.
        if (Files.exists(legacyPath(repo, id))) return id

        write(target, bytes)
        return id
    }

    /** La miniatura de un blob que ya está guardado. Ver [ImageNormalizer.thumbnail]. */
    fun putThumbnail(repo: RepoKey, id: AttachmentId, bytes: ByteArray) {
        val target = thumbnailPath(repo, id)
        if (Files.exists(target)) return
        write(target, bytes)
    }

    private fun write(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        layout.ensureIgnored()

        val tmp = target.resolveSibling(target.fileName.toString() + BlobLayout.TEMP_SUFFIX)
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            thisLogger().warn("Tasklane: ATOMIC_MOVE no soportado en $target, se usa move normal", e)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---------------------------------------------------------------- lectura

    /**
     * @return la imagen descodificada, o `null` si el blob no está o no se puede leer.
     *   Ausente no es excepcional: el cuerpo puede referenciar una imagen que ya
     *   recogió el GC o que se borró a mano, y quien pinta lo resuelve con un
     *   marcador de posición.
     */
    fun load(repo: RepoKey, id: AttachmentId, thumbnail: Boolean = false): BufferedImage? {
        val file = locate(repo, id, thumbnail) ?: return null
        return read(file)
    }

    private fun read(file: Path): BufferedImage? = try {
        Files.newInputStream(file).use(ImageIO::read)
    } catch (e: Exception) {
        thisLogger().warn("Tasklane: no se pudo leer el adjunto $file", e)
        null
    }

    /**
     * El tamaño de una imagen leyendo su cabecera, sin descodificarla. Lo pide la adopción.
     *
     * Primero como PNG —veinticuatro bytes, y es lo que son casi todos—; si no lo es,
     * porque desde la 2.3 un fichero soltado se guarda con su formato, se le pregunta al
     * lector de `ImageIO`, que también se queda en la cabecera.
     */
    fun dimensionsOf(file: Path): ImageNormalizer.Size? = try {
        Files.newInputStream(file).use { input ->
            val header = ByteArray(ImageNormalizer.HEADER_BYTES)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            if (read < header.size) null else ImageNormalizer.dimensions(header)
        } ?: Files.newInputStream(file).use(ImageNormalizer::sizeOf)
    } catch (e: Exception) {
        thisLogger().warn("Tasklane: no se pudo leer la cabecera de $file", e)
        null
    }

    // --------------------------------------------------------------- borrado

    /**
     * Borra un blob **y su miniatura**, estén fragmentados o en plano.
     *
     * La pareja entera porque dejarse media es dejar basura que ya nadie sabe nombrar:
     * una miniatura huérfana no la referencia ningún cuerpo y no tiene fila propia en la
     * tabla, así que ni el recolector ni la reconciliación volverían a mirarla.
     *
     * @param flatToo si hay que mirar además en el directorio plano. Quien sabe que el
     *   traslado del §4.1 ya terminó pasa `false` y se ahorra dos `unlink` por blob que
     *   no pueden encontrar nada — y en una recolección grande eso es la mitad de las
     *   llamadas al sistema.
     * @return `true` si se borró algo.
     */
    fun delete(repo: RepoKey, id: AttachmentId, flatToo: Boolean = true): Boolean {
        var gone = false
        for (thumbnail in listOf(false, true)) {
            val sharded = if (thumbnail) thumbnailPath(repo, id) else path(repo, id)
            gone = delete(sharded) || gone
            if (!flatToo) continue
            val flat = legacyPath(repo, id, thumbnail)
            if (flat != sharded) gone = delete(flat) || gone
        }
        return gone
    }

    fun delete(path: Path): Boolean = runCatching { Files.deleteIfExists(path) }
        .onFailure { thisLogger().warn("Tasklane: no se pudo borrar el adjunto $path", it) }
        .getOrDefault(false)

    /** Lo que hizo [deleteAll]: cuántos ficheros y bytes se fueron, y si llegó al final. */
    data class Wipe(val files: Long, val bytes: Long, val complete: Boolean)

    /**
     * Borra **todo** el directorio de adjuntos de un repositorio: originales, miniaturas,
     * lo que quedara en plano y los temporales. Es la mitad de disco de «borrar todas las
     * imágenes» de los ajustes; la otra mitad son las filas de `blob`.
     *
     * Con memoria constante, como el borrado de un repositorio entero
     * (`TaskFileStore.delete`): `walkFileTree` entrega cada directorio **después** de sus
     * hijos, y lo único que recuerda es la rama en la que está.
     *
     * [cancelled] se mira entre fichero y fichero. Parar a medias deja un directorio con
     * menos imágenes y una tabla que todavía las cuenta: quien llama tiene que hacer que la
     * reconciliación vuelva a pasar, que es justo lo que arregla esa deriva.
     *
     * [onFile] recibe cuántos van, para la barra.
     */
    fun deleteAll(repo: RepoKey, cancelled: () -> Boolean = { false }, onFile: (Long) -> Unit = {}): Wipe {
        val dir = layout.attachmentsDir(repo)
        if (!Files.isDirectory(dir)) return Wipe(0, 0, complete = true)
        var files = 0L
        var bytes = 0L
        var stopped = false
        Files.walkFileTree(
            dir,
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (cancelled()) {
                        stopped = true
                        return java.nio.file.FileVisitResult.TERMINATE
                    }
                    if (delete(file)) {
                        files++
                        bytes += attrs.size()
                        onFile(files)
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): java.nio.file.FileVisitResult {
                    thisLogger().warn("Tasklane: no se pudo visitar $file", exc)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                    // Un directorio que no se vació —un fichero que no se pudo borrar— se
                    // queda, y no es un error: `deleteIfExists` sobre él falla y se anota.
                    runCatching { Files.deleteIfExists(dir) }
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
        return Wipe(files, bytes, complete = !stopped)
    }

    // ------------------------------------------------- traslado del plano al árbol

    /**
     * Mueve a su hoja hasta [limit] blobs de los que quedaran en el directorio plano.
     *
     * **Perezoso y por tandas**, que es lo que dice el §4.1: quien tenga cuatro
     * capturas no se entera, y quien tenga cien mil las va moviendo en segundo plano sin
     * que nadie espere. Mientras tanto [locate] sigue encontrándolas donde estén, así
     * que una cancelación a medias no rompe nada — sólo deja trabajo para la próxima.
     *
     * Se listan **como mucho [limit] entradas** y no el directorio entero: con un
     * `Files.list(...).toList()` volveríamos a materializar justo lo que esta fase vino
     * a quitar.
     */
    fun relocate(repo: RepoKey, limit: Int = RELOCATE_BATCH, cancelled: () -> Boolean = { false }): Relocation {
        val dir = layout.attachmentsDir(repo)
        if (!Files.isDirectory(dir)) return Relocation(0, remaining = false)

        var moved = 0
        var remaining = false
        try {
            Files.newDirectoryStream(dir).use { entries ->
                for (entry in entries) {
                    if (cancelled()) return Relocation(moved, remaining = true)
                    val name = entry.fileName.toString()
                    // Los directorios del árbol nuevo, no. Sólo lo que quedó en plano.
                    if (Files.isDirectory(entry)) continue
                    val (id, thumbnail) = BlobLayout.ownerOf(name)
                        ?.takeIf { BlobLayout.shardsOf(it.first) != null }
                        ?: continue
                    if (moved >= limit) {
                        remaining = true
                        break
                    }
                    val target = if (thumbnail) thumbnailPath(repo, id) else path(repo, id)
                    if (move(entry, target)) moved++
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Tasklane: no se pudo trasladar el directorio de adjuntos $dir", e)
            return Relocation(moved, remaining = true)
        }
        return Relocation(moved, remaining)
    }

    private fun move(from: Path, to: Path): Boolean = try {
        Files.createDirectories(to.parent)
        // REPLACE_EXISTING: si el destino ya está, es el mismo contenido —el nombre es
        // su hash—, así que sustituirlo es idempotente y no pierde nada.
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: Exception) {
        thisLogger().warn("Tasklane: no se pudo mover $from a $to", e)
        false
    }

    // ------------------------------------------------------ recorrido (§4.3)

    /**
     * Recorre el árbol de adjuntos **por tandas y con memoria constante**.
     *
     * Es el único sitio del plugin que vuelve a mirar todos los ficheros, y por eso
     * corre como mucho una vez por semana y en segundo plano
     * ([AttachmentChore.RECONCILE_EVERY_MILLIS]). Nada se acumula en memoria entre
     * tandas: quien llama decide qué hacer con cada [Scan] y la suelta.
     *
     * Entra en los dos niveles de hojas y **también** mira la raíz, que es donde están
     * los blobs de quien todavía no ha terminado el traslado.
     *
     * @return `false` si se canceló a mitad. Un recorrido cancelado no es un recorrido:
     *   quien llama no debe sacar de él conclusiones sobre lo que falta.
     */
    fun scan(
        repo: RepoKey,
        batch: Int = SCAN_BATCH,
        cancelled: () -> Boolean = { false },
        onBatch: (Scan) -> Unit,
    ): Boolean {
        val dir = layout.attachmentsDir(repo)
        if (!Files.isDirectory(dir)) return true

        val blobs = ArrayList<Blob>(batch)
        val temporaries = ArrayList<Blob>()
        var flat = 0
        val flush = {
            if (blobs.isNotEmpty() || temporaries.isNotEmpty()) {
                onBatch(Scan(ArrayList(blobs), ArrayList(temporaries), flat))
                blobs.clear()
                temporaries.clear()
                flat = 0
            }
        }

        val done = walk(dir, MAX_DEPTH, cancelled) { path, name ->
            when {
                BlobLayout.isTemporary(name) -> describe(path, AttachmentId(""))?.let { temporaries += it }
                BlobLayout.isThumbnail(name) -> Unit // se va con su original; no se cuenta
                else -> BlobLayout.idOf(name)?.let { id ->
                    describe(path, id)?.let {
                        blobs += it
                        if (path.parent == dir) flat++
                    }
                }
            }
            if (blobs.size >= batch) flush()
        }
        flush()
        return done
    }

    private fun walk(dir: Path, depth: Int, cancelled: () -> Boolean, onFile: (Path, String) -> Unit): Boolean {
        try {
            Files.newDirectoryStream(dir).use { entries ->
                for (entry in entries) {
                    if (cancelled()) return false
                    val name = entry.fileName.toString()
                    if (Files.isDirectory(entry)) {
                        if (depth > 0 && !walk(entry, depth - 1, cancelled, onFile)) return false
                    } else {
                        onFile(entry, name)
                    }
                }
            }
        } catch (e: Exception) {
            // Un directorio ilegible no invalida el resto del recorrido, pero sí es un
            // recorrido incompleto: se dice que no terminó para que nadie deduzca de él
            // qué ficheros faltan.
            thisLogger().warn("Tasklane: no se pudo recorrer $dir", e)
            return false
        }
        return true
    }

    private fun describe(path: Path, id: AttachmentId): Blob? = try {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (attrs.isRegularFile) Blob(id, path, attrs.lastModifiedTime().toInstant(), attrs.size()) else null
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Cuántos blobs se mueven por pasada. Ver [relocate]. */
        const val RELOCATE_BATCH = 2_000

        /** El tamaño de la tanda del recorrido: lo que cabe en una consulta `IN`. */
        const val SCAN_BATCH = 400

        /** `ab/cd`: dos niveles de hoja, y ni uno más. */
        private const val MAX_DEPTH = 2
    }
}
