package com.tasklane.ui.editor

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.ui.JBColor
import com.intellij.util.textCompletion.TextCompletionProvider
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.TagColor
import com.tasklane.domain.model.TagCount
import com.tasklane.domain.text.TagParser
import java.util.Locale

/**
 * Las etiquetas que ya existen en el repositorio, sugeridas mientras se escribe una
 * (P30), con cuántas tareas lleva cada una. Es lo que evita que nazcan `#api` y `#apis`:
 * quien empieza a escribir `ap` ve que ya hay una con doce tareas.
 *
 * Las más usadas arriba, y antes las que **empiezan** como lo escrito que las que sólo
 * lo contienen. No sale ninguna de las que la tarea ya lleva.
 *
 * [load] se llama una vez, la primera vez que hace falta y fuera del EDT —la lista la
 * pide la plataforma en segundo plano—: son las etiquetas del repositorio con su
 * cuenta, que no es un salto de índice. Elegir una la convierte en ficha con [onPicked].
 */
internal class TagCompletion(
    load: () -> List<TagCount>,
    private val taken: () -> Collection<String>,
    private val colorOf: (String) -> TagColor?,
    private val onPicked: () -> Unit,
) : TextCompletionProvider {

    private val tags by lazy(load)

    override fun getAdvertisement(): String? = null

    /** Sin la almohadilla: quien copia `#api` de otro sitio también tiene que ver `api`. */
    override fun getPrefix(text: String, offset: Int): String = text.substring(0, offset).removePrefix("#")

    override fun applyPrefixMatcher(result: CompletionResultSet, prefix: String): CompletionResultSet =
        result.withPrefixMatcher(PlainPrefixMatcher(prefix, false))

    /** Lo que separa etiquetas cierra la lista: lo escrito se queda tal cual, como ficha. */
    override fun acceptChar(c: Char): CharFilter.Result =
        if (TagParser.isSeparator(c)) CharFilter.Result.HIDE_LOOKUP else CharFilter.Result.ADD_TO_PREFIX

    override fun fillCompletionVariants(parameters: CompletionParameters, prefix: String, result: CompletionResultSet) {
        val skip = taken().mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        for (count in tags) {
            if (count.tag.lowercase(Locale.ROOT) in skip) continue
            result.addElement(PrioritizedLookupElement.withPriority(element(count), weight(count, prefix)))
        }
    }

    private fun element(count: TagCount): LookupElement {
        val builder = LookupElementBuilder.create(count.tag)
            .withPresentableText("#${count.tag}")
            .withTypeText(TasklaneBundle.message("dialog.task.tags.count", count.tasks), true)
            .withInsertHandler(picked)
        val color = colorOf(count.tag) ?: return builder
        return builder.withIcon(ColorIcon(JBUI.scale(DOT), JBColor(color.light, color.dark)))
    }

    /** Elegida, ficha: no hace falta escribir la coma detrás. */
    private val picked = InsertHandler<LookupElement> { context, _ -> context.laterRunnable = Runnable(onPicked) }

    private fun weight(count: TagCount, prefix: String): Double =
        (if (count.tag.startsWith(prefix, ignoreCase = true)) STARTS else 0.0) + count.tasks

    private companion object {
        const val STARTS = 1_000_000.0
        const val DOT = 8
    }
}
