package com.tasklane.ui

import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.ui.toolwindow.StateTabRow
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/**
 * La fila de estados, sin arrancar un IDE.
 *
 * Lo que se comprueba es que **pintar la fila no revienta**. Suena a poco y no lo es:
 * `TasklanePanel.render` actualiza la fila *antes* de reconstruir el árbol, así que
 * cualquier excepción aquí deja la ventana con los recuentos puestos y la lista vacía
 * —«Nothing to show» sobre una pestaña que dice que hay tres—, que es justo lo que
 * pasó al añadir el nombre accesible de la `1.0.0`.
 */
class StateTabRowTest {

    @Test
    fun `actualizar la fila no revienta y deja una pestana por estado`() {
        val row = StateTabRow { }
        val states = TasklaneConfig.DEFAULT.states
        val counts = states.associate { it.id to 3 }

        row.update(states, counts, states.first().id)

        assertEquals(states.size, row.componentCount)
        // Dos veces: la segunda pasa por el camino de «los estados no han cambiado».
        row.update(states, counts, states.last().id)
        assertEquals(states.size, row.componentCount)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
