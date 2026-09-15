package com.tasklane.data.attachment

/**
 * La cuota con aviso del §4.5 del plan de escala, que es **una política y no una
 * arquitectura**, y conviene decirlo sin rodeos:
 *
 * > Diez imágenes únicas por tarea × un millón de tareas = diez millones de blobs. A
 * > 400 px son ~315 GB, y con deduplicación 10:1 unos 31 GB. No hay diseño que meta eso
 * > en un `.idea/`.
 *
 * Lo que la Fase 4 **sí** garantiza es que el *número* de blobs no rompa nada: búsqueda
 * por índice, recolección por tabla, arranque constante y una lista que no descodifica
 * nada grande. Con diez millones de blobs el plugin va igual de rápido. Lo que no puede
 * hacer es inventar disco, y por eso hay que avisar.
 *
 * **De las tres opciones del §4.5 se eligió la primera**: cuota con aviso. Nunca se
 * borra nada sin decirlo; lo único que hace esto es enseñar el peso y ofrecer las
 * salidas —bajar el tope de escalado, recolectar lo no referenciado, archivar—.
 *
 * Puro, y por el mismo motivo que [AttachmentGc]: una cifra que se le enseña al usuario
 * —y un aviso que puede volverse cargante— tienen que poder fijarse con un test. El
 * umbral en sí es configuración y vive en `TasklaneConfig.imageQuotaMegabytes`; aquí
 * sólo está lo que se decide con él.
 */
object AttachmentQuota {

    /**
     * Cuánto tiene que engordar desde el último aviso para volver a avisar.
     *
     * Sin esto, el aviso saldría en **cada apertura** en cuanto se cruzara el umbral, y
     * un aviso que sale siempre es un aviso que se aprende a cerrar sin leer. Con vez y
     * media, el segundo aviso quiere decir algo nuevo: «esto sigue creciendo, y rápido».
     */
    const val REWARN_GROWTH = 1.5

    sealed interface Decision {
        /** Ni avisar ni olvidar: o no se ha cruzado, o ya se avisó y no ha crecido bastante. */
        data object Quiet : Decision

        /** Se bajó del umbral: se olvida el aviso para que volver a cruzarlo vuelva a avisar. */
        data object Forget : Decision

        /** Avisar, y apuntar [bytes] como el peso por el que se avisó. */
        data class Warn(val bytes: Long) : Decision
    }

    /**
     * @param bytes lo que ocupan hoy los adjuntos del proyecto.
     * @param limitBytes el umbral configurado, o `0` para no avisar nunca.
     * @param lastWarnedBytes el peso por el que se avisó la última vez, o `0` si no se
     *   ha avisado. Sale de la tabla `chore`, así que sobrevive a cerrar el IDE — que es
     *   precisamente lo que hace que el aviso no sea cargante.
     */
    fun decide(bytes: Long, limitBytes: Long, lastWarnedBytes: Long): Decision = when {
        limitBytes <= 0L -> Decision.Quiet
        bytes < limitBytes -> if (lastWarnedBytes > 0L) Decision.Forget else Decision.Quiet
        lastWarnedBytes <= 0L -> Decision.Warn(bytes)
        bytes >= (lastWarnedBytes * REWARN_GROWTH) -> Decision.Warn(bytes)
        else -> Decision.Quiet
    }

    fun bytesOf(megabytes: Int): Long = megabytes.toLong() * 1024 * 1024
}
