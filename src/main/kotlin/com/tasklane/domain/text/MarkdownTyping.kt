package com.tasklane.domain.text

/**
 * Lo que cambia en el texto al teclear algo con significado: cambios que no se pisan y la
 * selección con la que queda. Si [selectionStart] y [selectionEnd] coinciden, es el cursor.
 *
 * Son cambios sueltos y no el texto entero porque el documento del editor lleva colgando
 * los plegados y las vistas previas de las imágenes, que se perderían al sustituirlo todo.
 */
class TypingResult(
    val edits: List<Edit>,
    val selectionStart: Int,
    val selectionEnd: Int = selectionStart,
    /** Si el salto de línea de [edits] es un `Enter` de verdad. Ver [steps]. */
    private val typedNewline: Boolean = false,
) {
    class Edit(val start: Int, val end: Int, val text: String)

    /**
     * Los cambios en el orden en que se aplican: de atrás adelante, para que cada uno deje
     * intactas las posiciones de los que faltan.
     *
     * Un `Enter` entra como un salto de línea **solo** y la marca después. Quien captura `#tag` y
     * los triggers mira que se haya tecleado un único separador, y `\n- ` de golpe parecería un
     * pegado: la etiqueta de la línea que se acaba de cerrar se quedaría en el texto.
     */
    fun steps(): List<Edit> = edits.sortedByDescending { it.start }.flatMap { edit ->
        if (!typedNewline || !edit.text.startsWith('\n')) return@flatMap listOf(edit)
        listOfNotNull(
            Edit(edit.start, edit.end, "").takeIf { edit.end > edit.start },
            Edit(edit.start, edit.start, "\n"),
            Edit(edit.start + 1, edit.start + 1, edit.text.substring(1)).takeIf { edit.text.length > 1 },
        )
    }
}

/**
 * Las dos ayudas al escribir Markdown en el cuerpo de una tarea (2.32.0): `Enter` dentro de una
 * lista y una marca tecleada con texto seleccionado.
 *
 * Kotlin puro y sobre texto y posiciones: quien las engancha al editor —`BodyEditorHandlers`—
 * sólo aplica el resultado. `null` significa siempre «esto no es cosa mía»: la tecla hace lo de
 * siempre, y así un error aquí nunca se puede comer lo que el usuario está escribiendo.
 */
object MarkdownTyping {

    // ------------------------------------------------------------------- Enter

    /**
     * `Enter` con la selección `[start, end)`, o con el cursor si coinciden.
     *
     * - Dentro de una lista añade la marca siguiente: `1.` → `2.`, `- ` → `- `, y una casilla
     *   siempre sin marcar. Lo que hubiera a la derecha del cursor baja a la línea nueva.
     * - En una lista numerada, los números de detrás que ya no valdrían suben uno.
     * - Sobre una marca vacía acaba la lista: anidada sube un nivel, y si no, la marca se borra.
     * - Con el cursor en la marca o delante de ella, dentro de un bloque de código o en lo que
     *   no es una lista, `null`.
     */
    fun enter(text: String, start: Int, end: Int): TypingResult? {
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val before = text.substring(lineStart, start)
        val after = text.substring(end, lineEnd)
        val line = before + after

        val above = if (lineStart == 0) emptyList() else text.substring(0, lineStart - 1).split('\n')
        val fence = CodeFence()
        val roles = above.map(fence::next)
        if (fence.next(line) != CodeFence.Role.PROSE) return null

        val item = ListItem.parse(line) ?: return null
        if (before.length < item.content) return null

        // Con una selección no estaba vacía: lo que se hace es sustituir su texto por un salto.
        if (start == end && line.substring(item.content).isBlank()) {
            return endItem(text, lineStart, lineEnd, item, above, roles, fence)
        }
        val next = item.next() ?: return null
        val moved = after.length - after.trimStart(' ', '\t').length
        val inserted = "\n" + item.indent + next
        val edits = mutableListOf(TypingResult.Edit(start, end + moved, inserted))
        if (item.number != null) edits += renumber(text, lineEnd, fence, item, item.number + 2)
        return TypingResult(edits, start + inserted.length, typedNewline = true)
    }

