package com.tasklane.domain.text

/**
 * El énfasis en línea de una línea de cuerpo: negrita, cursiva, código y tachado.
 *
 * Existe porque la fila **enseña** el cuerpo y hasta la `0.7.0` lo enseñaba en
 * crudo: una tarea escrita con la barra de formato del diálogo salía en la lista
 * como `**Resolver el fallo**`, con las marcas a la vista. Quien pulsa el botón de
 * negrita espera negrita, no dos asteriscos.
 *
 * **Devuelve rangos, no texto.** Cada [Span] apunta al original, así que quien
 * llama puede cruzarlos con lo que ya sabía de esa línea —los enlaces del título
 * llevan su rango— sin recalcular ningún desplazamiento. Las marcas simplemente no
 * aparecen en ningún span: pintar la lista de spans es pintar la línea sin ellas.
 *
 * El subconjunto es exactamente el que escribe [com.tasklane.ui.editor.MarkdownToolbar]
 * —`**`, `*`, `` ` `` y, de propina, `~~`—, que es lo que el plugin promete entender.
 * Los guiones bajos quedan fuera a propósito: `un_nombre_asi` es identificador mucho
 * más a menudo de lo que es cursiva, y una tarea es el sitio donde se pegan
 * identificadores.
 *
 * Dos reglas de CommonMark, las dos para no encontrar énfasis donde no lo hay:
 *  - una marca sólo **abre** si no lleva un espacio detrás, y sólo **cierra** si no
 *    lo lleva delante: `2 * 3 * 4` es una multiplicación, no una cursiva;
 *  - una marca sin pareja se queda como texto.
 *
 * Kotlin puro: se prueba sin arrancar un IDE, igual que [TriggerParser].
 */
object InlineMarkdown {

    /** El énfasis que le toca a un tramo. */
    data class Emphasis(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
    ) {
        companion object {
            val NONE = Emphasis()
        }
    }

    /** Un tramo visible del original: su rango y con qué se pinta. */
    data class Span(val range: IntRange, val emphasis: Emphasis)

    /**
     * Los tramos visibles de [text], en orden y sin las marcas. Un texto sin
     * ninguna marca sale como un único span con todo dentro.
     */
    fun parse(text: String): List<Span> {
        if (text.isEmpty()) return emptyList()

        val spans = mutableListOf<Span>()
        var emphasis = Emphasis.NONE
        var start = 0
        var index = 0

        fun flush(end: Int) {
            if (end > start) spans += Span(start until end, emphasis)
        }

        while (index < text.length) {
            val char = text[index]

            // La barra invertida protege la marca siguiente y no se pinta.
            if (char == ESCAPE && text.getOrNull(index + 1)?.let { it in ESCAPABLE } == true) {
                flush(index)
                index += 1
                start = index
                index += 1
                continue
            }

            // El código no anida: lo de dentro es literal, marcas incluidas.
            if (char == CODE) {
                val end = text.indexOf(CODE, index + 1)
                if (end > index + 1) {
                    flush(index)
                    spans += Span(index + 1 until end, emphasis.copy(code = true))
                    index = end + 1
                    start = index
                    continue
                }
                index++
                continue
            }

            val marker = markerAt(text, index)
            if (marker != null) {
                val open = emphasis.has(marker)
                val matched = if (open) closes(text, index) else pairedFrom(text, marker, index)
                if (matched) {
                    flush(index)
                    emphasis = emphasis.with(marker, !open)
                    index += marker.length
                    start = index
                    continue
                }
            }
            index++
        }
        flush(text.length)
        return spans
    }

    /** [text] sin las marcas, para donde no hay con qué pintar el énfasis. */
    fun strip(text: String): String {
        val spans = parse(text)
        if (spans.size == 1 && spans.first().range == text.indices) return text
        return buildString { spans.forEach { append(text, it.range.first, it.range.last + 1) } }
    }

    // ----------------------------------------------------------------- marcas

    private fun markerAt(text: String, index: Int): String? = when {
        text.startsWith(BOLD, index) -> BOLD
        text.startsWith(STRIKE, index) -> STRIKE
        text[index] == '*' -> ITALIC
        else -> null
    }

    /** ¿Hay más adelante una marca igual que pueda cerrar la que empieza en [index]? */
    private fun pairedFrom(text: String, marker: String, index: Int): Boolean {
        if (!opens(text, marker, index)) return false
        var cursor = index + marker.length
        while (cursor < text.length) {
            val found = text.indexOf(marker, cursor)
            if (found < 0) return false
            // Un `*` suelto no puede ser la mitad de un `**`.
            if (marker == ITALIC && text.startsWith(BOLD, found)) {
                cursor = found + BOLD.length
                continue
            }
            if (found > index + marker.length && closes(text, found)) return true
            cursor = found + marker.length
        }
        return false
    }

    /** Abre la que no lleva espacio detrás; cierra la que no lo lleva delante. */
    private fun opens(text: String, marker: String, index: Int): Boolean =
        text.getOrNull(index + marker.length)?.isWhitespace() == false

    private fun closes(text: String, index: Int): Boolean =
        text.getOrNull(index - 1)?.isWhitespace() == false

    private fun Emphasis.has(marker: String): Boolean = when (marker) {
        BOLD -> bold
        STRIKE -> strike
        else -> italic
    }

    private fun Emphasis.with(marker: String, on: Boolean): Emphasis = when (marker) {
        BOLD -> copy(bold = on)
        STRIKE -> copy(strike = on)
        else -> copy(italic = on)
    }

    private const val BOLD = "**"
    private const val ITALIC = "*"
    private const val STRIKE = "~~"
    private const val CODE = '`'
    private const val ESCAPE = '\\'
    private val ESCAPABLE = setOf('*', '~', '`', '\\', '[', ']', '_', '#')
}
