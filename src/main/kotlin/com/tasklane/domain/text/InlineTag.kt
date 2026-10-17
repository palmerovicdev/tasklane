package com.tasklane.domain.text

/**
 * Una etiqueta escrita **en el cuerpo** del diálogo de la tarea (2.29.0): `Revisar el login
 * #api ` pasa `api` a las etiquetas y la saca del texto, sin ir al campo de fichas.
 *
 * La UI pregunta cuando se teclea un separador —espacio, tabulador, salto de línea o coma,
 * los de [TagParser]— por la palabra que ese separador acaba de cerrar, y al aceptar por
 * la que se quedó al final sin cerrar. Aquí sólo se decide si esa palabra es una etiqueta.
 *
 * Las reglas existen para no comerse texto que no lo es, y cada una por un caso real del
 * cuerpo de una tarea:
 *
 * - **La almohadilla empieza la palabra.** `C#`, `pagina#seccion` o una URL con fragmento
 *   no son etiquetas.
 * - **Lleva al menos una letra.** `#123` es una incidencia de GitHub, no una etiqueta.
 * - **Sólo letras, cifras, `-`, `_` y `/`.** `# Título` (Markdown), `##api` o `#api.` al
 *   final de una frase se quedan como están: la regla es «`#palabra` y un separador», y lo
 *   que no la cumple a la primera no se adivina.
 * - **Nada dentro de código**, ni en línea ni en un bloque con valla: `` `#include` `` es
 *   código que se está citando.
 *
 * Kotlin puro, como [TriggerParser]: se testea sin IDE.
 */
object InlineTag {

    /** La etiqueta [tag], escrita en `[start, end)` del texto con su almohadilla. */
    data class Found(val start: Int, val end: Int, val tag: String)

    /**
     * La etiqueta que acaba justo antes de [end] en [text], o `null` si la palabra que acaba
     * ahí no lo es. [end] es donde se acaba de teclear el separador.
     */
    fun endingAt(text: String, end: Int): Found? {
        if (end <= 0 || end > text.length) return null
        var start = end
        while (start > 0 && !text[start - 1].isWhitespace() && !TagParser.isSeparator(text[start - 1])) start--
        if (start == end || text[start] != HASH) return null
        // `a,#api` no: la almohadilla tiene que abrir la palabra, no seguir a una coma pegada.
        if (start > 0 && !text[start - 1].isWhitespace()) return null
        val word = text.substring(start + 1, end)
        if (!isTag(word) || inCode(text, start)) return null
        return Found(start, end, word)
    }

    /**
     * La que se quedó escrita al final, sin separador detrás: lo que se acepta con el diálogo
     * no puede perder la etiqueta que se está viendo, igual que en el campo de fichas.
     */
    fun trailing(text: String): Found? = endingAt(text, text.length)

    /**
     * Lo que se borra del texto al pasar [found] a las etiquetas, como `[first, last)`.
     *
     * El espacio, tabulador o coma que la cerró se va con ella: `Revisar #api login` queda
     * `Revisar login`, sin un hueco doble. Un salto de línea se queda —es estructura del
     * cuerpo—, y entonces lo que sobra es el espacio que la separaba de lo de delante; igual
     * al final del texto.
     */
    fun cut(text: String, found: Found): Pair<Int, Int> {
        val next = text.getOrNull(found.end)
        if (next != null && next != '\n') return found.start to found.end + 1
        var from = found.start
        while (from > 0 && (text[from - 1] == ' ' || text[from - 1] == '\t')) from--
        return from to found.end
    }

    /** [text] sin [found]: ver [cut]. */
    fun remove(text: String, found: Found): String {
        val (first, last) = cut(text, found)
        return text.removeRange(first, last)
    }

    private fun isTag(word: String): Boolean =
        word.isNotEmpty() && word.any(Char::isLetter) && word.all { it.isLetterOrDigit() || it in EXTRA }

    /** Si [offset] cae dentro de código en línea —tildes impares antes en la línea— o de un bloque. */
    private fun inCode(text: String, offset: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        if (text.substring(lineStart, offset).count { it == TICK } % 2 == 1) return true
        if (lineStart == 0) return false
        val fence = CodeFence()
        text.substring(0, lineStart - 1).split('\n').forEach(fence::next)
        return fence.inBlock
    }

    private const val HASH = '#'
    private const val TICK = '`'
    private const val EXTRA = "-_/"
}
