package com.tasklane.domain.text

import com.tasklane.domain.model.TaskLink

/**
 * Saca del cuerpo de una tarea los enlaces que se pueden pintar y abrir.
 *
 * Se ejecuta **al escribir**, no al pintar: el resultado se cachea en `Task.links`,
 * de modo que repintar una fila del árbol —que ocurre en cada movimiento del ratón—
 * nunca dispara una expresión regular. Ver `docs/architecture.html` §9.
 *
 * Tres decisiones que son el 90 % de la utilidad de esta clase:
 *
 * - **Sólo `http` y `https`.** Un `file:` o un `javascript:` pegado en una tarea no
 *   se convierte en un enlace clicable. No es un detalle de estilo: lo que se pinta
 *   como enlace es lo que un clic va a ejecutar.
 * - **El código no se mira.** Una URL dentro de un bloque ``` o entre comillas
 *   simples invertidas es un ejemplo, no un destino.
 * - **La puntuación de la frase no es parte de la URL.** `Mira https://ej.com/a.`
 *   enlaza a `https://ej.com/a`, y el punto se queda en la frase. Los paréntesis se
 *   cuentan, así que `(ver https://ej.com/a)` tampoco se lleva el cierre — pero
 *   `https://es.wikipedia.org/wiki/Ada_(lenguaje)` sí conserva el suyo.
 *
 * Kotlin puro: se testea sin arrancar un IDE.
 */
object LinkExtractor {

    private const val FENCE = "```"

    /**
     * Markdown primero, URL suelta después. El orden importa: en `[texto](url)` la
     * alternativa de Markdown gana en la posición del corchete, así que la URL de
     * dentro no se extrae además por su cuenta.
     *
     * La URL suelta admite paréntesis —los llevan Wikipedia y media
     * documentación— y los desequilibrados se recortan luego.
     */
    private val PATTERN = Regex(
        """\[([^\]\n]*)\]\((\S+?)\)""" +
            """|(?:https?://|www\.)[^\s<>"'`\[\]]+""",
        RegexOption.IGNORE_CASE,
    )

    /** Puntuación que casi siempre pertenece a la frase y no a la URL. */
    private const val TRAILING = ".,;:!?\"'«»"

    fun extract(body: String, max: Int = UrlShortener.DEFAULT_MAX): List<TaskLink> {
        if (body.length < MIN_LENGTH) return emptyList()

        val code = codeRanges(body)
        val links = ArrayList<TaskLink>()

        for (match in PATTERN.findAll(body)) {
            val start = match.range.first
            if (code.any { start in it }) continue

            val markdown = match.groups[2] != null
            val raw = if (markdown) match.groupValues[2] else trimTrailing(match.value)
            val url = normalize(raw) ?: continue

            val range = if (markdown) match.range else start until (start + raw.length)
            val label = match.groups[1]?.value?.trim()?.takeIf { it.isNotEmpty() }
            links += TaskLink(url, label ?: UrlShortener.shorten(url, max), range)
        }
        return links
    }

    /**
     * Sólo `http(s)`. El `www.` suelto se completa a `https://` porque es lo que
     * escribe la gente y lo que abriría cualquier navegador.
     */
    private fun normalize(raw: String): String? = when {
        raw.startsWith("http://", ignoreCase = true) -> raw
        raw.startsWith("https://", ignoreCase = true) -> raw
        raw.startsWith("www.", ignoreCase = true) -> "https://$raw"
        else -> null
    }

    /**
     * Recorta por el final la puntuación de la frase. El cierre de un paréntesis o
     * un corchete sólo se recorta si **sobra**: se cuenta la apertura dentro de la
     * propia URL, que es lo que distingue `(ver https://ej.com/a)` de
     * `https://es.wikipedia.org/wiki/Ada_(lenguaje)`.
     */
    private fun trimTrailing(url: String): String {
        var end = url.length
        while (end > 0) {
            when (val last = url[end - 1]) {
                in TRAILING -> end--
                ')', ']' -> {
                    val open = if (last == ')') '(' else '['
                    val text = url.substring(0, end)
                    if (text.count { it == open } < text.count { it == last }) end-- else return text
                }

                else -> return url.substring(0, end)
            }
        }
        return ""
    }

    /**
     * Los tramos que son código: bloques con vallas ``` y tramos entre comillas
     * simples invertidas dentro de una línea.
     *
     * Un bloque sin cerrar se considera abierto hasta el final del cuerpo. Es lo que
     * pasa mientras se escribe, y tratar como enlace lo que está a medio teclear
     * haría parpadear la fila.
     */
    private fun codeRanges(body: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var fence = -1
        var index = 0

        while (index <= body.length) {
            val end = body.indexOf('\n', index).takeIf { it >= 0 } ?: body.length
            if (body.startsWith(FENCE, index + leadingBlanks(body, index, end))) {
                if (fence < 0) fence = index else { ranges += fence until end; fence = -1 }
            } else if (fence < 0) {
                inlineCode(body, index, end, ranges)
            }
            if (end == body.length) break
            index = end + 1
        }

        if (fence >= 0) ranges += fence until body.length
        return ranges
    }

    private fun leadingBlanks(body: String, from: Int, to: Int): Int {
        var i = from
        while (i < to && (body[i] == ' ' || body[i] == '\t')) i++
        return i - from
    }

    private fun inlineCode(body: String, from: Int, to: Int, out: MutableList<IntRange>) {
        var i = from
        while (i < to) {
            if (body[i] != '`') {
                i++
                continue
            }
            val close = body.indexOf('`', i + 1)
            // Una comilla sin pareja en la misma línea no abre nada: es un carácter
            // suelto, y tomarla por código escondería todo lo que venga detrás.
            if (close < 0 || close >= to) return
            out += i..close
            i = close + 1
        }
    }

    /** `www.a.io` — nada más corto puede ser una URL. */
    private const val MIN_LENGTH = 8
}
