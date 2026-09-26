package com.tasklane.domain.model

import com.tasklane.domain.text.CodeFence

/**
 * El código de un ancla **tal como está hoy** en el fichero (2.15.0): lo que pinta la
 * tarjeta desplegada debajo de un bloque anclado y lo que enseña el tooltip de su ficha.
 *
 * Se lee del fichero y no se guarda con la tarea a propósito. Una copia guardada sería
 * el código del día en que se anotó, y la nota habla del código que hay que tocar ahora:
 * si alguien ya arregló la mitad, eso es justo lo que conviene ver al desplegarla.
 *
 * [lines] son las primeras del bloque, sin la sangría común —en una tarjeta estrecha
 * cada columna cuenta, y la sangría es la del sitio del fichero, no la del código— y
 * como mucho [MAX_LINES]; [total] es cuántas tiene el bloque entero, para poder decir
 * cuántas se quedan fuera.
 *
 * Kotlin puro sobre un accesor de líneas, como [AnchorResolver]: la plataforma sólo
 * pone el `Document`.
 */
data class AnchorSnippet(val lines: List<String>, val total: Int) {

    /** Las líneas del bloque que no están en [lines]. */
    val hidden: Int get() = total - lines.size

    companion object {
        /**
         * Tope de líneas que se leen de un bloque. Un ancla sobre una clase entera no se
         * lee desplegando la tarjeta: se abre. Y cada línea es un componente de la
         * tarjeta, así que el tope también es el de lo que cuesta pintarla.
         */
        const val MAX_LINES = 40

        /**
         * El fragmento de [anchor] en un fichero de [lineCount] líneas, o `null` si el
         * fichero está vacío. Un ancla de una línea da esa línea.
         */
        fun of(anchor: CodeAnchor, lineCount: Int, max: Int = MAX_LINES, lineAt: (Int) -> String): AnchorSnippet? {
            if (lineCount <= 0) return null
            val range = AnchorResolver.range(anchor, lineCount, lineAt)
            val last = minOf(range.last, range.first + max.coerceAtLeast(1) - 1)
            val lines = CodeFence.dedent((range.first..last).map(lineAt))
            return AnchorSnippet(lines, range.last - range.first + 1)
        }

        fun of(anchor: CodeAnchor, lines: List<String>, max: Int = MAX_LINES): AnchorSnippet? =
            of(anchor, lines.size, max) { lines[it] }
    }
}
