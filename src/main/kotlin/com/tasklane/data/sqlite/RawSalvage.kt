package com.tasklane.data.sqlite

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Salvar las tareas de una `tasklane.db` **cuyo esquema no se puede ni leer**.
 *
 * [StoreRecovery] rescata tabla a tabla con SQL, y eso necesita que SQLite consiga abrir el
 * fichero. Hay un daño que lo impide sin haber tocado una sola fila de tarea: si `sqlite_master`
 * —el propio esquema— queda incoherente, cada consulta falla con
 * `malformed database schema (…)` y no hay `SELECT` que valga. Es lo que pasó el 2026-09-28
 * cuando el fichero `.db` se sustituyó por otro **con el `-wal` de la sesión anterior aún al
 * lado**: SQLite superpuso un diario que no era de ese fichero y el esquema apuntaba a
 * hojas que ya eran de otra tabla. Las 181 tareas estaban intactas en sus páginas.
 *
 * Esto **no usa SQLite**. Lee el formato de fichero directamente —páginas de 4 KB, hojas de
 * tabla, celdas con su registro— y reconoce cada tabla **por la forma de sus filas**, que
 * es lo único que no depende del esquema:
 *
 * - `task`: 24 columnas —siete de texto seguidas de catorce enteros y un texto—, con el id en la segunda.
 * - `tag`: 10 columnas: cuatro de texto y seis enteros.
 * - `anchor`: 7 columnas (6 antes de `span`): id, entero, texto, dos enteros y texto.
 *
 * ## Una sola vista: la más reciente
 *
 * Se lee **el fichero con su diario superpuesto** —las tramas del WAL válidas hasta el último
 * commit—, que es lo que SQLite habría visto: lo último que se escribió gana a lo que había
 * en la base. No se junta con el fichero a secas: una tarea que sólo está en la base y no en
 * lo más reciente es una tarea **borrada** durante la sesión, y unir las dos vistas la
 * resucitaba —con la base real del 2026-09-28 eran 15 tareas que ya no estaban—. Sin diario,
 * la vista es el fichero.
 *
 * Entre varias versiones de la misma tarea en hojas distintas, gana la de `updated_at`
 * mayor. **Puede reaparecer alguna borrada** si su página seguía sin reutilizar, y por eso el
 * resultado se cuenta como recuperación con posible pérdida, no como limpia: es el criterio
 * de `StoreRecovery` —el fichero de más es un problema mucho menor que el de menos—.
 *
 * Sin IDE y sin dependencias, para probarlo rompiendo ficheros de verdad.
 */
internal object RawSalvage {

    class Salvaged(
        /** Filas de `task` con las 24 columnas, `seq` (el rowid) primero. */
        val tasks: List<Array<Any>>,
        val tags: List<Array<Any>>,
        val anchors: List<Array<Any>>,
        /** Páginas de hoja de tabla que se recorrieron, entre las dos vistas. */
        val leaves: Int,
    ) {
        val isEmpty: Boolean get() = tasks.isEmpty()
    }

    const val TASK_COLUMNS = 24
    const val TAG_COLUMNS = 10
    const val ANCHOR_COLUMNS = 7

    /** Un id de tarea: texto corto y no vacío. Son UUID, pero un test —o un import— puede traer otro. */
    private fun isId(id: String) = id.isNotEmpty() && id.length <= 64

    private const val TABLE_LEAF = 0x0D
    private const val MAX_OVERFLOW_HOPS = 4096

    /**
     * El diario de [file], cargado **ya**. Quien vaya a abrir el fichero con SQLite —que al
     * cerrar vuelca el WAL a la base y lo borra— lo llama antes, para que las dos vistas de
     * [read] sigan existiendo después. Un WAL es acotado —SQLite lo vuelca solo a los pocos
     * miles de páginas—, así que cabe en memoria.
     */
    fun preload(file: Path): Wal? =
        TaskDb.walOf(file).takeIf { Files.isRegularFile(it) && Files.size(it) > 32 }
            ?.let { runCatching { WalFrames.read(it) }.getOrNull() }
            ?.takeIf { it.pages.isNotEmpty() }

