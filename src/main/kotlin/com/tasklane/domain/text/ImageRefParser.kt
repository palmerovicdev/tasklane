package com.tasklane.domain.text

import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.AttachmentRef

/**
 * Las imágenes del cuerpo de una tarea: `![alt](tasklane:<sha256>)`.
 *
 * Igual que [LinkExtractor], se ejecuta **al escribir** y el resultado se cachea en
 * `Task.attachments`. Kotlin puro: ni IO ni IDE, así que se testea sin arrancar nada.
 *
 * Dos decisiones que parecen detalles y no lo son:
 *
 * - **El patrón es estricto**: exactamente 64 caracteres hexadecimales, que es lo
 *   que mide un SHA-256. Este parser es la única definición de «qué está
 *   referenciado», y quien decide qué blobs sobran —[com.tasklane.data.attachment.AttachmentGc]—
 *   la usa. Un patrón laxo aquí no borra de más: aceptar basura como referencia sólo
 *   mantendría vivos blobs que nadie usa. Uno estricto tampoco: la referencia la
 *   escribe el plugin, no el usuario.
 * - **Los bloques de código NO se ignoran**, al revés que con los enlaces. Un
 *   `![](tasklane:…)` dentro de un bloque ``` seguiría siendo una referencia real a
 *   un blob; saltárselo lo dejaría sin referencias y el GC lo borraría con el texto
 *   todavía apuntándole. Entre pintar un inlay de más y perder una imagen, la
 *   elección no está reñida.
 */
object ImageRefParser {

    /** El esquema de la referencia. No es una URL: no sale nunca del plugin. */
    const val SCHEME = "tasklane"

    private val PATTERN = Regex("""!\[[^\]\n]*]\($SCHEME:([0-9a-fA-F]{64})\)""")

    fun parse(body: String): List<AttachmentRef> {
        if (!body.contains("$SCHEME:")) return emptyList()
        return PATTERN.findAll(body)
            .map { AttachmentRef(AttachmentId(it.groupValues[1].lowercase()), it.range) }
            .toList()
    }

    /** Los IDs distintos, que es lo que el recolector necesita. */
    fun ids(body: String): Set<AttachmentId> = parse(body).mapTo(LinkedHashSet()) { it.id }

    /** El texto que se inserta en el cuerpo al pegar una imagen. */
    fun reference(id: AttachmentId): String = "![](${SCHEME}:${id.value})"

    /** El cuerpo sin las referencias. Lo usa la exportación: fuera del IDE un SHA no dice nada. */
    fun strip(body: String): String = if (body.contains("$SCHEME:")) PATTERN.replace(body, "") else body
}
