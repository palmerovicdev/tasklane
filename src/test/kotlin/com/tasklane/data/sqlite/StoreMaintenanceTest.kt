package com.tasklane.data.sqlite

import com.tasklane.data.attachment.Chore
import com.tasklane.data.sqlite.StoreMaintenance.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuándo se copia y cuándo se comprueba la base. Son decisiones sobre el fichero de
 * alguien, y por eso son puras y están aquí escritas caso a caso.
 */
class StoreMaintenanceTest {

    private val day = StoreMaintenance.BACKUP_EVERY_MILLIS
    private val now = 100 * day
    private val gb = 1L shl 30

    private fun backup(
        backupAt: Long? = now - 2 * day,
        modifiedAt: Long = now - day,
        healthy: Boolean = true,
        free: Long = 100 * gb,
        db: Long = gb,
    ) = StoreMaintenance.backup(backupAt, modifiedAt, now, healthy, free, db)

    @Test
    fun `sin copia, se copia`() {
        assertNull(backup(backupAt = null))
    }

    @Test
    fun `una copia de hace mas de un dia con cambios detras se rehace`() {
        assertNull(backup())
    }

    @Test
    fun `una copia de hoy no se rehace`() {
        assertEquals(Skip.RECENT, backup(backupAt = now - day / 2, modifiedAt = now))
    }

    /** Un proyecto que no se toca no se vuelve a copiar cada día: la copia de ayer ya es la de hoy. */
    @Test
    fun `si nada cambio desde la copia no se copia`() {
        assertEquals(Skip.UNCHANGED, backup(backupAt = now - 3 * day, modifiedAt = now - 4 * day))
    }

    /**
     * **Nunca con la base bajo sospecha.** La copia sustituye a la anterior: copiar una base
     * dañada borraría la última copia buena justo cuando más falta hace. Manda sobre todo lo
     * demás, incluso sobre no tener copia.
     */
    @Test
    fun `una base bajo sospecha no se copia, aunque no haya copia`() {
        assertEquals(Skip.UNHEALTHY, backup(healthy = false))
        assertEquals(Skip.UNHEALTHY, backup(backupAt = null, healthy = false))
    }

    @Test
    fun `sin sitio en disco no se copia`() {
        assertEquals(Skip.NO_SPACE, backup(free = gb, db = gb))
        assertNull("con margen, sí", backup(free = 2 * gb, db = gb))
    }

    @Test
    fun `se comprueba tras un cierre sucio, con una pendiente o con danos en la ultima`() {
        assertTrue(StoreMaintenance.checkIntegrity(dirty = true, pending = null, last = null))
        assertTrue(StoreMaintenance.checkIntegrity(dirty = false, pending = Chore(now, 0), last = null))
        assertTrue(StoreMaintenance.checkIntegrity(dirty = false, pending = null, last = Chore(now, 3)))
        assertFalse("una base sana y cerrada limpia no se comprueba", StoreMaintenance.checkIntegrity(false, null, Chore(now, 0)))
        assertFalse(StoreMaintenance.checkIntegrity(false, null, null))
    }

    @Test
    fun `sana es sin pendientes y sin danos`() {
        assertTrue(StoreMaintenance.healthy(null, null))
        assertTrue(StoreMaintenance.healthy(null, Chore(now, 0)))
        assertFalse(StoreMaintenance.healthy(Chore(now, 0), null))
        assertFalse(StoreMaintenance.healthy(null, Chore(now, 1)))
    }
}
