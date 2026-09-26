package com.tasklane.ui

import com.tasklane.ui.toolwindow.BadgeWalk
import com.tasklane.ui.toolwindow.BadgeWalk.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/** Adónde va el tabulador dentro de una tarjeta (P34). */
class BadgeWalkTest {

    @Test
    fun `tab entra en la tarjeta, la recorre y sale por el final`() {
        assertEquals(Step.To(0), BadgeWalk.step(null, 3, forward = true))
        assertEquals(Step.To(1), BadgeWalk.step(0, 3, forward = true))
        assertEquals(Step.To(2), BadgeWalk.step(1, 3, forward = true))
        assertEquals("pasado el último, el tabulador sigue su camino", Step.Out, BadgeWalk.step(2, 3, forward = true))
    }

    @Test
    fun `shift tab vuelve hasta la tarjeta y desde la tarjeta sale`() {
        assertEquals(Step.To(1), BadgeWalk.step(2, 3, forward = false))
        assertEquals(Step.Card, BadgeWalk.step(0, 3, forward = false))
        assertEquals(Step.Out, BadgeWalk.step(null, 3, forward = false))
    }

    @Test
    fun `una tarjeta sin nada que pulsar no retiene el tabulador`() {
        assertEquals(Step.Out, BadgeWalk.step(null, 0, forward = true))
        assertEquals(Step.Out, BadgeWalk.step(null, 0, forward = false))
    }

    /** La tarjeta cambió desde que se llegó al distintivo: se plegó, o se quitó un enlace. */
    @Test
    fun `si la tarjeta encoge no se sale de ella`() {
        assertEquals(Step.To(2), BadgeWalk.step(5, 3, forward = false))
        assertEquals(Step.Out, BadgeWalk.step(5, 3, forward = true))
        assertEquals(Step.Card, BadgeWalk.step(2, 0, forward = false))
    }
}
