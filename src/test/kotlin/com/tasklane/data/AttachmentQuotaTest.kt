package com.tasklane.data

import com.tasklane.data.attachment.AttachmentQuota
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La cuota con aviso del §4.5, que es la decisión de la Fase 4 que le habla al usuario
 * —y por tanto la que más fácil sería volver cargante—.
 *
 * Dos cosas se fijan aquí y las dos importan: que avisa cuando hay que avisar, y que
 * **no** avisa en cada apertura por el mismo giga de siempre.
 */
class AttachmentQuotaTest {

    private val giga = 1024L * 1024 * 1024
    private val limit = 5 * giga

    @Test
    fun `por debajo del umbral no se dice nada`() {
        assertEquals(AttachmentQuota.Decision.Quiet, AttachmentQuota.decide(giga, limit, 0))
    }

    @Test
    fun `al cruzarlo se avisa una vez`() {
        val crossed = 6 * giga
        assertEquals(AttachmentQuota.Decision.Warn(crossed), AttachmentQuota.decide(crossed, limit, 0))
        // Y la apertura siguiente, con el mismo peso, ya no.
        assertEquals(AttachmentQuota.Decision.Quiet, AttachmentQuota.decide(crossed, limit, crossed))
    }

    /** El segundo aviso tiene que querer decir algo nuevo: «esto sigue creciendo». */
    @Test
    fun `se vuelve a avisar cuando crece de verdad`() {
        val warned = 6 * giga
        assertEquals(AttachmentQuota.Decision.Quiet, AttachmentQuota.decide(8 * giga, limit, warned))
        assertEquals(AttachmentQuota.Decision.Warn(9 * giga), AttachmentQuota.decide(9 * giga, limit, warned))
    }

    /** Bajar del umbral rearma el aviso: volver a cruzarlo vuelve a ser noticia. */
    @Test
    fun `bajar del umbral olvida el aviso`() {
        assertEquals(AttachmentQuota.Decision.Forget, AttachmentQuota.decide(giga, limit, 6 * giga))
        assertEquals(AttachmentQuota.Decision.Quiet, AttachmentQuota.decide(giga, limit, 0))
    }

    @Test
    fun `con la cuota apagada no se avisa nunca`() {
        assertEquals(AttachmentQuota.Decision.Quiet, AttachmentQuota.decide(500 * giga, 0, 0))
    }

    /**
     * El cero tiene que sobrevivir a `normalized()`: acotarlo al mínimo convertiría «no
     * me avises» en «avísame a partir de un giga», que es lo contrario de lo que se pidió.
     */
    @Test
    fun `apagar la cuota sobrevive a la normalizacion`() {
        val off = TasklaneConfig.DEFAULT.copy(imageQuotaMegabytes = TasklaneConfig.NO_IMAGE_QUOTA).normalized()
        assertEquals(TasklaneConfig.NO_IMAGE_QUOTA, off.imageQuotaMegabytes)

        val tiny = TasklaneConfig.DEFAULT.copy(imageQuotaMegabytes = 3).normalized()
        assertEquals(TasklaneConfig.MIN_IMAGE_QUOTA_MB, tiny.imageQuotaMegabytes)
    }

    @Test
    fun `el umbral por defecto son cinco gigas`() {
        assertEquals(5 * giga, AttachmentQuota.bytesOf(TasklaneConfig.DEFAULT_IMAGE_QUOTA_MB))
    }
}
