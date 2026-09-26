package com.tasklane.domain.query

import com.tasklane.domain.model.TagCount
import com.tasklane.domain.text.TextNormalizer

/**
 * Lo que vale detrás de cada operador en el repositorio que se está mirando: los nombres
 * visibles, no los ids, porque es lo que se escribe.
 */
data class QueryVocabulary(
    val states: List<String> = emptyList(),
    /** La más alta primero, como en la ventana. */
    val priorities: List<String> = emptyList(),
    val repos: List<String> = emptyList(),
    /** Las más usadas primero. */
    val tags: List<TagCount> = emptyList(),
)

/**
 * Una sugerencia: [text] sustituye al token entero —con su `-` si lo llevaba—. [hint] es
 * lo que se pinta en gris al lado: una cuenta, o la clave del bundle de [hintKey], que el
 * dominio no conoce.
 */
data class QuerySuggestion(val text: String, val label: String, val hint: String? = null, val hintKey: String? = null)

/**
 * Qué sugerir y qué sustituye: los caracteres de [from] a [to]. [preselect] dice si la
 * primera ya sale elegida —ver [QueryCompletion]—.
 */
data class QueryCompletionResult(val from: Int, val to: Int, val suggestions: List<QuerySuggestion>, val preselect: Boolean)

/**
 * El autocompletado del buscador (2.21.0): dado lo escrito y dónde está el cursor, qué
 * ofrecer.
 *
 * - Tras `state:`, `p:`, `repo:`, `is:`, `has:` o un operador de fecha, sus valores; tras
 *   `#`, las etiquetas del repositorio. Ahí la primera sale **elegida**: quien escribió
 *   `state:` ya ha dicho que quiere un estado.
 * - Una palabra suelta que empieza como un operador (`st` → `state:`) lo ofrece **sin
 *   elegir nada**, a partir de dos letras. `Enter` sigue siendo lo de siempre —ir a la
 *   lista— y quien busca «stack» no se encuentra con un `state:` puesto; `Tab` o `↓` lo
 *   toman.
 * - Pedido a mano (`⌃Espacio`) y sin nada escrito, todos los operadores.
 *
 * Todo casa por **prefijo** y sin acentos ni mayúsculas, como los operadores al buscar.
 * Nada que ofrecer, o sólo lo que ya está escrito, es `null`: no hay ventana que abrir.
 */
object QueryCompletion {

    fun complete(text: String, caret: Int, vocabulary: QueryVocabulary, explicit: Boolean): QueryCompletionResult? {
        val at = caret.coerceIn(0, text.length)
        val (from, to) = token(text, at)
        val typed = text.substring(from, at)
        val whole = text.substring(from, to)

        val not = if (typed.startsWith(NOT)) NOT else ""
        val body = typed.removePrefix(not)
        val colon = body.indexOf(':')

        val result = when {
            body.startsWith(TAG) -> values(not + TAG, body.drop(TAG.length), tags(vocabulary), preselect = true)
            colon > 0 -> {
                val key = body.take(colon)
                val values = valuesOf(key.lowercase(), vocabulary) ?: return null
                values(not + key + ":", body.substring(colon + 1), values, preselect = true)
            }
            body.length >= AUTO_OPERATOR || (explicit && body.isNotEmpty()) -> operators(not, body, vocabulary, explicit)
            explicit -> Found(operators(vocabulary).map { it.copy(text = not + it.text) }, preselect = false)
            else -> return null
        }
        val suggestions = result.suggestions.takeIf { it.isNotEmpty() } ?: return null
        // Lo que ya está escrito entero no se ofrece: la ventana taparía la lista para nada.
        if (suggestions.size == 1 && same(suggestions.single().text, whole)) return null
        return QueryCompletionResult(from, to, suggestions, result.preselect)
    }

    /**
     * Dónde empieza y acaba el token en el que está [caret], con las comillas del
     * buscador: `state:"In Review"` es uno solo. Es lo que se sustituye al insertar, y se
     * vuelve a preguntar justo entonces —el texto puede haber cambiado desde que se ofreció—.
     */
    fun token(text: String, caret: Int): Pair<Int, Int> {
        val at = caret.coerceIn(0, text.length)
        var from = 0
        var quoted = false
        for (i in 0 until at) {
            val ch = text[i]
            if (ch == '"') quoted = !quoted else if (ch.isWhitespace() && !quoted) from = i + 1
        }
        var to = at
        while (to < text.length) {
            val ch = text[to]
            if (ch == '"') quoted = !quoted else if (ch.isWhitespace() && !quoted) break
            to++
        }
        return from to to
    }