    /** Lee [file] y su diario, [wal] si ya se cargó con [preload]. Nunca escribe. */
    fun read(file: Path, wal: Wal? = preload(file)): Salvaged {
        val views = listOf(Pages(file, wal))

        val tasks = LinkedHashMap<String, Array<Any>>()
        val tags = LinkedHashMap<String, Array<Any>>()
        val anchors = LinkedHashMap<String, Array<Any>>()
        var leaves = 0

        for (view in views) {
            view.use {
                // Las páginas libres guardan, sin borrar, lo que había en ellas: son las tareas
                // que se borraron. `.recover` de SQLite también las salta.
                val free = it.freePages()
                for (page in 1..it.count) {
                    if (page in free) continue
                    val bytes = it.page(page) ?: continue
                    val header = if (page == 1) 100 else 0
                    if ((bytes[header].toInt() and 0xFF) != TABLE_LEAF) continue
                    leaves++
                    for (row in it.rows(bytes, header)) {
                        when {
                            isTask(row) -> {
                                val id = row.values[1] as String
                                val old = tasks[id]
                                if (old == null || (row.values[15] as Long) > (old[15] as Long)) {
                                    tasks[id] = Array(TASK_COLUMNS) { i -> if (i == 0) row.rowid else row.values[i]!! }
                                }
                            }

                            isTag(row) -> tags["${row.values[0]}\u0000${row.values[1]}"] = Array(TAG_COLUMNS) { row.values[it]!! }
                            isAnchor(row) -> anchors["${row.values[0]}\u0000${row.values[1]}"] =
                                Array(ANCHOR_COLUMNS) { i -> row.values.getOrNull(i) ?: 0L }
                        }
                    }
                }
            }
        }

        // Con páginas de generaciones distintas, dos tareas **distintas** pueden traer el mismo
        // `seq` (el rowid). El `seq` es interno —nada fuera de la base lo nombra— y la tabla lo
        // exige único: la segunda se quedaría fuera en silencio, así que se le da uno nuevo.
        val taken = HashSet<Long>()
        var next = (tasks.values.maxOfOrNull { it[0] as Long } ?: 0L) + 1
        for (row in tasks.values) if (!taken.add(row[0] as Long)) row[0] = next.also { taken += it }.also { next++ }

        // Sólo lo que cuelga de una tarea que se rescató.
        return Salvaged(
            tasks = tasks.values.toList(),
            tags = tags.values.filter { it[0] in tasks },
            anchors = anchors.values.filter { it[0] in tasks },
            leaves = leaves,
        )
    }

    // ------------------------------------------------------------ reconocer filas

    private class Row(val rowid: Long, val values: List<Any?>)

    private fun isTask(row: Row): Boolean {
        val v = row.values
        if (v.size != TASK_COLUMNS) return false
        val id = v[1] as? String ?: return false
        if (!isId(id)) return false
        // repo, título, cuerpo, etiquetas, ficheros, estado y prioridad son texto…
        for (i in 2..8) if (v[i] !is String) return false
        // …y de ahí a `extra`, enteros. seq (v[0]) es NULL en el registro: el rowid va en la celda.
        for (i in 9..22) if (v[i] !is Long) return false
        return v[15] is Long && v[23] is String
    }

    private fun isTag(row: Row): Boolean {
        val v = row.values
        if (v.size != TAG_COLUMNS) return false
        val id = v[0] as? String ?: return false
        if (!isId(id)) return false
        return v[1] is String && v[2] is String && v[3] is String && (4 until TAG_COLUMNS).all { v[it] is Long }
    }

    private fun isAnchor(row: Row): Boolean {
        val v = row.values
        if (v.size != ANCHOR_COLUMNS && v.size != ANCHOR_COLUMNS - 1) return false
        val id = v[0] as? String ?: return false
        return isId(id) && v[1] is Long && v[2] is String && v[3] is Long && v[4] is Long && v[5] is String &&
            (v.size == ANCHOR_COLUMNS - 1 || v[6] is Long)
    }

    // ------------------------------------------------------------------ páginas

    /** El fichero visto página a página, con o sin el diario encima. */
    private class Pages(file: Path, private val wal: Wal?) : AutoCloseable {
        private val raf = RandomAccessFile(file.toFile(), "r")
        private val size = raf.length()

        val pageSize: Int
        private val usable: Int
        val count: Int

