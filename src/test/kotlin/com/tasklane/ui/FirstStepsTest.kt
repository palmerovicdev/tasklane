package com.tasklane.ui

import com.intellij.util.ui.StatusText
import com.tasklane.TasklaneBundle
import com.tasklane.ui.toolwindow.FirstSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import javax.swing.JComponent

/**
 * La lista vacía de un repositorio nuevo (P35), sin IDE: una línea por paso, cada una
 * pulsable, y todas caben en la ventana a su ancho mínimo —la ventana se usa estrecha— aun
 * con los atajos largos de Windows y Linux, que son los que más ocupan.
 */
class FirstStepsTest {

    private fun steps(vararg shortcuts: String?) = listOf(
        "firststeps.new",
        "firststeps.quickAdd",
        "firststeps.fromHere",
        "firststeps.importTodos",
        "firststeps.agent",
    ).mapIndexed { i, key -> FirstSteps.Step(TasklaneBundle.message(key), shortcuts.getOrNull(i)) {} }

    private fun painted(steps: List<FirstSteps.Step>): StatusText {
        val owner = object : JComponent() {}.apply { setSize(MIN_WIDTH, 600) }
        val status = object : StatusText(owner) {
            override fun isStatusVisible() = true
        }
        FirstSteps.paint(status, TasklaneBundle.message("toolwindow.tree.empty"), steps)
        return status
    }

    @Test
    fun `un titulo y una linea por paso`() {
        val lines = painted(steps("Ctrl+N", "Ctrl+Alt+R", "Ctrl+Alt+Shift+R")).wrappedFragmentsIterable.toList()

        assertEquals(6, lines.size)
    }

    @Test
    fun `cada linea cabe a lo estrecho, con su atajo`() {
        val lines = painted(steps("Ctrl+N", "Ctrl+Alt+R", "Ctrl+Alt+Shift+R")).wrappedFragmentsIterable.toList()

        for (line in lines) {
            val width = line.preferredSize.width
            assertTrue("«${(line as? com.intellij.ui.SimpleColoredComponent)?.getCharSequence(false)}» mide $width", width <= MIN_WIDTH - MARGIN)
        }
    }

    @Test
    fun `volver a pintarla no acumula lineas`() {
        val status = painted(steps())
        FirstSteps.paint(status, "No tasks yet", steps())

        assertEquals(6, status.wrappedFragmentsIterable.toList().size)
    }

    companion object {
        /** El de `TasklanePanel.MIN_WIDTH`. */
        private const val MIN_WIDTH = 300

        /** El borde del panel y la barra de desplazamiento, que también se comen su trozo. */
        private const val MARGIN = 24

        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
