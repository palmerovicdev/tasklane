package com.tasklane.domain.text

/**
 * Las casillas de una lista de comprobación en el cuerpo: `- [ ] algo` y `- [x] algo`,
 * como las escribe GitHub (2.11.0).
 *
 * Ya estuvieron hasta la `1.0.0` y se quitaron: se contaban, pero una tarea cuyo cuerpo
 * **era** la lista enseñaba los corchetes en crudo en el título. Vuelven con eso resuelto
 * —la tarjeta pinta la casilla en el título igual que en el cuerpo— y pulsables: marcar
 * una en la tarjeta escribe la `x` en el cuerpo, que sigue siendo la fuente de verdad.
 *
 * Sin expresiones regulares: esto se ejecuta al derivar cada línea de cada tarea pintada.
 */
object Checklist {

    /**
     * Una línea que es una casilla. [box] es dónde está el carácter de dentro de los
     * corchetes —el que pasa de espacio a `x`— y [text] dónde empieza lo que dice, los dos
     * en coordenadas de la línea que se analizó.
     */
    data class Item(val checked: Boolean, val box: Int, val text: Int)

    /**
     * La casilla de [line], o `null` si no es una. Admite hasta tres espacios de sangría
     * —o más, para listas anidadas—, los tres marcadores de viñeta y los de lista
     * numerada (`1.` y `1)`), y exige el espacio tras el corchete salvo al final de la
     * línea: `- [x]` sola es una casilla vacía de texto, `- [x]algo` no es una casilla.
     */
    fun parse(line: String): Item? {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        if (i >= line.length) return null
        when {
            line[i] == '-' || line[i] == '*' || line[i] == '+' -> i++
            line[i].isDigit() -> {
                val start = i
                while (i < line.length && line[i].isDigit()) i++
                if (i - start > MAX_NUMBER_DIGITS || i >= line.length || (line[i] != '.' && line[i] != ')')) return null
                i++
            }
            else -> return null
        }
        if (i >= line.length || line[i] != ' ') return null
        while (i < line.length && line[i] == ' ') i++
        if (i + 2 >= line.length || line[i] != '[' || line[i + 2] != ']') return null
        val mark = line[i + 1]
        val checked = when (mark) {
            ' ' -> false
            'x', 'X' -> true
            else -> return null
        }
        val after = i + 3
        if (after < line.length && line[after] != ' ') return null
        var text = after
        while (text < line.length && line[text] == ' ') text++
        return Item(checked, i + 1, text)
    }

    /**
     * [body] con la casilla de la posición [box] marcada o desmarcada, o `null` si ahí ya
     * no hay una casilla —el cuerpo cambió entre pintar la tarjeta y pulsarla—. Se vuelve a
     * analizar la línea entera en vez de fiarse del carácter: un espacio suelto en esa
     * posición no es una casilla.
     */
    fun toggle(body: String, box: Int): String? {
        if (box !in body.indices) return null
        val start = body.lastIndexOf('\n', box - 1) + 1
        val end = body.indexOf('\n', box).takeIf { it >= 0 } ?: body.length
        val item = parse(body.substring(start, end)) ?: return null
        if (start + item.box != box) return null
        val next = if (item.checked) ' ' else 'x'
        return body.substring(0, box) + next + body.substring(box + 1)
    }

    /** Cuántas casillas hay en [body] y cuántas están marcadas. Fuera de los bloques de código. */
    fun progress(body: String): Pair<Int, Int> {
        var done = 0
        var total = 0
        val fence = CodeFence()
        for (line in body.lineSequence()) {
            if (fence.next(line) != CodeFence.Role.PROSE) continue
            val item = parse(line) ?: continue
            total++
            if (item.checked) done++
        }
        return done to total
    }

    /** El tope de CommonMark: un número de lista de más cifras ya no es una lista. */
    private const val MAX_NUMBER_DIGITS = 9
}
