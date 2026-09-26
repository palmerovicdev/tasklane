package com.tasklane.domain.text

/**
 * Un texto pegado o soltado en la lista, visto como tareas: **una por línea** (2.19.0).
 *
 * Es copiar la lista de una reunión, de un chat o de un `README` y tenerla en Tasklane sin
 * pasar por el diálogo diez veces. Lo que se quita de cada línea es lo que la hacía ser un
 * elemento de lista —la viñeta (`-`, `*`, `+`, `•`), la numeración (`1.`, `2)`) y la casilla
 * (`[ ]`, `[x]`)—, que en una tarea sólo sería ruido delante del título.
 *
 * Una línea sin una sola letra ni cifra no es una tarea: son las rayas de un `---`, los
 * `===` de un subrayado o el `|---|---|` de una tabla, que acompañan a una lista copiada de
 * un documento y no dicen nada.
 *
 * Kotlin puro: se prueba sin IDE.
 */
object PastedLines {

    /** Las tareas que salen de [text], en su orden. Vacía si no hay ninguna. */
    fun split(text: String): List<String> =
        text.lineSequence().map(::clean).filter { line -> line.any(Char::isLetterOrDigit) }.toList()

    /** [line] sin sangría, sin viñeta o número y sin casilla. */
    fun clean(line: String): String = MARKER.replaceFirst(line.trim(), "").trim()

    /**
     * Viñeta o número —hasta tres cifras: `2026.` al principio de una frase es un año, no
     * el elemento dos mil veintiséis—, casilla, o las dos, y siempre seguidas de espacio o
     * del final: `-5 grados` y `*negrita*` no son listas.
     */
    private val MARKER = Regex("""^(?:(?:[-*+•◦▪‣]|\d{1,3}[.)])(?:\s+|$))?(?:\[[ xX]](?:\s+|$))?""")
}