    /** `Enter` sobre una marca sin texto: la lista se acaba aquí. Ver [enter]. */
    private fun endItem(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        item: ListItem,
        above: List<String>,
        roles: List<CodeFence.Role>,
        fence: CodeFence,
    ): TypingResult {
        val parent = if (item.indent.isEmpty()) null else parentOf(item, above, roles)
        val next = parent?.next()
        if (parent == null || next == null) {
            return TypingResult(listOf(TypingResult.Edit(lineStart, lineEnd, "")), lineStart)
        }
        val replacement = parent.indent + next
        val edits = mutableListOf(TypingResult.Edit(lineStart, lineEnd, replacement))
        if (parent.number != null) edits += renumber(text, lineEnd, fence, parent, parent.number + 2)
        return TypingResult(edits, lineStart + replacement.length)
    }

    /** La marca del nivel de arriba: la primera que se encuentra subiendo, menos sangrada que [item]. */
    private fun parentOf(item: ListItem, above: List<String>, roles: List<CodeFence.Role>): ListItem? {
        for (index in above.indices.reversed()) {
            val line = above[index]
            if (roles[index] == CodeFence.Role.CODE || line.isBlank()) continue
            if (widthOf(line) >= item.width) continue
            return if (roles[index] == CodeFence.Role.PROSE) ListItem.parse(line) else null
        }
        return null
    }

    /**
     * Los números que dejarían de valer detrás de [lineEnd], en el nivel de [item]: el primero
     * debería ser [first] y cada uno de los siguientes, uno más.
     *
     * Se para en el primero que ya es mayor —no hay conflicto, y una numeración con huecos es
     * cosa de quien la escribió— y en lo que ya no es esta lista: menos sangría, otro
     * delimitador, un párrafo. Los hijos, más sangrados, y las líneas en blanco de una lista
     * separada se saltan, y una línea de un bloque de código nunca es un número.
     */
    private fun renumber(
        text: String,
        lineEnd: Int,
        fence: CodeFence,
        item: ListItem,
        first: Long,
    ): List<TypingResult.Edit> {
        val out = mutableListOf<TypingResult.Edit>()
        var expected = first
        var at = lineEnd + 1
        while (at <= text.length && lineEnd < text.length) {
            val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            val line = text.substring(at, end)
            val role = fence.next(line)
            if (role != CodeFence.Role.CODE && line.isNotBlank()) {
                val width = widthOf(line)
                if (width < item.width) break
                if (width == item.width) {
                    val other = ListItem.parse(line) ?: break
                    val number = other.number ?: break
                    if (other.delimiter != item.delimiter || number >= expected) break
                    val digits = other.digits(expected) ?: break
                    out += TypingResult.Edit(at + other.indent.length, at + other.indent.length + other.digitCount, digits)
                    expected++
                }
            }
            at = end + 1
        }
        return out
    }

