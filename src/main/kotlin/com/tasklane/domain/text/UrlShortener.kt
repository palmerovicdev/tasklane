package com.tasklane.domain.text

/**
 * Convierte una URL en algo que quepa en una fila del árbol **sin dejar de
 * identificarla**.
 *
 * La regla es una: el dominio primero y entero. Recortar por el final —lo que hace
 * un `StringUtil.shortenTextWithEllipsis` cualquiera— produce
 * `https://youtrack.jetbrains…`, que es justo la parte que no distingue un enlace de
 * otro. Anteponiendo el dominio queda `youtrack.jetbrains.com/…/ABC-123`: se ve a
 * dónde va y se ve qué es.
 *
 * Se cae en tres escalones, del más informativo al menos:
 *
 *  1. `dominio/ruta/completa` si ya cabe.
 *  2. `dominio/…/ultimo-segmento` — el último segmento es casi siempre el
 *     identificador (el ticket, el fichero, el commit).
 *  3. `dominio/…` cuando ni eso cabe.
 *
 * El esquema y el `www.` se quitan siempre: ocupan ocho caracteres y no dicen nada.
 * Lo que se abre es **siempre** la URL original, nunca esta.
 *
 * Kotlin puro, sin dependencias de la plataforma: se testea sin arrancar un IDE.
 */
object UrlShortener {

    const val DEFAULT_MAX = 48

    private const val ELLIPSIS = "…"

    fun shorten(url: String, max: Int = DEFAULT_MAX): String {
        val naked = url
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removeSuffix("/")

        if (naked.length <= max) return naked

        val slash = naked.indexOf('/')
        // Sin ruta no hay nada que colapsar: el dominio es todo lo que hay, y si aun
        // asi no cabe se recorta por el final porque no queda alternativa.
        if (slash < 0) return clip(naked, max)

        val host = naked.take(slash)
        if (host.length + 2 >= max) return clip(host, max - 2) + "/$ELLIPSIS"

        val last = naked
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotEmpty() && it != host }

        if (last != null) {
            val collapsed = "$host/$ELLIPSIS/$last"
            if (collapsed.length <= max) return collapsed
        }
        return "$host/$ELLIPSIS"
    }

    /** Recorte duro por el final. Último recurso: aquí ya no hay nada que preservar. */
    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take((max - 1).coerceAtLeast(0)) + ELLIPSIS
}
