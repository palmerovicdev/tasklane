package com.tasklane.domain.model

/**
 * En qué línea está **ahora** lo que se ancló.
 *
 * Un número de línea a secas envejece en cuanto alguien añade un import: a la semana
 * el ancla apunta a otra cosa y deja de merecer confianza. Por eso [CodeAnchor] guarda
 * además el texto de la línea, y por eso abrir un ancla pasa por aquí: si el texto
 * sigue donde estaba, se va ahí; si se movió, se busca **hacia fuera** desde su sitio
 * anterior y gana la coincidencia más cercana, que es la que casi siempre es la buena.
 *
 * Si no aparece —la línea se borró o se reescribió—, se va al número guardado de todas
 * formas, acotado al fichero. Abrir el fichero por un sitio aproximado es mejor que no
 * abrirlo: quien pulsa el ancla quiere ir a ese fichero, y el contexto de alrededor le
 * dice enseguida si la nota sigue teniendo sentido.
 *
 * Kotlin puro y sobre un accesor de líneas y no sobre un `Document`: así se prueba sin
 * IDE y, sobre un fichero real, no hay que trocear el contenido entero para leer unas
 * cuantas líneas.
 */
object AnchorResolver {

    /**
     * @param lineCount cuántas líneas tiene el fichero ahora mismo.
     * @param lineAt el texto de una línea, 0-based. Se llama con índices válidos.
     */
    fun resolve(anchor: CodeAnchor, lineCount: Int, lineAt: (Int) -> String): Int {
        if (lineCount <= 0) return 0
        val last = lineCount - 1
        val start = anchor.line.coerceIn(0, last)
        val text = anchor.text
        if (text.isEmpty()) return start
        if (lineAt(start).trim() == text) return start

        // Hacia abajo antes que hacia arriba a igual distancia: el código crece más de
        // lo que mengua, así que lo anclado suele haber bajado.
        var offset = 1
        while (start - offset >= 0 || start + offset <= last) {
            val down = start + offset
            if (down <= last && lineAt(down).trim() == text) return down
            val up = start - offset
            if (up >= 0 && lineAt(up).trim() == text) return up
            offset++
        }
        return start
    }

    /** Comodidad para las pruebas y para cualquiera que ya tenga las líneas partidas. */
    fun resolve(anchor: CodeAnchor, lines: List<String>): Int =
        resolve(anchor, lines.size) { lines[it] }

    /**
     * Las líneas que abarca **ahora** un ancla (2.15.0): la primera donde la deje
     * [resolve] y, detrás, las [CodeAnchor.span] que se anclaron, acotadas al final del
     * fichero. En un ancla de una línea es esa línea sola.
     *
     * El bloque se va entero con su primera línea: si el código de encima creció, baja
     * todo junto. Lo que no se sigue es lo que cambia **dentro** —dos líneas añadidas en
     * medio dejan el final dos más arriba de lo que era—; sin guardar el texto de cada
     * línea no hay de dónde saberlo, y un bloque aproximado sigue diciendo de qué parte
     * del fichero habla la nota.
     */
    fun range(anchor: CodeAnchor, lineCount: Int, lineAt: (Int) -> String): IntRange {
        val first = resolve(anchor, lineCount, lineAt)
        return first..minOf(first + anchor.span, (lineCount - 1).coerceAtLeast(first))
    }

    fun range(anchor: CodeAnchor, lines: List<String>): IntRange = range(anchor, lines.size) { lines[it] }
}