    /** La marca de una línea de lista: sangría, viñeta o número, y si es una casilla. */
    private class ListItem(
        val indent: String,
        private val bullet: String?,
        val number: Long?,
        val digitCount: Int,
        val delimiter: Char?,
        private val gap: String,
        private val paddedZeros: Boolean,
        /** Lo que va tras el corchete de una casilla en la marca nueva; `null` si no es una. */
        private val checkGap: String?,
        /** Dónde empieza el texto, en coordenadas de la línea. */
        val content: Int,
    ) {
        val width: Int get() = widthOf(indent)

        /** La marca que sigue a ésta, sin sangría, o `null` si el número ya no sería una lista. */
        fun next(): String? {
            val head = bullet ?: ((digits((number ?: return null) + 1) ?: return null) + delimiter)
            return head + gap + if (checkGap == null) "" else "[ ]$checkGap"
        }

        /** [value] escrito como este número: con los ceros de delante, si los llevaba. */
        fun digits(value: Long): String? {
            val digits = if (paddedZeros) value.toString().padStart(digitCount, '0') else value.toString()
            return digits.takeIf { it.length <= MAX_DIGITS }
        }

        companion object {
            private val MARKER = Regex("""^([ \t]*)(?:([-*+])|(\d{1,$MAX_DIGITS})([.)]))([ \t]+)""")

            /** La marca de [line], o `null` si no es una línea de lista. */
            fun parse(line: String): ListItem? {
                if (isRule(line)) return null
                val match = MARKER.find(line) ?: return null
                val (indent, bullet, digits, delimiter, gap) = match.destructured
                // Lo que es una casilla lo decide quien las pinta en la tarjeta: lo que aquí
                // siguiera como casilla tiene que salir allí como casilla.
                val check = Checklist.parse(line)
                return ListItem(
                    indent = indent,
                    bullet = bullet.ifEmpty { null },
                    number = digits.ifEmpty { null }?.toLong(),
                    digitCount = digits.length,
                    delimiter = delimiter.firstOrNull(),
                    gap = gap,
                    paddedZeros = digits.length > 1 && digits.startsWith('0'),
                    checkGap = check?.let { line.substring(it.box + 2, it.text).ifEmpty { " " } },
                    content = check?.text ?: match.range.last + 1,
                )
            }

            /** `- - -` y `* * *` son una línea horizontal: tienen forma de marca, pero no lo son. */
            private fun isRule(line: String): Boolean {
                val marks = line.filterNot { it == ' ' || it == '\t' }
                return marks.length >= MIN_RULE && marks.first() in RULE_MARKS && marks.all { it == marks.first() }
            }
        }
    }

    /** Las columnas de sangría de [line]. Un tabulador vale cuatro, que es lo que vale en una lista. */
    private fun widthOf(line: String): Int {
        var width = 0
        for (char in line) {
            when (char) {
                ' ' -> width++
                '\t' -> width += TAB_WIDTH
                else -> return width
            }
        }
        return width
    }

    // ---------------------------------------------------------------- rodear

    /**
     * [char] tecleado con la selección `[start, end)`: `` ` `` y `*` la rodean en vez de
     * sustituirla, y la selección se queda en el texto de dentro para poder pulsar otra vez.
     * Por eso `***` son tres pulsaciones de lo mismo —cursiva, negrita, las dos— y `` ``` ``
     * también: a la tercera comilla las marcas se convierten en un bloque.
     *
     * Las marcas no pueden tocar un espacio —`**hola **` no es negrita—, así que los blancos de
     * los bordes de la selección se quedan fuera.
     */
    fun typed(text: String, start: Int, end: Int, char: Char): TypingResult? {
        if (char != TICK && char != STAR) return null
        var from = start
        var to = end
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        if (from == to) return null
        return if (char == TICK) tick(text, from, to) else star(text, from, to)
    }

    private fun star(text: String, from: Int, to: Int): TypingResult {
        if (marksAround(text, from, to, STAR) >= MAX_STARS) return TypingResult(emptyList(), from, to)
        return wrap(from, to, STAR)
    }

    /**
     * Código en línea hasta la tercera comilla, que lo convierte en bloque. Varias líneas son un
     * bloque desde la primera —el código en línea no cruza saltos de línea, y la tarjeta las
     * pintaría con las comillas a la vista— y las dos comillas siguientes de un ```` ``` ````
     * tecleado no hacen nada: el bloque ya está.
     */
    private fun tick(text: String, from: Int, to: Int): TypingResult {
        if (isFenced(text, from, to)) return TypingResult(emptyList(), from, to)
        if ('\n' in text.substring(from, to)) return fence(text, from, to, 0)
        val ticks = marksAround(text, from, to, TICK)
        return when {
            ticks == FENCE_TICKS - 1 -> fence(text, from, to, ticks)
            ticks >= FENCE_TICKS -> TypingResult(emptyList(), from, to)
            else -> wrap(from, to, TICK)
        }
    }

