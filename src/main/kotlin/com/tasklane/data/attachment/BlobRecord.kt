package com.tasklane.data.attachment

import com.tasklane.domain.model.AttachmentId
import java.time.Instant

/**
 * Un blob **tal y como lo conoce la base**: qué es, cuánto pesa, de qué tamaño y desde
 * cuándo está.
 *
 * Es la pieza que la Fase 4 pone donde antes estaba el directorio. Hasta la Fase 3 la
 * única forma de saber qué adjuntos había era listarlos, y eso es la operación que el
 * §1.6 del plan de escala señala como imposible a diez millones de ficheros: un
 * `Files.list` más un `readAttributes` por entrada, en el arranque, para poder decidir
 * qué sobra. Desde aquí, lo mismo es un salto de índice.
 *
 * **No confundir con [AttachmentStore.Blob]**, que es lo que hay *en disco*. Los dos
 * existen a propósito y por separado porque la reconciliación del §4.3 consiste
 * exactamente en comparar uno con otro: ficheros que la tabla no conoce —se adoptan— y
 * filas cuyo fichero ya no está —se marcan ausentes—.
 *
 * [createdAt] no es la fecha del fichero por casualidad: es lo que abre el **periodo de
 * gracia** de [AttachmentGc], así que una fila adoptada por el reconciliador la hereda
 * del fichero en vez de estrenarla. Ver `TaskStore.adoptBlobs`.
 */
data class BlobRecord(
    val id: AttachmentId,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val createdAt: Instant,
    /** Su fila sigue, su fichero no. Lo pone la reconciliación; la tarjeta ya sabe pintarlo. */
    val missing: Boolean = false,
)

/**
 * Cuándo se hizo por última vez una tarea de mantenimiento, y con qué número.
 *
 * Qué significa [n] lo decide cada tarea y está escrito en [AttachmentChore]. Vive en
 * la base y no en la configuración del IDE porque es estado **de estos datos**: copiar
 * el proyecto a otra máquina tiene que llevarse consigo que la reconciliación ya se
 * hizo, y reinstalar el IDE no tiene por qué volver a dispararla.
 */
data class Chore(val at: Long, val n: Long)

/**
 * Las tres tareas de fondo de los adjuntos, con su nombre en la tabla `chore`.
 *
 * Están juntas porque comparten una regla: **ninguna puede correr en cada apertura**.
 * Recorrer el árbol entero de adjuntos, mover ficheros o avisar de la cuota son cosas
 * que cuestan y que no cambian de resultado dos veces en la misma mañana.
 */
object AttachmentChore {

    /**
     * El traslado del directorio plano al fragmentado (§4.1). `n` = cuántos quedaron
     * por mover la última vez, y por eso hay marca: cuando llega a cero, no se vuelve a
     * mirar.
     */
    fun relocation(repo: String): String = "blob.relocate:$repo"

    /** La reconciliación del §4.3. `n` = cuántos blobs se adoptaron en la última pasada. */
    fun reconcile(repo: String): String = "blob.fsck:$repo"

    /**
     * El aviso de cuota del §4.5, **de un repositorio** desde la 2.3. `n` = los bytes por
     * los que se avisó la última vez.
     */
    fun quota(repo: String): String = "blob.quota:$repo"

    /**
     * Cada cuánto se reconcilia, como mucho.
     *
     * Una semana, que es lo que pide el §4.3 y lo que se corresponde con lo que
     * detecta: deriva causada **desde fuera del plugin** —un `rm`, una copia de
     * seguridad restaurada a medias, un `.idea` sincronizado a mano—. Nada de eso pasa
     * a diario, y el recorrido cuesta lo que cuesta mirar todos los ficheros.
     */
    const val RECONCILE_EVERY_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
}
