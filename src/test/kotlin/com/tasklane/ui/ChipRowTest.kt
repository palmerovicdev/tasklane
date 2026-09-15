package com.tasklane.ui

import com.tasklane.ui.toolwindow.ChipRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * El reparto de la línea de distintivos, con componentes de tamaño fijo: lo que se afirma
 * es qué se coloca y qué no, que no depende de ninguna fuente.
 */
class ChipRowTest {

    private fun box(width: Int): JComponent = object : JComponent() {}.apply {
        preferredSize = Dimension(width, 10)
    }

    private fun row(width: Int, vararg children: JComponent): JPanel =
        JPanel(ChipRow(gap = 0)).apply {
            children.forEach(::add)
            setSize(width, 10)
            doLayout()
        }

    private val JComponent.placed: Boolean get() = width > 0

    @Test
    fun `lo que no cabe se queda fuera y el primero entra siempre`() {
        val first = box(80)
        val second = box(40)

        row(60, first, second)

        assertTrue("el primero entra aunque no quepa", first.placed)
        assertTrue("el segundo ya no", !second.placed)
    }

    /**
     * Lo que pidió la 2.3.0: un distintivo marcado no se cae. Si no cabe entero se coloca
     * su versión reducida, y su sitio se aparta antes de colocar lo que va delante.
     */
    @Test
    fun `un distintivo que no se cae se reduce en vez de desaparecer`() {
        val before = box(50)
        val anchor = box(120)
        val icon = box(16)
        ChipRow.keep(anchor, icon)

        row(70, before, anchor, icon)

        assertTrue("lo de delante cabe junto al icono", before.placed)
        assertTrue("el entero no cabe", !anchor.placed)
        assertTrue("el icono ocupa su sitio", icon.placed)
        assertEquals(50, icon.x)
    }

    @Test
    fun `lo de delante cede el sitio al que no se puede caer`() {
        val before = box(60)
        val anchor = box(120)
        val icon = box(16)
        ChipRow.keep(anchor, icon)

        row(70, before, anchor, icon)

        assertTrue("sin sitio para los dos, se va el que se puede caer", !before.placed)
        assertTrue(icon.placed)
    }

    @Test
    fun `con sitio se ve entero y la version reducida no`() {
        val anchor = box(120)
        val icon = box(16)
        ChipRow.keep(anchor, icon)

        row(300, box(50), anchor, icon)

        assertTrue(anchor.placed)
        assertTrue(!icon.placed)
    }

    /** La versión reducida no es un distintivo más: no suma a lo que pide la línea. */
    @Test
    fun `la version reducida no cuenta para la medida`() {
        val anchor = box(120)
        val icon = box(16)
        ChipRow.keep(anchor, icon)

        assertEquals(170, row(300, box(50), anchor, icon).preferredSize.width)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