    private class Found(val suggestions: List<QuerySuggestion>, val preselect: Boolean)

    /** Los valores que empiezan como [partial], con [prefix] —`-state:`, `#`…— delante. */
    private fun values(prefix: String, partial: String, all: List<QuerySuggestion>, preselect: Boolean): Found {
        val wanted = TextNormalizer.normalize(partial.replace("\"", ""))
        val found = all.filter { TextNormalizer.normalize(it.label).startsWith(wanted) }
            .map { it.copy(text = prefix + quoted(it.text)) }
        return Found(found, preselect)
    }

    private fun operators(not: String, word: String, vocabulary: QueryVocabulary, explicit: Boolean): Found {
        val wanted = TextNormalizer.normalize(word)
        val found = operators(vocabulary)
            .filter { it.label.startsWith(wanted) && (explicit || it.label != TAG) }
            .map { it.copy(text = not + it.text) }
        return Found(found, preselect = false)
    }

    /**
     * Los operadores, en el orden en que se ofrecen: primero los que más se escriben.
     * `repo:` sólo si hay más de un repositorio, que si no es un filtro que no filtra.
     */
    private fun operators(vocabulary: QueryVocabulary): List<QuerySuggestion> = buildList {
        add(operator("state:", "search.complete.op.state"))
        add(operator("p:", "search.complete.op.priority"))
        add(operator("is:", "search.complete.op.is"))
        add(operator("has:", "search.complete.op.has"))
        add(operator("due:", "search.complete.op.due"))
        add(operator("closed:", "search.complete.op.closed"))
        add(operator("created:", "search.complete.op.created"))
        add(operator("updated:", "search.complete.op.updated"))
        add(operator("file:", "search.complete.op.file"))
        if (vocabulary.repos.size > 1) add(operator("repo:", "search.complete.op.repo"))
        add(operator(TAG, "search.complete.op.tag"))
    }

    private fun operator(text: String, hintKey: String) = QuerySuggestion(text, text, hintKey = hintKey)

    /** Lo que se ofrece detrás de [key], o `null` si detrás de ese operador no hay nada que ofrecer. */
    private fun valuesOf(key: String, vocabulary: QueryVocabulary): List<QuerySuggestion>? = when (key) {
        "state" -> vocabulary.states.map(::plain)
        "p", "priority" -> vocabulary.priorities.map(::plain)
        "repo" -> vocabulary.repos.map(::plain)
        "is" -> IS.map { (value, hint) -> QuerySuggestion(value, value, hintKey = hint) }
        "has" -> HAS.map { (value, hint) -> QuerySuggestion(value, value, hintKey = hint) }
        in QueryParser.DATE_KEYS -> {
            val due = QueryParser.DATE_KEYS[key] == DateField.DUE
            // Lo que se pregunta de un vencimiento es lo que viene; de lo demás, lo que pasó.
            val named = QueryDates.NAMED.filter { if (due) it != "yesterday" else it != "tomorrow" }
            named.map { QuerySuggestion(it, it, hintKey = "search.complete.date.$it") } +
                QuerySuggestion(DISTANCE, DISTANCE, hintKey = if (due) "search.complete.date.within" else "search.complete.date.last")
        }
        else -> null
    }

    private fun tags(vocabulary: QueryVocabulary): List<QuerySuggestion> =
        vocabulary.tags.map { QuerySuggestion(it.tag, it.tag, hint = it.tasks.toString()) }

    private fun plain(name: String) = QuerySuggestion(name, name)

    /** Un valor con espacios va entre comillas, o el buscador lo partiría en dos. */
    private fun quoted(value: String): String = if (value.any(Char::isWhitespace)) "\"$value\"" else value

    private fun same(a: String, b: String) =
        TextNormalizer.normalize(a.replace("\"", "")) == TextNormalizer.normalize(b.replace("\"", ""))

    private val IS = listOf(
        "open" to "search.complete.is.open",
        "done" to "search.complete.is.done",
        "overdue" to "search.complete.is.overdue",
        "bookmarked" to "search.complete.is.bookmarked",
    )

    private val HAS = listOf(
        "due" to "search.complete.has.due",
        "checklist" to "search.complete.has.checklist",
        "tag" to "search.complete.has.tag",
        "link" to "search.complete.has.link",
        "image" to "search.complete.has.image",
        "code" to "search.complete.has.code",
        "broken-anchor" to "search.complete.has.broken",
    )

    private const val DISTANCE = "<7d"
    private const val TAG = "#"
    private const val NOT = "-"

    /** Letras de una palabra suelta a partir de las cuales se ofrecen operadores sin pedirlo. */
    private const val AUTO_OPERATOR = 2
}
