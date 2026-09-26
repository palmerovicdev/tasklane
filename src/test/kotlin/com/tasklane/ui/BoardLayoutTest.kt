package com.tasklane.ui

import com.tasklane.ui.board.StripLayout
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Dimension
import javax.swing.JPanel

/**
 * El reparto de ancho entre las columnas del tablero (2.17.0), sin arrancar un IDE.
 *
 * Lo que se fija es la regla de `ColumnStrip`: las columnas llenan el hueco a partes
 * iguales mientras quepan a su ancho mínimo —el de la tool window—, y si no caben, cada una
 * se queda en su mínimo y el tablero pide sitio para la barra horizontal. Una columna más
 * estrecha que eso es una tarjeta que deja de leerse.
 */
class BoardLayoutTest {

    private fun strip(columns: Int, minimum: Int = 300) = JPanel(StripLayout()).apply {
        repeat(columns) { add(JPanel().apply { minimumSize = Dimension(minimum, 10) }) }
    }

    private fun JPanel.widths(): List<Int> = components.map { it.width }

    @Test
    fun `con sitio, las columnas se reparten el ancho entero`() {
        val strip = strip(3)
        strip.setSize(1202, 500)
        strip.doLayout()

        assertEquals(listOf(400, 400, 400), strip.widths())
        assertEquals(listOf(0, 401, 802), strip.components.map { it.x })
        assertEquals(500, strip.getComponent(0).height)
    }

    @Test
    fun `lo que sobra de redondear se lo lleva la ultima`() {
        val strip = strip(3)
        strip.setSize(1204, 500)
        strip.doLayout()

        assertEquals(listOf(400, 400, 402), strip.widths())
    }

    @Test
    fun `sin sitio, cada columna se queda en su minimo y el tablero pide lo que falta`() {
        val strip = strip(3)
        strip.setSize(600, 500)
        strip.doLayout()

        assertEquals(listOf(300, 300, 300), strip.widths())
        assertEquals(902, strip.minimumSize.width)
        assertEquals(902, strip.preferredSize.width)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
