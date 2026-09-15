package com.tasklane.data.sqlite

import com.tasklane.data.attachment.Chore

/**
 * Cuándo se copia y cuándo se comprueba la base. **Puro**, por lo mismo que
 * `AttachmentQuota` y `AttachmentGc`: son decisiones que se toman sobre el fichero de
 * alguien, y una decisión así tiene que poder fijarse con un test.
 *
 * Es el punto 4 de la Fase 5 del `docs/plan-escala.md`: el `.bak` que `TaskFileStore`
 * copiaba en **cada** volcado se sustituye por una copia diaria como mucho, y la
 * comprobación de integridad —cara sobre una base de gigas— pasa a hacerse sólo cuando
 * hay motivo.
 */
internal object StoreMaintenance {

    /** `tasklane.db.backup`, junto a la base. El `.gitignore` de los datos ya lo cubre. */
    const val BACKUP_SUFFIX = ".backup"

    /**
     * Hay una comprobación pendiente: la sesión anterior no cerró la base. Se apunta en la
     * base y no en memoria para que una comprobación que no llegue a terminar —se cierra
     * el IDE a mitad— se repita en la siguiente apertura. Ver [TaskDb.dirty].
     */
    const val INTEGRITY_PENDING = "db.integrity.pending"

    /** La última comprobación que terminó. `n` = cuántos problemas encontró; cero es sana. */
    const val INTEGRITY = "db.integrity"

    /** Una copia al día como mucho: el plan lo pide así, y es lo que la hace barata. */
    const val BACKUP_EVERY_MILLIS: Long = 24L * 60 * 60 * 1000

    /**
     * El margen de disco que se exige antes de copiar, sobre lo que ocupa la base. Una
     * copia de seguridad que llena el disco es peor que ninguna: se lleva por delante la
     * siguiente escritura del usuario.
     */
    const val SPACE_FACTOR = 1.5

    enum class Skip {
        /** Hay una comprobación pendiente o la última encontró daños. Ver [backup]. */
        UNHEALTHY,

        /** La copia que hay tiene menos de un día. */
        RECENT,

        /** Nada ha cambiado desde la copia que hay. */
        UNCHANGED,

        /** No cabe. */
        NO_SPACE,
    }

    /**
     * ¿Hay que copiar ya? `null` si sí; si no, por qué.
     *
     * La fecha de la última copia es **la del propio fichero** y no una marca en la base,
     * y no por ahorrarse una fila: apuntarla escribiría en la base, y esa escritura haría
     * que al día siguiente la base constara como cambiada aunque nadie hubiera tocado una
     * tarea. Así, un proyecto que no se edita no se vuelve a copiar, y una copia que el
     * usuario borre a mano se rehace en la apertura siguiente.
     *
     * **Nunca con la base bajo sospecha.** La copia sustituye a la anterior, y copiar una
     * base dañada sería borrar la última copia buena justo cuando más falta hace.
     *
     * @param backupAt cuándo se escribió la copia que hay, o `null` si no hay ninguna.
     * @param modifiedAt la última escritura de la base o de su diario.
     */
    fun backup(
        backupAt: Long?,
        modifiedAt: Long,
        now: Long,
        healthy: Boolean,
        freeBytes: Long,
        dbBytes: Long,
    ): Skip? = when {
        !healthy -> Skip.UNHEALTHY
        freeBytes < (dbBytes * SPACE_FACTOR).toLong() -> Skip.NO_SPACE
        backupAt == null -> null
        now - backupAt < BACKUP_EVERY_MILLIS -> Skip.RECENT
        modifiedAt <= backupAt -> Skip.UNCHANGED
        else -> null
    }

    /**
     * ¿Hay que comprobar la integridad? Si la última sesión no cerró, si hay una
     * comprobación que no llegó a terminar, o si la última encontró daños: una base
     * dañada se vuelve a mirar en cada apertura, que es lo que deja de avisar sola el día
     * que alguien la arregla.
     */
    fun checkIntegrity(dirty: Boolean, pending: Chore?, last: Chore?): Boolean =
        dirty || pending != null || (last != null && last.n > 0)

    /** Sana: ni pendiente ni con daños en la última comprobación. */
    fun healthy(pending: Chore?, last: Chore?): Boolean = pending == null && (last == null || last.n == 0L)
}
