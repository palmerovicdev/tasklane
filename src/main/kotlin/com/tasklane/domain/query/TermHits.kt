package com.tasklane.domain.query

import com.tasklane.domain.text.TextNormalizer

/**
 * Dónde casan los términos de una búsqueda dentro de un texto (2.20.0): lo que la tarjeta
 * resalta, y lo que decide qué línea del cuerpo enseña plegada.
 *
 * **Con las reglas del índice**, no con las del matcher de la plataforma que resaltaba
 * hasta la 2.17. Aquel casaba el texto libre entero como un patrón difuso —`*token
 * expira`, en ese orden y en el mismo tramo— y distinguía los acentos, así que buscar
 * `autenticacion` encontraba `autenticación` y no la resaltaba, y dos términos en orden
 * distinto no resaltaban ninguno. Aquí cada término se busca por su cuenta y como lo
 * busca FTS5 (`unicode61 remove_diacritics 2`, ver `Fts5Index`):
 *
 * - Sin mayúsculas ni diacríticos, con el mismo [TextNormalizer] que la consulta.
 * - Por **palabras y prefijo de palabra**: `log` casa en `login` y no en `catalog`.
 * - Una palabra es una tira de letras y números; todo lo demás separa. Un término con
 *   separadores dentro —`auth.kt`, `"in review"`— es una frase: sus palabras seguidas,
 *   la última como prefijo.
 *
 * Los rangos vuelven en coordenadas del texto **original**, que es lo que se pinta: una
 * letra con su acento compuesto aparte (`o` + U+0301) sigue siendo una letra, y el acento
 * cae dentro del rango de su letra.
 */
object TermHits {

    /** Los tramos de [text] que casan con algún término, ordenados y sin solaparse. */
    fun find(text: String, terms: List<String>): List<IntRange> {
        if (text.isEmpty() || terms.isEmpty()) return emptyList()
        val folded = Folded.of(text)
        val words = words(folded.text)
        val found = ArrayList<IntRange>()
        for (term in terms.distinct()) {
            val wanted = wordsOf(term)
            if (wanted.isEmpty()) continue
            for (start in words.indices) {
                val end = matchAt(folded.text, words, start, wanted) ?: continue
                found += folded.from[words[start].first] until folded.to[end]
            }
        }
        return merge(found)
    }

    /** Cuántos términos distintos casan en [text]: lo que decide qué línea enseña la tarjeta. */
    fun count(text: String, terms: List<String>): Int {
        if (text.isEmpty() || terms.isEmpty()) return 0
        val folded = Folded.of(text)
        val words = words(folded.text)
        return terms.distinct().count { term ->
            val wanted = wordsOf(term)
            wanted.isNotEmpty() && words.indices.any { matchAt(folded.text, words, it, wanted) != null }
        }
    }

    /**
     * Si las palabras de [text] desde la [start]-ésima son [wanted] —todas enteras menos
     * la última, que basta con que empiece igual—, el último carácter plegado de la
     * coincidencia; si no, `null`.
     */
    private fun matchAt(text: String, words: List<IntRange>, start: Int, wanted: List<String>): Int? {
        if (start + wanted.size > words.size) return null
        for ((index, word) in wanted.withIndex()) {
            val range = words[start + index]
            val last = index == wanted.size - 1
            val length = range.last - range.first + 1
            if (if (last) length < word.length else length != word.length) return null
            if (!text.regionMatches(range.first, word, 0, word.length)) return null
            if (last) return range.first + word.length - 1
        }
        return null
    }

    /** Las palabras de un término, plegado otra vez por si no viene de `QueryParser`. */
    private fun wordsOf(term: String): List<String> {
        val folded = TextNormalizer.normalize(term)
        return words(folded).map { folded.substring(it.first, it.last + 1) }
    }

    /** Las palabras de [text]: tiras de letras y números, como los *tokens* de `unicode61`. */
    private fun words(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            val next = index + Character.charCount(point)
            if (isWordChar(point)) {
                if (start < 0) start = index
            } else if (start >= 0) {
                out += start until index
                start = -1
            }
            index = next
        }
        if (start >= 0) out += start until text.length
        return out
    }

    private fun isWordChar(point: Int): Boolean =
        Character.isLetterOrDigit(point) || when (Character.getType(point).toByte()) {
            Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.PRIVATE_USE -> true
            else -> false
        }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>(sorted.size)
        var current = sorted.first()
        for (range in sorted.drop(1)) {
            current = if (range.first <= current.last + 1) {
                current.first..maxOf(current.last, range.last)
            } else {
                out += current
                range
            }
        }
        out += current
        return out
    }

    /**
     * [text] plegado como lo pliega [TextNormalizer], con de qué tramo del original sale
     * cada carácter: [from] es dónde empieza y [to] dónde acaba, sin incluir.
     *
     * Se pliega **letra a letra**, y no la cadena entera, para no perder la cuenta: plegar
     * puede quitar caracteres —el acento suelto de una letra ya descompuesta— y también
     * añadirlos —una sílaba hangul son dos o tres jamos—. Pasar cada una por
     * [TextNormalizer.normalize] es la garantía de que se pliega igual que la consulta.
     */
    private class Folded(val text: String, val from: IntArray, val to: IntArray) {
        companion object {
            fun of(text: String): Folded {
                if (text.all { it.code < 0x80 }) {
                    return Folded(text.lowercase(), IntArray(text.length) { it }, IntArray(text.length) { it + 1 })
                }
                val out = StringBuilder(text.length)
                var from = IntArray(text.length)
                var to = IntArray(text.length)
                var index = 0
                while (index < text.length) {
                    val point = text.codePointAt(index)
                    val next = index + Character.charCount(point)
                    val piece = TextNormalizer.normalize(text.substring(index, next))
                    if (piece.isEmpty()) {
                        // Un acento suelto: es de la letra de antes, y va con ella.
                        if (out.isNotEmpty()) to[out.length - 1] = next
                    } else {
                        if (out.length + piece.length > from.size) {
                            val size = maxOf(from.size * 2, out.length + piece.length)
                            from = from.copyOf(size)
                            to = to.copyOf(size)
                        }
                        for (char in piece) {
                            from[out.length] = index
                            to[out.length] = next
                            out.append(char)
                        }
                    }
                    index = next
                }
                return Folded(out.toString(), from, to)
            }
        }
    }
}
