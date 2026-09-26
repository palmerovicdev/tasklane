package com.tasklane.domain.query

/**
 * Lo que hace pulsar `#api` en una tarjeta (P30): filtrar por esa etiqueta, o dejar de
 * hacerlo si ya se estaba filtrando.
 *
 * Se **añade** a lo que ya hubiera escrito en vez de sustituirlo, porque las etiquetas
 * se piden todas —`#api #urgente` son las que llevan las dos, ver [TaskQuery]—: pulsar
 * otra etiqueta estrecha la búsqueda, que es lo que se quiere al ir de una en otra. Y
 * pulsar la misma otra vez la quita, que es la forma de volver sin ir al buscador.
 *
 * Trabaja sobre el texto y no sobre la [TaskQuery]: lo que se toca es el buscador, y el
 * resto de lo escrito —comillas, espacios, operadores— tiene que quedar como estaba.
 */
object TagToggle {

    /** Si [raw] ya filtra por [tag] tal cual, como `#api` o `#"en revisión"`. */
    fun isActive(raw: String, tag: String): Boolean = pattern(tag).containsMatchIn(raw)

    fun toggle(raw: String, tag: String): String {
        val pattern = pattern(tag)
        if (pattern.containsMatchIn(raw)) return pattern.replace(raw, "").replace(SPACES, " ").trim()
        val text = raw.trimEnd()
        return if (text.isEmpty()) token(tag) else "$text ${token(tag)}"
    }

    /**
     * `#api`. Una etiqueta con espacios —sólo la puede crear un agente, el diálogo los
     * usa para separar— va entre comillas, que es como la lee el buscador.
     */
    fun token(tag: String): String = if (tag.any(Char::isWhitespace)) "#\"$tag\"" else "#$tag"

    /** El token entero y suelto, sin mirar mayúsculas: `#API` también es `#api`, pero `#apis` no. */
    private fun pattern(tag: String): Regex =
        Regex("""(^|\s)#"?${Regex.escape(tag)}"?(?=\s|$)""", RegexOption.IGNORE_CASE)

    private val SPACES = Regex("""\s{2,}""")
}
