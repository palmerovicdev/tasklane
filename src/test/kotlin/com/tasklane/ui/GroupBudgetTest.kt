package com.tasklane.ui

import com.tasklane.domain.model.GroupKey
import com.tasklane.paging.GroupOutline
import com.tasklane.paging.TaskPager
import com.tasklane.ui.toolwindow.GroupBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El reparto del presupuesto de filas del primer pintado.
 *
 * Es la regla de la Fase 2 que el usuario ve: con listas normales no cambia nada
 * —todo cabe y todo se abre— y con listas grandes lo que hay debajo de la primera
 * pantalla empieza plegado. Los casos están escritos en función de las constantes y no
 * de sus valores: si alguien afina el presupuesto, lo que no puede cambiar es la
 * política.
 */
class GroupBudgetTest {

    private fun group(name: String, size: Int) = GroupOutline(GroupKey.OfTag(name), size)

    private fun open(
        outline: List<GroupOutline>,
        opened: Set<GroupKey> = emptySet(),
        collapsed: Set<GroupKey> = emptySet(),
        headers: Int = outline.size,
    ) = GroupBudget.open(outline, opened, collapsed, headers)

    private fun tag(name: String) = GroupKey.OfTag(name)

    @Test
    fun `una lista normal se abre entera`() {
        val outline = listOf(group("a", 3), group("b", 5), group("c", 2))

        assertEquals(outline.map { it.key }.toSet(), open(outline))
    }

    /**
     * Lo que esta fase vino a impedir: con muchos grupos llenos, abrirlos todos son
     * miles de filas que medir en el hilo de interfaz.
     */
    @Test
    fun `cuando se acaba el presupuesto los de abajo empiezan plegados`() {
        val outline = List(10) { group("g$it", TaskPager.PAGE) }

        val opened = open(outline)

        assertTrue("alguno se abre", opened.isNotEmpty())
        assertTrue("pero no todos", opened.size < outline.size)
        // Y se abren los de **arriba**, que es lo que se está mirando.
        assertEquals(outline.take(opened.size).map { it.key }.toSet(), opened)
    }

    @Test
    fun `un grupo enorme no se abre solo aunque sea el primero`() {
        val outline = listOf(group("enorme", GroupBudget.BIG_GROUP + 1), group("normal", 4))

        val opened = open(outline)

        assertEquals(setOf(tag("normal")), opened)
    }

    @Test
    fun `lo que el usuario abrio se queda abierto aunque no quepa`() {
        val outline = List(10) { group("g$it", TaskPager.PAGE) }
        val last = outline.last().key

        assertTrue(last in open(outline, opened = setOf(last)))
    }

    @Test
    fun `lo que el usuario cerro se queda cerrado aunque sobre sitio`() {
        val outline = listOf(group("a", 2), group("b", 2))

        assertEquals(setOf(tag("a")), open(outline, collapsed = setOf(tag("b"))))
    }

    @Test
    fun `abrir a mano un grupo enorme funciona`() {
        val outline = listOf(group("enorme", 800_000))

        assertEquals(setOf(tag("enorme")), open(outline, opened = setOf(tag("enorme"))))
    }

    /**
     * Las cabeceras también son filas. Con tantas que ya llenan el presupuesto, no
     * queda nada para abrir ninguna: lo que se ve al abrir es la lista de grupos.
     */
    @Test
    fun `las cabeceras gastan del mismo presupuesto`() {
        val outline = List(20) { group("g$it", 4) }

        assertTrue(open(outline, headers = GroupBudget.FIRST_PAINT_ROWS).isEmpty())
        assertTrue(open(outline, headers = 1).isNotEmpty())
    }

    /**
     * Un grupo por el que el usuario ya se desplazó tiene más filas cargadas, y
     * volver a ponerlas cuesta más: el presupuesto cuenta lo que hay, no una página.
     */
    @Test
    fun `un grupo con varias paginas cargadas gasta lo que ocupa`() {
        val outline = listOf(group("gordo", 10_000), group("siguiente", 4))

        val conUnaPagina = GroupBudget.open(outline, setOf(tag("gordo")), emptySet(), outline.size)
        val conMuchas = GroupBudget.open(outline, setOf(tag("gordo")), emptySet(), outline.size) {
            if (it == tag("gordo")) GroupBudget.FIRST_PAINT_ROWS else TaskPager.PAGE
        }

        assertTrue("con una página todavía cabe el siguiente", tag("siguiente") in conUnaPagina)
        assertEquals("con el grupo lleno ya no", setOf(tag("gordo")), conMuchas)
    }
}
