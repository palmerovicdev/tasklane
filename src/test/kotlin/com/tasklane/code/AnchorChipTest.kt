package com.tasklane.code

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El texto de la pastilla del editor. Es el nombre del estado, no un `TODO` cableado:
 * los estados son configurables y son el vocabulario del plugin.
 */
class AnchorChipTest {

    /** Con la configuracion de fabrica, la marca dice exactamente lo que se pidio. */
    @Test
    fun `el estado por defecto da TODO`() {
        assertEquals("TODO", AnchorChip.labelOf("ToDo"))
    }

    @Test
    fun `una tarea en marcha no dice TODO`() {
        assertEquals("DOING", AnchorChip.labelOf("Doing"))
    }

    /** Un nombre largo metido dentro de una linea de codigo deja de ser una marca. */
    @Test
    fun `un nombre largo se recorta`() {
        val label = AnchorChip.labelOf("Waiting for review")

        assertEquals("WAITING FOR…", label)
        assertEquals(AnchorChip.MAX_LABEL, label.length)
    }

    /** Se corta por donde toca, aunque sea a mitad de palabra: lo que importa es el ancho. */
    @Test
    fun `el recorte es por ancho, no por palabra`() {
        assertEquals("IN CODE REV…", AnchorChip.labelOf("In code review"))
    }

    /** Y no deja un espacio colgando delante de los puntos. */
    @Test
    fun `el recorte no deja espacio antes de los puntos`() {
        assertEquals("WAITING ON…", AnchorChip.labelOf("Waiting on team"))
    }
}
