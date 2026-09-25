package com.tasklane.domain

import com.tasklane.domain.text.CodeFence
import com.tasklane.domain.text.CodeFence.Role.CODE
import com.tasklane.domain.text.CodeFence.Role.FENCE
import com.tasklane.domain.text.CodeFence.Role.PROSE
import org.junit.Assert.assertEquals
import org.junit.Test

/** Qué líneas de un cuerpo son código entre vallas, con las reglas de CommonMark que importan. */
class CodeFenceTest {

    @Test
    fun `lo de entre vallas es codigo y las vallas no se pintan`() {
        assertEquals(
            listOf(PROSE, FENCE, CODE, CODE, FENCE, PROSE),
            CodeFence.roles(listOf("Mira esto", "```kotlin", "val a = 1", "val *b = 2", "```", "y ya")),
        )
    }

    @Test
    fun `el lenguaje es lo que sigue a la valla`() {
        val fence = CodeFence()
        fence.next("  ```  kotlin ")
        assertEquals("kotlin", fence.language)
    }

    @Test
    fun `una valla sin cerrar llega hasta el final`() {
        assertEquals(listOf(FENCE, CODE, CODE), CodeFence.roles(listOf("```", "uno", "dos")))
    }

    @Test
    fun `tres comillas con otra detras son codigo en linea, no una valla`() {
        assertEquals(listOf(PROSE, PROSE), CodeFence.roles(listOf("```x```", "texto")))
    }

    @Test
    fun `cierra sólo una valla sola y al menos igual de larga`() {
        assertEquals(
            listOf(FENCE, CODE, CODE, FENCE),
            CodeFence.roles(listOf("````", "```", "``` no cierra", "````")),
        )
    }

    @Test
    fun `quita la sangria comun y hace espacios los tabuladores`() {
        assertEquals(
            listOf("if (a) {", "    b()", "", "}"),
            CodeFence.dedent(listOf("  if (a) {", "  \tb()", "   ", "  }")),
        )
    }
}
