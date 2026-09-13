package com.tasklane.domain

import com.tasklane.domain.text.InlineMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownTest {

    /** Los tramos como `texto|estilos`, que es lo que se quiere leer en un fallo. */
    private fun render(text: String): String =
        InlineMarkdown.parse(text).joinToString("·") { span ->
            val flags = buildString {
                if (span.emphasis.bold) append('b')
                if (span.emphasis.italic) append('i')
                if (span.emphasis.code) append('c')
                if (span.emphasis.strike) append('s')
            }
            text.substring(span.range.first, span.range.last + 1) + if (flags.isEmpty()) "" else "|$flags"
        }

    @Test
    fun `un texto sin marcas sale de una pieza`() {
        assertEquals("Resolver el fallo", render("Resolver el fallo"))
        assertEquals("Resolver el fallo", InlineMarkdown.strip("Resolver el fallo"))
    }

    @Test
    fun `las marcas no se pintan`() {
        assertEquals("Bold text|b", render("**Bold text**"))
        assertEquals("Bold text", InlineMarkdown.strip("**Bold text**"))
    }

    @Test
    fun `negrita, cursiva, codigo y tachado`() {
        assertEquals("a|b", render("**a**"))
        assertEquals("a|i", render("*a*"))
        assertEquals("a|c", render("`a`"))
        assertEquals("a|s", render("~~a~~"))
    }

    @Test
    fun `el enfasis anida`() {
        assertEquals("a|b·b|bi·c|b", render("**a*b*c**"))
    }

    @Test
    fun `el codigo es literal por dentro`() {
        // Dentro de comillas invertidas los asteriscos son asteriscos.
        assertEquals("Revisar ·a **b**|c", render("Revisar `a **b**`"))
    }

    @Test
    fun `una marca suelta se queda como texto`() {
        assertEquals("2 * 3", render("2 * 3"))
        assertEquals("a ** b", render("a ** b"))
        assertEquals("Sin cerrar *esto", render("Sin cerrar *esto"))
        assertEquals("2 * 3", InlineMarkdown.strip("2 * 3"))
    }

    @Test
    fun `una marca con espacio detras no abre`() {
        // La regla que salva las multiplicaciones y las listas.
        assertEquals("2 * 3 * 4", render("2 * 3 * 4"))
    }

    @Test
    fun `los guiones bajos no son cursiva`() {
        // `un_nombre_asi` es un identificador mucho mas a menudo que una cursiva.
        assertEquals("un_nombre_asi", render("un_nombre_asi"))
    }

    @Test
    fun `la barra invertida protege la marca`() {
        // La barra parte el tramo en dos —lo de antes y lo de despues— porque un
        // span es siempre un trozo contiguo del original, y la barra no se pinta.
        assertEquals("2 ·* 3", render("2 \\* 3"))
        assertEquals("2 * 3", InlineMarkdown.strip("2 \\* 3"))
        assertEquals("*literal*", InlineMarkdown.strip("\\*literal\\*"))
    }

    @Test
    fun `los rangos apuntan al original`() {
        val text = "**Bold** normal"
        val spans = InlineMarkdown.parse(text)
        assertEquals(2 until 6, spans.first().range)
        assertTrue(spans.all { text.substring(it.range.first, it.range.last + 1).isNotEmpty() })
    }

    @Test
    fun `una linea vacia no tiene tramos`() {
        assertEquals(emptyList<InlineMarkdown.Span>(), InlineMarkdown.parse(""))
    }
}
