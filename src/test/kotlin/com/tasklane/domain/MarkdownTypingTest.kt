package com.tasklane.domain

import com.tasklane.domain.text.MarkdownTyping
import com.tasklane.domain.text.TypingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que hace el cuerpo de la tarea al pulsar `Enter` en una lista y al teclear una marca con
 * texto seleccionado. Los casos se escriben con `|` donde está el cursor y `‹…›` lo seleccionado,
 * que es como se lee de un vistazo el antes y el después.
 */
class MarkdownTypingTest {

    private fun apply(result: TypingResult, text: String): String {
        val out = StringBuilder(text)
        result.steps().forEach { out.replace(it.start, it.end, it.text) }
        val end = result.selectionEnd
        val start = result.selectionStart
        return if (start == end) out.insert(start, '|').toString() else out.insert(end, '›').insert(start, '‹').toString()
    }

    private fun parts(scene: String): Triple<String, Int, Int> {
        val caret = scene.indexOf('|')
        if (caret >= 0) return Triple(scene.removeRange(caret, caret + 1), caret, caret)
        val open = scene.indexOf('‹')
        val close = scene.indexOf('›') - 1
        return Triple(scene.replace("‹", "").replace("›", ""), open, close)
    }

    private fun enter(scene: String): String? {
        val (text, start, end) = parts(scene)
        return MarkdownTyping.enter(text, start, end)?.let { apply(it, text) }
    }

    private fun typed(scene: String, char: Char): String? {
        val (text, start, end) = parts(scene)
        return MarkdownTyping.typed(text, start, end, char)?.let { apply(it, text) }
    }

    /** Teclear [char] [times] veces seguidas, con la selección que deja cada pulsación. */
    private fun typedTimes(scene: String, char: Char, times: Int): String =
        (1..times).fold(scene) { current, _ -> typed(current, char) ?: current }

    // ---------------------------------------------------------------- numeradas

    @Test
    fun `una lista numerada sigue con el numero siguiente`() {
        assertEquals("1. uno\n2. |", enter("1. uno|"))
        assertEquals("1. uno\n2. dos\n3. |", enter("1. uno\n2. dos|"))
    }

    @Test
    fun `el numero sigue al actual aunque cambie de cifras`() {
        assertEquals("9. nueve\n10. |", enter("9. nueve|"))
        assertEquals("99. a\n100. |", enter("99. a|"))
    }

    @Test
    fun `se respeta el delimitador la sangria y el hueco de la marca`() {
        assertEquals("1) uno\n2) |", enter("1) uno|"))
        assertEquals("   4. x\n   5. |", enter("   4. x|"))
        assertEquals("1.   x\n2.   |", enter("1.   x|"))
        assertEquals("\t1. x\n\t2. |", enter("\t1. x|"))
        assertEquals("007. x\n008. |", enter("007. x|"))
    }

    @Test
    fun `insertar en medio renumera lo que viene detras`() {
        assertEquals("1. a\n2. |\n3. b\n4. c", enter("1. a|\n2. b\n3. c"))
    }

    @Test
    fun `renumerar se para donde ya no hay conflicto`() {
        assertEquals("1. a\n2. |\n3. b\n7. c\n8. d", enter("1. a|\n2. b\n7. c\n8. d"))
        assertEquals("1. a\n2. |\n5. b", enter("1. a|\n5. b"))
    }

    @Test
    fun `renumerar no toca otra lista hijos ni bloques de codigo`() {
        assertEquals(
            "1. a\n2. |\n   1. hijo\n   2. hijo\n3. b",
            enter("1. a|\n   1. hijo\n   2. hijo\n2. b"),
        )
        assertEquals("1. a\n2. |\n2) b", enter("1. a|\n2) b"))
        assertEquals("1. a\n2. |\nparrafo\n2. b", enter("1. a|\nparrafo\n2. b"))
        assertEquals("1. a\n2. |\n```\n2. no\n```\n2. b", enter("1. a|\n```\n2. no\n```\n2. b"))
    }

    @Test
    fun `renumerar cruza las lineas en blanco de una lista separada`() {
        assertEquals("1. a\n2. |\n\n3. b", enter("1. a|\n\n2. b"))
    }

    @Test
    fun `un numero de mas de nueve cifras no es una lista`() {
        assertNull(enter("1234567890. x|"))
    }

    @Test
    fun `si el siguiente numero ya no seria una lista se deja el salto normal`() {
        assertNull(enter("999999999. x|"))
    }

    // ---------------------------------------------------------------- viñetas y casillas

    @Test
    fun `una lista de puntos repite su marcador`() {
        assertEquals("- a\n- |", enter("- a|"))
        assertEquals("* a\n* |", enter("* a|"))
        assertEquals("+ a\n+ |", enter("+ a|"))
        assertEquals("  - a\n  - |", enter("  - a|"))
    }