    private fun wrap(from: Int, to: Int, mark: Char) = TypingResult(
        listOf(TypingResult.Edit(to, to, mark.toString()), TypingResult.Edit(from, from, mark.toString())),
        from + 1,
        to + 1,
    )

    /** Cuántas veces está [mark] pegada a los dos lados de la selección. */
    private fun marksAround(text: String, from: Int, to: Int, mark: Char): Int {
        var count = 0
        while (from - count - 1 >= 0 && to + count < text.length &&
            text[from - count - 1] == mark && text[to + count] == mark
        ) {
            count++
        }
        return count
    }

    /**
     * Un bloque alrededor de `[from, to)`, quitando las [marks] comillas que ya llevara a cada
     * lado. Una valla tiene que ir sola en su línea, así que lo que haya delante o detrás en la
     * misma línea se separa con un salto más; si delante sólo hay sangría, las vallas la copian.
     */
    private fun fence(text: String, from: Int, to: Int, marks: Int): TypingResult {
        val outerFrom = from - marks
        val outerTo = to + marks
        val lineStart = text.lastIndexOf('\n', outerFrom - 1) + 1
        val lineEnd = text.indexOf('\n', outerTo).let { if (it < 0) text.length else it }
        val before = text.substring(lineStart, outerFrom)
        val after = text.substring(outerTo, lineEnd)
        val indent = if (before.isBlank()) before else ""
        val open = if (before.isBlank()) {
            TypingResult.Edit(outerFrom, from, "$FENCE\n$indent")
        } else {
            TypingResult.Edit(lineStart + before.trimEnd(' ', '\t').length, from, "\n$FENCE\n")
        }
        val close = if (after.isBlank()) {
            TypingResult.Edit(to, lineEnd, "\n$indent$FENCE")
        } else {
            val spaces = after.length - after.trimStart(' ', '\t').length
            TypingResult.Edit(to, outerTo + spaces, "\n$indent$FENCE\n$indent")
        }
        val inner = open.start + open.text.length
        return TypingResult(listOf(open, close), inner, inner + (to - from))
    }

    /** Si `[from, to)` son justo las líneas de dentro de un bloque. */
    private fun isFenced(text: String, from: Int, to: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', from - 1) + 1
        val lineEnd = text.indexOf('\n', to).let { if (it < 0) return false else it }
        if (lineStart == 0) return false
        if (text.substring(lineStart, from).isNotBlank() || text.substring(to, lineEnd).isNotBlank()) return false
        // Recorriendo desde el principio: la valla de arriba puede ser la que cierra otro bloque.
        val fence = CodeFence()
        var opening = CodeFence.Role.PROSE
        text.substring(0, lineStart - 1).split('\n').forEach { opening = fence.next(it) }
        val closingEnd = text.indexOf('\n', lineEnd + 1).let { if (it < 0) text.length else it }
        val closing = text.substring(lineEnd + 1, closingEnd).trim()
        return opening == CodeFence.Role.FENCE && fence.inBlock &&
            closing.length >= FENCE_TICKS && closing.all { it == TICK }
    }

    private const val TICK = '`'
    private const val STAR = '*'
    private const val FENCE = "```"
    private const val FENCE_TICKS = 3
    private const val MAX_STARS = 3
    private const val TAB_WIDTH = 4
    private const val MIN_RULE = 3
    private const val RULE_MARKS = "-*_"

    /** El tope de CommonMark, el mismo de [Checklist]: con más cifras ya no es una lista. */
    private const val MAX_DIGITS = 9
}
