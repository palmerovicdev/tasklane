package com.tasklane.ui

import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.tasklane.ui.settings.RowReorder
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import javax.swing.JTable

/**
 * Mover filas en las tablas de los ajustes. Arrastrar y las flechas acaban aquí, así que
 * es lo que decide qué orden queda y qué fila se queda seleccionada.
 */
class RowReorderTest {

    private val names = object : ColumnInfo<String, String>("Name") {
        override fun valueOf(item: String): String = item
    }

    private val model = ListTableModel(arrayOf<ColumnInfo<*, *>>(names), mutableListOf("a", "b", "c", "d"))
    private val table = JTable(model)
    private val reorder = RowReorder(table, model)

    @Test
    fun `arrastrar hacia abajo empuja las de en medio hacia arriba`() {
        reorder.move(0, 2)

        assertEquals(listOf("b", "c", "a", "d"), model.items)
        assertEquals("la fila arrastrada sigue seleccionada", 2, table.selectedRow)
    }

    @Test
    fun `arrastrar hacia arriba empuja las de en medio hacia abajo`() {
        reorder.move(3, 1)

        assertEquals(listOf("a", "d", "b", "c"), model.items)
        assertEquals(1, table.selectedRow)
    }

    @Test
    fun `las flechas mueven la seleccion una fila y paran en los bordes`() {
        table.selectionModel.setSelectionInterval(0, 0)

        reorder.moveSelection(-1)
        assertEquals(listOf("a", "b", "c", "d"), model.items)

        reorder.moveSelection(1)
        assertEquals(listOf("b", "a", "c", "d"), model.items)
        assertEquals(1, table.selectedRow)
    }

    /** Quien valida y marca los ajustes como cambiados escucha el modelo: tiene que enterarse. */
    @Test
    fun `mover avisa al modelo y fuera de rango no hace nada`() {
        var events = 0
        model.addTableModelListener { events++ }

        reorder.move(1, 9)
        reorder.move(2, 2)
        assertEquals(0, events)

        reorder.move(2, 0)
        assertEquals(listOf("c", "a", "b", "d"), model.items)
        assertEquals(2, events)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