        init {
            val head = ByteArray(100)
            val ok = size >= 100 && run { raf.seek(0); raf.readFully(head); true }
            val declared = if (ok) ((head[16].toInt() and 0xFF) shl 8) or (head[17].toInt() and 0xFF) else 0
            pageSize = when {
                declared == 1 -> 65536
                declared in 512..32768 && declared and (declared - 1) == 0 -> declared
                else -> 4096
            }
            usable = pageSize - (if (ok) head[20].toInt() and 0xFF else 0)
            // Con diario, el tamaño de la base es el que dejó su último commit: lo que el fichero
            // tenga más allá es de otra generación, no de esta base. Sin diario, lo que mida el fichero.
            count = wal?.size ?: (size / pageSize).toInt()
        }

        fun page(number: Int): ByteArray? {
            if (number < 1 || number > count) return null
            wal?.pages?.get(number)?.let { return it }
            val offset = (number - 1).toLong() * pageSize
            if (offset + pageSize > size) return null
            val out = ByteArray(pageSize)
            return runCatching { raf.seek(offset); raf.readFully(out); out }.getOrNull()
        }

        /** Las páginas de la lista libre: cada troncal, con la lista de hojas que lleva dentro. */
        fun freePages(): Set<Int> {
            val first = page(1) ?: return emptySet()
            var trunk = ByteBuffer.wrap(first, 32, 4).int
            val out = HashSet<Int>()
            var hops = 0
            while (trunk > 0 && trunk <= count && out.add(trunk) && ++hops <= MAX_OVERFLOW_HOPS) {
                val bytes = page(trunk) ?: break
                val buffer = ByteBuffer.wrap(bytes)
                val leaves = buffer.getInt(4).coerceIn(0, (pageSize / 4) - 2)
                for (i in 0 until leaves) out += buffer.getInt(8 + i * 4)
                trunk = buffer.getInt(0)
            }
            return out
        }

        /** Las filas de una hoja de tabla. Una celda que no se deja leer se salta, no tumba la hoja. */
        fun rows(bytes: ByteArray, header: Int): List<Row> {
            val cells = u16(bytes, header + 3)
            if (cells == 0 || header + 8 + cells * 2 > bytes.size) return emptyList()
            val out = ArrayList<Row>(cells)
            for (i in 0 until cells) {
                val offset = u16(bytes, header + 8 + i * 2)
                if (offset < header + 8 || offset >= bytes.size) continue
                runCatching { cell(bytes, offset) }.getOrNull()?.let(out::add)
            }
            return out
        }

        private fun cell(page: ByteArray, start: Int): Row? {
            var at = start
            val (payload, n1) = varint(page, at); at += n1
            val (rowid, n2) = varint(page, at); at += n2
            if (payload < 0 || payload > MAX_RECORD) return null

            val max = usable - 35
            val local = if (payload <= max) payload.toInt() else {
                val min = ((usable - 12) * 32 / 255) - 23
                val k = min + ((payload - min) % (usable - 4)).toInt()
                if (k <= max) k else min
            }
            if (at + local > page.size) return null

            val data = if (payload.toInt() == local) page.copyOfRange(at, at + local) else {
                val out = ByteArray(payload.toInt())
                System.arraycopy(page, at, out, 0, local)
                var filled = local
                var next = ByteBuffer.wrap(page, at + local, 4).int
                var hops = 0
                while (filled < out.size) {
                    if (next <= 0 || ++hops > MAX_OVERFLOW_HOPS) return null
                    val overflow = page(next) ?: return null
                    val take = minOf(usable - 4, out.size - filled)
                    System.arraycopy(overflow, 4, out, filled, take)
                    filled += take
                    next = ByteBuffer.wrap(overflow, 0, 4).int
                }
                out
            }
            return Row(rowid, record(data) ?: return null)
        }

        override fun close() = raf.close()
    }

    private const val MAX_RECORD = 1L shl 30

