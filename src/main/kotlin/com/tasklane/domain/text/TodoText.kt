package com.tasklane.domain.text

/**
 * El texto de un comentario `TODO`, visto como tarea (2.8.0).
 *
 * El IDE ya sabe dónde hay un TODO —su índice es el de la ventana *TODO*, con los
 * patrones que el usuario haya configurado— y devuelve el tramo que empieza en la
 * palabra clave. Aquí se decide qué parte de ese tramo es **la tarea** y si el
 * comentario que lo contiene se puede borrar sin llevarse nada más.
 *
 * Kotlin puro: se prueba sin IDE.
 */
object TodoText {

    /**
     * `TODO(ana): arreglar el login` → `arreglar el login`. Se quita la palabra clave,
     * el autor entre paréntesis si lo hay y el separador. Las líneas de continuación
     * llegan como [parts] aparte y se juntan con un espacio: un TODO partido en dos
     * líneas de comentario es una frase, no dos párrafos.
     */
    fun clean(parts: List<String>): String {
        val joined = parts.joinToString(" ") { it.trim() }.trim()
        return LEAD.replaceFirst(joined, "").replace(SPACES, " ").trim()
    }

    /** La palabra clave en minúsculas —`todo`, `fixme`—, que sirve de etiqueta. */
    fun keyword(text: String): String =
        WORD.find(text.trimStart())?.value?.lowercase().orEmpty()

    /**
     * ¿El comentario es **sólo** el TODO? Es lo que decide si pasarlo a Tasklane lo
     * borra del código o lo deja: un `/** Doc … TODO revisar */` lleva documentación
     * que no es de la tarea, y borrarlo entero se la llevaría.
     *
     * Se quitan del texto del comentario los tramos del TODO —[todo], en coordenadas del
     * comentario— y las marcas de comentario del lenguaje —[markers], más las habituales—,
     * y tiene que quedar sólo espacio y asteriscos de bloque.
     */
    fun isOnlyTodo(comment: String, todo: List<IntRange>, markers: List<String> = emptyList()): Boolean {
        val rest = StringBuilder(comment)
        for (range in todo.sortedByDescending { it.first }) {
            val from = range.first.coerceIn(0, rest.length)
            val to = (range.last + 1).coerceIn(from, rest.length)
            rest.delete(from, to)
        }
        var left = rest.toString()
        for (marker in (markers + COMMON).filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            left = left.replace(marker, " ")
        }
        return left.all { it.isWhitespace() || it == '*' }
    }

    private val LEAD = Regex("""^[\p{L}_]+(\([^)]*\))?\s*[:\-–—]?\s*""")
    private val WORD = Regex("""^[\p{L}_]+""")
    private val SPACES = Regex("""\s+""")

    /** Las marcas de comentario de los lenguajes de siempre, por si el del fichero no dice las suyas. */
    private val COMMON = listOf("<!--", "-->", "/**", "/*", "*/", "///", "//", "#", "--", ";", "%")
}