    @Test
    fun `una casilla sigue con otra sin marcar`() {
        assertEquals("- [ ] a\n- [ ] |", enter("- [ ] a|"))
        assertEquals("- [x] a\n- [ ] |", enter("- [x] a|"))
        assertEquals("- [X] a\n- [ ] |", enter("- [X] a|"))
        assertEquals("* [ ] a\n* [ ] |", enter("* [ ] a|"))
        assertEquals("  - [ ] a\n  - [ ] |", enter("  - [ ] a|"))
    }

    @Test
    fun `una casilla numerada sigue numerada y sin marcar`() {
        assertEquals("1. [x] a\n2. [ ] |", enter("1. [x] a|"))
    }

    @Test
    fun `unos corchetes pegados al texto no son una casilla`() {
        assertEquals("- [ ]algo\n- |", enter("- [ ]algo|"))
        assertEquals("- [nota] algo\n- |", enter("- [nota] algo|"))
    }

    // ---------------------------------------------------------------- lo que no es lista

    @Test
    fun `lo que no es una lista deja el salto normal`() {
        assertNull(enter("texto|"))
        assertNull(enter("|"))
        assertNull(enter("-|"))
        assertNull(enter("-algo|"))
        assertNull(enter("1.algo|"))
        assertNull(enter("1|"))
        assertNull(enter("**negrita** y mas|"))
        assertNull(enter("texto - con guion|"))
    }

    @Test
    fun `una linea horizontal no es una lista`() {
        assertNull(enter("---|"))
        assertNull(enter("- - -|"))
        assertNull(enter("* * *|"))
    }

    @Test
    fun `dentro de un bloque de codigo no se continua nada`() {
        assertNull(enter("```\n- a|\n```"))
        assertNull(enter("```\n1. a|"))
        assertNull(enter("```|"))
        assertEquals("```\nx\n```\n- a\n- |", enter("```\nx\n```\n- a|"))
    }

    // ---------------------------------------------------------------- dónde está el cursor

    @Test
    fun `en medio del texto el resto pasa a la linea nueva`() {
        assertEquals("1. un\n2. |o", enter("1. un|o"))
        assertEquals("- [x] un\n- [ ] |o", enter("- [x] un|o"))
        assertEquals("- uno\n- |dos", enter("- uno| dos"))
    }

    @Test
    fun `con el cursor en la marca o antes el salto es normal`() {
        assertNull(enter("|- a"))
        assertNull(enter("-| a"))
        assertNull(enter("  |- a"))
        assertNull(enter(" | - a"))
        assertNull(enter("1|. a"))
        assertNull(enter("- [ |] a"))
    }

    @Test
    fun `justo delante del texto la marca se queda y el texto baja`() {
        assertEquals("- \n- |a", enter("- |a"))
    }

    @Test
    fun `una seleccion se sustituye y el resto de la linea baja`() {
        assertEquals("1. u\n2. |o", enter("1. u‹n›o"))
        assertEquals("- a\n- |c", enter("- a‹b\nmas\nfin›c"))
    }

    // ---------------------------------------------------------------- acabar la lista

    @Test
    fun `una marca vacia acaba la lista`() {
        assertEquals("- a\n|", enter("- a\n- |"))
        assertEquals("1. a\n|", enter("1. a\n2. |"))
        assertEquals("- [ ] a\n|", enter("- [ ] a\n- [ ] |"))
        assertEquals("- [x] a\n|", enter("- [x] a\n- [x] |"))
        assertEquals("|", enter("- |"))
    }

    @Test
    fun `una marca vacia sin espacio final tambien cuenta si es una casilla`() {
        assertEquals("|", enter("- [ ]|"))
    }

    @Test
    fun `acabar la lista no toca lo que viene detras`() {
        assertEquals("1. a\n|\n3. b", enter("1. a\n2. |\n3. b"))
    }

    @Test
    fun `una marca vacia anidada sube un nivel`() {
        assertEquals("- a\n- |", enter("- a\n  - |"))
        assertEquals("1. a\n   - b\n2. |", enter("1. a\n   - b\n   - |"))
    }

    @Test
    fun `subir de nivel renumera el nivel al que se sube`() {
        assertEquals("1. a\n   - b\n2. |\n3. c", enter("1. a\n   - b\n   - |\n2. c"))
    }

    @Test
    fun `una marca vacia sangrada sin padre se borra`() {
        assertEquals("|", enter("  - |"))
        assertEquals("texto\n|", enter("texto\n  - |"))
    }

    @Test
    fun `el padre no se busca dentro de un bloque de codigo`() {
        assertEquals("```\n- no\n```\n|", enter("```\n- no\n```\n  - |"))
    }

    // ---------------------------------------------------------------- rodear: código