    /** Las páginas que dejó un diario WAL hasta su último commit, y cuántas tiene la base tras él. */
    class Wal(val pages: Map<Int, ByteArray>, val size: Int)

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /** Un varint de SQLite: hasta 9 bytes, los 8 primeros con 7 bits. Devuelve valor y longitud. */
    private fun varint(b: ByteArray, at: Int): Pair<Long, Int> {
        var value = 0L
        for (i in 0 until 8) {
            val byte = b[at + i].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F).toLong()
            if (byte < 0x80) return value to i + 1
        }
        return ((value shl 8) or (b[at + 8].toInt() and 0xFF).toLong()) to 9
    }

    /** Un registro: cabecera de tipos y después los valores. `null` en la lista es NULL de SQL. */
    private fun record(data: ByteArray): List<Any?>? {
        val (headerSize, first) = varint(data, 0)
        if (headerSize < first || headerSize > data.size) return null
        val types = ArrayList<Long>()
        var at = first
        while (at < headerSize) {
            val (type, n) = varint(data, at)
            types += type
            at += n
        }
        var pos = headerSize.toInt()
        val out = ArrayList<Any?>(types.size)
        for (type in types) {
            val size = when {
                type in 0..4 -> type.toInt()
                type == 5L -> 6
                type == 6L || type == 7L -> 8
                type == 8L || type == 9L -> 0
                type >= 12 -> if (type % 2 == 0L) ((type - 12) / 2).toInt() else ((type - 13) / 2).toInt()
                else -> return null
            }
            if (pos + size > data.size) return null
            out += when {
                type == 0L -> null
                type == 8L -> 0L
                type == 9L -> 1L
                type in 1..6 -> {
                    var v = if (data[pos].toInt() < 0) -1L else 0L
                    for (i in 0 until size) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
                    v
                }

                type == 7L -> java.lang.Double.longBitsToDouble(ByteBuffer.wrap(data, pos, 8).long)
                type % 2L == 0L -> data.copyOfRange(pos, pos + size)
                else -> String(data, pos, size, Charsets.UTF_8)
            }
            pos += size
        }
        return out
    }

    // ---------------------------------------------------------------------- WAL

    /**
     * Las páginas que dice un diario WAL: la última versión de cada una **hasta el último
     * commit válido**. Una trama vale si su sal coincide con la de la cabecera y su suma de
     * comprobación encadena con la anterior; la primera que no vale corta el diario.
     */
    private object WalFrames {

        fun read(file: Path): Wal {
            val bytes = Files.readAllBytes(file)
            if (bytes.size < 32) return Wal(emptyMap(), 0)
            val head = ByteBuffer.wrap(bytes)
            val magic = head.getInt(0)
            if (magic != 0x377f0682 && magic != 0x377f0683) return Wal(emptyMap(), 0)
            val bigEndian = magic == 0x377f0683
            val pageSize = head.getInt(8).let { if (it == 1) 65536 else it }
            if (pageSize < 512 || pageSize and (pageSize - 1) != 0) return Wal(emptyMap(), 0)
            val salt1 = head.getInt(16)
            val salt2 = head.getInt(20)

            var (s0, s1) = checksum(bytes, 0, 24, bigEndian, 0, 0)
            if (s0 != head.getInt(24) || s1 != head.getInt(28)) return Wal(emptyMap(), 0)

            val frame = 24 + pageSize
            val committed = HashMap<Int, ByteArray>()
            val pending = HashMap<Int, ByteArray>()
            var offset = 32
            var size = 0
            while (offset + frame <= bytes.size) {
                val page = head.getInt(offset)
                val commit = head.getInt(offset + 4)
                if (page <= 0 || head.getInt(offset + 8) != salt1 || head.getInt(offset + 12) != salt2) break
                val a = checksum(bytes, offset, 8, bigEndian, s0, s1)
                val b = checksum(bytes, offset + 24, pageSize, bigEndian, a.first, a.second)
                if (b.first != head.getInt(offset + 16) || b.second != head.getInt(offset + 20)) break
                s0 = b.first
                s1 = b.second
                pending[page] = bytes.copyOfRange(offset + 24, offset + frame)
                if (commit != 0) {
                    committed.putAll(pending)
                    pending.clear()
                    size = commit
                }
                offset += frame
            }
            return Wal(committed, size)
        }

        /** La suma de comprobación del WAL: dos acumuladores de 32 bits sobre pares de palabras. */
        private fun checksum(b: ByteArray, from: Int, length: Int, big: Boolean, i0: Int, i1: Int): Pair<Int, Int> {
            val order = if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            val buffer = ByteBuffer.wrap(b).order(order)
            var s0 = i0
            var s1 = i1
            var i = from
            while (i < from + length) {
                s0 += buffer.getInt(i) + s1
                s1 += buffer.getInt(i + 4) + s0
                i += 8
            }
            return s0 to s1
        }
    }
}
