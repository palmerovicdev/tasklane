package com.tasklane.domain.text

/**
 * Lo que comparten las dos cosas que se escriben como una palabra suelta en el cuerpo del
 * diálogo y salen del texto al cerrarla: las etiquetas (`#api`, [InlineTag]) y los triggers
 * (`!!!`, `@doing`, [TriggerParser]). Cómo se corta la palabra sin dejar un hueco doble, y
 * cuándo una palabra no cuenta porque es código.
 *
 * Kotlin puro: se testea sin IDE.
 */
object TypedWord {

    /**
     * Lo que se borra del texto al sacar la palabra `[start, end)`, como `[first, last)`.
     *
     * El espacio, tabulador o coma que la cerró se va con ella: `Revisar #api login` queda
     * `Revisar login`, sin un hueco doble. Un salto de línea se queda —es estructura del
     * cuerpo—, y entonces lo que sobra es el espacio que la separaba de lo de delante; igual
     * al final del texto.
     */
    fun cut(text: String, start: Int, end: Int): Pair<Int, Int> {
        val next = text.getOrNull(end)
        if (next != null && next != '\n') return start to end + 1
        var from = start
        while (from > 0 && (text[from - 1] == ' ' || text[from - 1] == '\t')) from--
        return from to end
    }

    /** Si [offset] cae dentro de código en línea —tildes impares antes en la línea— o de un bloque. */
    fun inCode(text: String, offset: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        if (text.substring(lineStart, offset).count { it == TICK } % 2 == 1) return true
        if (lineStart == 0) return false
        val fence = CodeFence()
        text.substring(0, lineStart - 1).split('\n').forEach(fence::next)
        return fence.inBlock
    }

    private const val TICK = '`'
}
