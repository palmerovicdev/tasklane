package com.tasklane.data.attachment

import com.tasklane.domain.model.AttachmentId

/**
 * Dónde va cada blob dentro del directorio de adjuntos, y cómo se llama.
 *
 *     attachments/ab/cd/<sha256>.png          el original
 *     attachments/ab/cd/<sha256>.thumb.png    su miniatura, si hace falta
 *
 * **Por qué se fragmenta** (§4.1 del plan de escala). Un directorio plano con diez
 * millones de entradas no es lento: es intratable. `Files.list` sobre él no termina en
 * un tiempo razonable en ningún sistema de ficheros de los que hay, y ni siquiera hace
 * falta llegar ahí —ext4 sin `dir_index`, HFS+ y los directorios compartidos por red
 * degradan mucho antes—. Con los cuatro primeros dígitos hexadecimales del SHA hay
 * **65.536 hojas** y ~150 blobs por hoja con diez millones, que es un directorio que
 * cualquier sistema abre sin pensarlo.
 *
 * **Dos niveles de dos dígitos y no uno de cuatro**, que daría las mismas hojas: un
 * único directorio con 65.536 subdirectorios dentro vuelve a ser un directorio enorme,
 * y el problema se habría movido en vez de resuelto. Así ningún directorio del árbol
 * pasa de 256 entradas.
 *
 * **El SHA reparte solo.** No hay que equilibrar nada ni llevar cuenta de qué hoja está
 * llena: la salida de SHA-256 es uniforme, así que los dos primeros bytes reparten los
 * blobs por igual entre las hojas sin que nadie lo organice.
 *
 * Puro y sin `Path` a propósito: la política de nombres se fija con un test de texto, y
 * quien toca disco es [AttachmentStore].
 */
object BlobLayout {

    /** Lo que se le añade al nombre para la miniatura del §4.4. */
    const val THUMB_SUFFIX = ".thumb"

    /** Un SHA-256 en hexadecimal. Lo que no mida esto, no es un blob. */
    const val SHA256_HEX = 64

    private const val SHARD = 2

    /**
     * Los dos segmentos de directorio de un blob, o `null` si el id no tiene forma de
     * SHA-256.
     *
     * Devolver `null` en vez de inventar una hoja no es remilgo: por el directorio de
     * adjuntos pasa lo que el usuario deje caer ahí, y un id que no es un hash no puede
     * decidir dónde se escribe un fichero.
     */
    fun shardsOf(id: AttachmentId): Pair<String, String>? {
        val value = id.value
        if (value.length != SHA256_HEX || !value.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return value.substring(0, SHARD) to value.substring(SHARD, SHARD * 2)
    }

    /** `ab/cd/<sha>.png`, con `/` porque es una ruta relativa lógica y no del sistema. */
    fun relativePath(id: AttachmentId, thumbnail: Boolean = false): String? {
        val (first, second) = shardsOf(id) ?: return null
        return "$first/$second/${fileName(id, thumbnail)}"
    }

    fun fileName(id: AttachmentId, thumbnail: Boolean = false): String =
        id.value + (if (thumbnail) THUMB_SUFFIX else "") + "." + ImageNormalizer.EXTENSION

    /**
     * El id que hay dentro de un nombre de fichero, o `null` si ese nombre no es el de
     * un blob.
     *
     * **Las miniaturas no son blobs** y por eso devuelven `null`: no se referencian, no
     * se cuentan y no se recolectan por su cuenta —se van con el original—. Que el
     * recorrido del §4.3 las adoptara como si fueran capturas sueltas duplicaría la
     * contabilidad entera.
     */
    fun idOf(name: String): AttachmentId? {
        val stem = name.removeSuffix("." + ImageNormalizer.EXTENSION)
        if (stem == name || stem.length != SHA256_HEX) return null
        return AttachmentId(stem)
    }

    /**
     * El blob al que pertenece un fichero del directorio, y si lo que se está mirando es
     * su miniatura. `null` si el nombre no es de ninguno de los dos.
     *
     * Lo usa el traslado del §4.1, que tiene que mover **la pareja entera**: dejar una
     * miniatura en plano y su original en la hoja no rompe nada —cada uno se busca por
     * su lado—, pero deja el directorio plano sin vaciarse nunca, que es justo lo que
     * el traslado viene a conseguir.
     */
    fun ownerOf(name: String): Pair<AttachmentId, Boolean>? {
        val extension = "." + ImageNormalizer.EXTENSION
        if (!name.endsWith(extension)) return null
        val thumbnail = isThumbnail(name)
        val stem = name.removeSuffix(extension).let { if (thumbnail) it.removeSuffix(THUMB_SUFFIX) else it }
        if (stem.length != SHA256_HEX) return null
        return AttachmentId(stem) to thumbnail
    }

    fun isThumbnail(name: String): Boolean =
        name.endsWith(THUMB_SUFFIX + "." + ImageNormalizer.EXTENSION)

    /** Un `.tmp` es el residuo de una escritura interrumpida, nunca un blob. */
    fun isTemporary(name: String): Boolean = name.endsWith(TEMP_SUFFIX)

    const val TEMP_SUFFIX = ".tmp"
}