    @Test
    fun `una comilla rodea la seleccion y la deja seleccionada`() {
        assertEquals("`‹hola›`", typed("‹hola›", '`'))
        assertEquals("di `‹hola›` ya", typed("di ‹hola› ya", '`'))
    }

    @Test
    fun `sin seleccion la tecla escribe lo suyo`() {
        assertNull(typed("hola|", '`'))
        assertNull(typed("hola|", '*'))
    }

    @Test
    fun `otras teclas sustituyen la seleccion como siempre`() {
        assertNull(typed("‹hola›", 'x'))
        assertNull(typed("‹hola›", '_'))
    }

    @Test
    fun `tres comillas hacen un bloque con saltos de linea`() {
        assertEquals("```\n‹hola›\n```", typedTimes("‹hola›", '`', 3))
    }

    @Test
    fun `un bloque rodeado de texto se queda en sus propias lineas`() {
        assertEquals("di\n```\n‹hola›\n```\nya", typedTimes("di ‹hola› ya", '`', 3))
    }

    @Test
    fun `un bloque en una linea sangrada conserva la sangria`() {
        assertEquals("  ```\n  ‹hola›\n  ```", typedTimes("  ‹hola›", '`', 3))
    }

    @Test
    fun `varias lineas son un bloque desde la primera comilla y no cambian con las demas`() {
        assertEquals("```\n‹a\nb›\n```", typed("‹a\nb›", '`'))
        assertEquals("```\n‹a\nb›\n```", typedTimes("‹a\nb›", '`', 3))
    }

    @Test
    fun `una cuarta comilla no rompe el bloque`() {
        assertEquals("```\n‹hola›\n```", typedTimes("‹hola›", '`', 4))
    }

    // ---------------------------------------------------------------- rodear: asteriscos

    @Test
    fun `los asteriscos van de cursiva a negrita y a las dos`() {
        assertEquals("*‹hola›*", typedTimes("‹hola›", '*', 1))
        assertEquals("**‹hola›**", typedTimes("‹hola›", '*', 2))
        assertEquals("***‹hola›***", typedTimes("‹hola›", '*', 3))
    }

    @Test
    fun `un cuarto asterisco no anade nada`() {
        assertEquals("***‹hola›***", typedTimes("‹hola›", '*', 4))
    }

    // ---------------------------------------------------------------- rodear: la selección

    @Test
    fun `los espacios de los bordes de la seleccion se quedan fuera de las marcas`() {
        assertEquals("di **‹hola›** ya", typedTimes("di‹ hola ›ya", '*', 2))
        assertEquals("`‹hola›`\nfin", typed("‹hola\n›fin", '`'))
    }

    @Test
    fun `una seleccion de solo espacios se sustituye como siempre`() {
        assertNull(typed("a‹  ›b", '`'))
        assertNull(typed("a‹\n›b", '*'))
    }

    @Test
    fun `una linea entre dos bloques no esta dentro de un bloque`() {
        assertEquals("```\na\n```\n`‹foo›`\n```\nb\n```", typed("```\na\n```\n‹foo›\n```\nb\n```", '`'))
    }

    @Test
    fun `sustituir todo el texto de un elemento no acaba la lista`() {
        assertEquals("1. \n2. |\n3. b", enter("1. ‹foo›\n2. b"))
    }

    /**
     * La captura de `#tag` y de los triggers sólo mira inserciones de un solo separador. Si el
     * salto entrara junto con la marca, `- revisar #api` dejaría de convertirse en etiqueta.
     */
    @Test
    fun `el salto de linea de una lista entra solo`() {
        val (text, start, end) = parts("- revisar #api|")
        val steps = MarkdownTyping.enter(text, start, end)!!.steps()

        val newline = steps.single { it.text == "\n" }
        assertEquals("sin quitar nada, o no se contaria como tecleado", newline.start, newline.end)
        assertEquals("en el mismo sitio donde acaba la etiqueta", text.length, newline.start)
    }

    @Test
    fun `con texto a la derecha el salto tambien entra solo`() {
        val (text, start, end) = parts("- revisar #api| fin")
        val steps = MarkdownTyping.enter(text, start, end)!!.steps()

        assertEquals(1, steps.count { it.text == "\n" && it.start == it.end })
        assertEquals("- revisar #api\n- |fin", enter("- revisar #api| fin"))
    }

    @Test
    fun `los saltos de un bloque tecleado no cuentan como un enter`() {
        val (text, start, end) = parts("#api ‹hola›")
        val fenced = MarkdownTyping.typed(text, start, end, '`')!!.let { apply(it, text) }
        val (after, from, to) = parts(fenced)
        val second = MarkdownTyping.typed(after, from, to, '`')!!.let { apply(it, after) }
        val (last, a, b) = parts(second)
        assertTrue(MarkdownTyping.typed(last, a, b, '`')!!.steps().none { it.text == "\n" })
    }
}
