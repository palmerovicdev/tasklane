package com.tasklane.domain.text

import com.tasklane.domain.model.TagCount
import java.util.Locale

/** Context and candidates for completing an inline tag in the task body. */
internal object InlineTagCompletion {
    data class Prefix(val start: Int, val end: Int, val typed: String)

    fun at(text: String, caret: Int): Prefix? {
        if (caret !in 0..text.length) return null
        var start = caret
        while (start > 0 && !text[start - 1].isWhitespace() && !TagParser.isSeparator(text[start - 1])) start--
        if (text.getOrNull(start) != '#' || caret <= start) return null
        if (start > 0 && !text[start - 1].isWhitespace()) return null
        if (TypedWord.inCode(text, start)) return null
        var end = caret
        while (end < text.length && !text[end].isWhitespace() && !TagParser.isSeparator(text[end])) end++
        val word = text.substring(start + 1, end)
        if (!word.all { it.isLetterOrDigit() || it in "-_/" }) return null
        return Prefix(start, end, text.substring(start + 1, caret))
    }

    fun isTypedCharacter(char: Char): Boolean = char >= ' ' && char != '\u007f'

    fun suggestions(tags: List<TagCount>, prefix: String, taken: Collection<String>): List<TagCount> {
        val excluded = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        return tags.asSequence()
            .filter { it.tag.lowercase(Locale.ROOT) !in excluded }
            .filter { InlineTag.trailing("#${it.tag}") != null }
            .filter { it.tag.contains(prefix, ignoreCase = true) }
            .sortedWith(compareByDescending<TagCount> { it.tag.startsWith(prefix, ignoreCase = true) }
                .thenByDescending { it.tasks })
            .toList()
    }
}
