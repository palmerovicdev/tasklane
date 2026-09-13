package com.tasklane.domain.text

/**
 * De lo que se escribe a la lista de etiquetas.
 *
 * Vive en el dominio y no en el campo que las recoge porque hay dos sitios que
 * escriben etiquetas —el control de fichas del diálogo y el pegado de un texto entero
 * dentro de él— y las dos entradas tienen que dar exactamente la misma lista.
 *
 * Tres reglas, y las tres existen por un caso real:
 *
 * - **La almohadilla de delante sobra.** Quien copia `#api` de una tarea no tiene por
 *   qué saber que aquí la marca no se escribe.
 * - **Coma, espacio y salto de línea separan.** Es lo que sale de pegar una lista
 *   escrita en cualquier otro sitio.
 * - **Repetidas, una sola, sin mirar mayúsculas.** `api` y `API` son la misma
 *   etiqueta para quien las lee; se conserva la primera forma escrita porque es la
 *   que el usuario eligió.
 */
object TagParser {

    fun parse(text: String): List<String> = text
        .split(*SEPARATORS)
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    /** Si [char] cierra la etiqueta que se está escribiendo. */
    fun isSeparator(char: Char): Boolean = char in SEPARATORS

    /** Añade [tag] a [current] si aporta algo. Devuelve la lista tal cual si ya estaba. */
    fun add(current: List<String>, tag: String): List<String> = parse((current + tag).joinToString(","))

    private val SEPARATORS = charArrayOf(',', ' ', '\n', '\t')
}
