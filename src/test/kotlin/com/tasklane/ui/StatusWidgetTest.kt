package com.tasklane.ui

import com.tasklane.domain.model.StatusBarCounts
import com.tasklane.domain.model.StatusBarCounts.StateCount
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.ui.statusbar.TasklaneStatusWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * La etiqueta del widget de la barra de estado (2.14.0), sin arrancar un IDE: qué dice,
 * qué se pulsa en cada trozo, y que ponerle el nombre accesible no revienta, que es lo que
 * le pasó a la fila de estados con el mismo componente.
 */
class StatusWidgetTest {

    private val todo = TasklaneConfig.DEFAULT.states[0]
    private val doing = TasklaneConfig.DEFAULT.states[1]

    private fun TasklaneStatusWidget.CountsLabel.text() = getCharSequence(false).toString()

    @Test
    fun `cada estado con su cuenta, y lo vencido al final`() {
        val label = TasklaneStatusWidget.CountsLabel()
        label.show(StatusBarCounts("backend", listOf(StateCount(todo, 3), StateCount(doing, 0)), overdue = 2))

        assertEquals("${todo.name} 3 · ${doing.name} 0 · 2 overdue", label.text())
        assertEquals(todo.id, label.getFragmentTag(0))
        assertEquals(doing.id, label.getFragmentTag(2))
        assertSame(TasklaneStatusWidget.Overdue, label.getFragmentTag(4))
        assertTrue(label.getAccessibleContext().accessibleName.contains("2 overdue"))
    }

    @Test
    fun `sin nada vencido no se dice`() {
        val label = TasklaneStatusWidget.CountsLabel()
        label.show(StatusBarCounts("backend", listOf(StateCount(todo, 3)), overdue = 0))
        assertEquals("${todo.name} 3", label.text())

        label.show(StatusBarCounts("backend", listOf(StateCount(todo, 3)), overdue = null))
        assertEquals("${todo.name} 3", label.text())
    }

    @Test
    fun `solo lo vencido, sin separador delante`() {
        val label = TasklaneStatusWidget.CountsLabel()
        label.show(StatusBarCounts("backend", emptyList(), overdue = 1))
        assertEquals("1 overdue", label.text())
    }

    @Test
    fun `antes de saber nada se queda en el icono`() {
        val label = TasklaneStatusWidget.CountsLabel()
        label.show(null)
        assertEquals("", label.text())
        assertTrue(label.toolTipText.isNotEmpty())
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
