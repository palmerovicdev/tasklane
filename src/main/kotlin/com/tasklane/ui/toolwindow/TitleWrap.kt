package com.tasklane.ui.toolwindow

import com.intellij.ui.SimpleTextAttributes
import com.tasklane.domain.model.TaskLink

/**
 * Un tramo de texto de la fila con el estilo con el que se pinta y, si lo hay, el
 * enlace del que forma parte.
 *
 * [matched] distingue lo que pasa por el resaltado de la búsqueda de lo que se añade
 * tal cual. La metainformación —fecha, repositorio, estado— no es parte de lo que se
 * buscó, y resaltarla dentro sería señalar una coincidencia que no existe.
 */
internal class Run(
    val text: String,
    val style: SimpleTextAttributes,
    val link: TaskLink? = null,
    val matched: Boolean = false,
) {
    fun withText(text: String): Run = Run(text, style, link, matched)
}

/**
 * Reparte tramos de texto en líneas que quepan en un ancho dado.
 *
 * Se envuelve por **tramos ya troceados** y no palabra a palabra en el componente
 * final porque el resaltado de la búsqueda casa el patrón contra el texto que se le
 * pasa: cortar por palabras perdería cualquier coincidencia de más de una. Lo que
 * sale de aquí es, por cada línea, la lista de tramos más larga posible, así que el
 * resaltado sigue viendo frases enteras salvo en el punto exacto del corte.
 *
 * La medida entra como función en vez de resolverse aquí: quien pinta es un
 * `SimpleColoredComponent` y su ancho depende de la fuente derivada de cada estilo,
 * que sólo él conoce. De paso, el troceado se puede probar con una métrica falsa.
 */
internal object TitleWrap {

    /** Lo que se añade al final cuando el texto no cabe en [maxLines]. */
    const val ELLIPSIS = "…"

    /**
     * El resultado del troceado: las líneas y si hubo que **dejarse algo fuera**.
     *
     * Lo segundo no se deduce mirando las líneas. Mirar si la última acaba en
     * puntos suspensivos confundiría un recorte con un título que termina en «…»
     * escrito a mano, y contar líneas tampoco sirve: un texto puede caber justo en
     * el máximo. Quien trocea es el único que lo sabe de verdad, así que lo dice.
     * Es lo que decide si la tarjeta enseña el botón de desplegar.
     */
    class Fit(val lines: List<List<Run>>, val clipped: Boolean)

    /**
     * @param available ancho útil en píxeles. Si no es positivo no se envuelve: el
     *   árbol todavía no tiene tamaño y adivinar daría un corte que habría que
     *   rehacer en el primer `doLayout`.
     */
    fun wrap(
        runs: List<Run>,
        available: Int,
        maxLines: Int,
        width: (String, SimpleTextAttributes) -> Int,
    ): List<List<Run>> = fit(runs, available, maxLines, width).lines

    /** Como [wrap], y además si algo se quedó fuera. Ver [Fit]. */
    fun fit(
        runs: List<Run>,
        available: Int,
        maxLines: Int,
        width: (String, SimpleTextAttributes) -> Int,
    ): Fit {
        if (runs.isEmpty()) return Fit(emptyList(), false)
        if (available <= 0 || maxLines <= 1) return Fit(listOf(runs), false)

        val lines = mutableListOf<MutableList<Piece>>(mutableListOf())
        var used = 0

        for (run in runs) {
            for (token in tokenize(run.text)) {
                // El espacio final de una palabra no cuenta para decidir si cabe: si
                // la palabra acaba la línea, ese espacio no se ve.
                val solid = width(token.trimEnd(), run.style)
                val full = width(token, run.style)
                val current = lines.last()

                if (used + solid <= available || current.isEmpty()) {
                    current += Piece(token, run)
                    used += full
                    continue
                }
                if (lines.size == maxLines) {
                    // Ya no hay dónde seguir: el resto se resume con el puntos
                    // suspensivos que pone `truncate`.
                    return finish(lines, available, width, overflowed = true)
                }
                lines += mutableListOf(Piece(token, run))
                used = full
            }
        }
        return finish(lines, available, width, overflowed = false)
    }

    private fun finish(
        lines: List<MutableList<Piece>>,
        available: Int,
        width: (String, SimpleTextAttributes) -> Int,
        overflowed: Boolean,
    ): Fit {
        val trimmed = lines.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return Fit(emptyList(), false)
        if (overflowed) truncate(trimmed.last(), available, width)
        // Y la que **aun así** se pasa: una palabra sola más ancha que el hueco —un
        // identificador largo, una URL sin acortar— no tiene espacios por donde
        // partirse, así que se queda entera y se lleva la fila por delante. Se corta
        // por letras. Que la fila no pueda ser más ancha que su sitio no es cosmética:
        // los botones de la derecha se colocan contra ese ancho, y con la fila
        // desbordada dejaban de caer donde se ven.
        //
        // No cuenta como `clipped`: lo que decide eso es si **desplegar** enseña algo
        // más, y aquí desplegar cortaría la misma palabra por el mismo sitio.
        for (line in trimmed) {
            if (measureLine(line, width) > available) truncate(line, available, width)
        }
        return Fit(trimmed.map(::merge), overflowed)
    }

    private fun measureLine(line: List<Piece>, width: (String, SimpleTextAttributes) -> Int): Int =
        line.sumOf { width(it.text.trimEnd(), it.run.style) }

    /**
     * Mete los puntos suspensivos al final de la línea, quitando palabras hasta que
     * quepan. Cuando ya no queda ninguna que quitar —queda una sola y sigue sin
     * caber— se corta **por letras**: es la única forma de que una palabra más larga
     * que el hueco deje de salirse de la fila.
     */
    private fun truncate(
        line: MutableList<Piece>,
        available: Int,
        width: (String, SimpleTextAttributes) -> Int,
    ) {
        val dots = width(ELLIPSIS, line.last().run.style)
        while (line.size > 1 && measureLine(line, width) + dots > available) {
            line.removeAt(line.size - 1)
        }
        val last = line.last()
        val head = measureLine(line.dropLast(1), width)
        var text = last.text.trimEnd()
        // Letra a letra y no por proporción: los tramos pueden ir en negrita o en
        // cursiva, y cada fuente mide lo suyo. Se puede quedar en nada, que es
        // preferible a una línea que se sale del panel.
        while (text.isNotEmpty() && head + width(text, last.run.style) + dots > available) {
            text = text.dropLast(1)
        }
        line[line.size - 1] = Piece(text + ELLIPSIS, last.run)
    }

    /**
     * Vuelve a juntar en un solo tramo las palabras seguidas que venían del mismo
     * [Run]. Es lo que devuelve al resaltado de la búsqueda el contexto que el
     * troceado le había quitado.
     */
    private fun merge(line: List<Piece>): List<Run> {
        val out = mutableListOf<Run>()
        val text = StringBuilder()
        var source: Run? = null
        for (piece in line) {
            if (piece.run !== source) {
                source?.let { out += it.withText(text.toString()) }
                source = piece.run
                text.setLength(0)
            }
            text.append(piece.text)
        }
        source?.let { out += it.withText(text.toString()) }
        return out
    }

    /** Palabras con los espacios que las siguen pegados, para no perderlos al unir. */
    private fun tokenize(text: String): List<String> =
        TOKEN.findAll(text).map { it.value }.toList()

    private class Piece(val text: String, val run: Run)

    private val TOKEN = Regex("\\S+\\s*|\\s+")
}
