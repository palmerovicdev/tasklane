package com.tasklane.ui.editor

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.textCompletion.TextCompletionProvider
import com.tasklane.code.CodeAnchors

/**
 * Autocompletado de rutas del proyecto para el campo de anclas (2.8.0): se escribe
 * `deploy` y se ofrece `plans/website_deployment_live_activity_implementation.md`.
 *
 * Se busca **por nombre de fichero** en el índice de la plataforma y se filtra después
 * por la ruta entera. El índice de nombres es lo único barato de recorrer en un
 * proyecto grande: iterar el contenido del proyecto en cada tecla sería leer el árbol
 * de directorios entero. Por lo mismo hay topes —[MAX_NAMES], [MAX_RESULTS]— en vez de
 * la promesa de enseñarlo todo: una lista de cinco mil rutas no ayuda a elegir.
 *
 * **Se calla** en cuanto lo escrito lleva la línea (`…:28`): ahí ya no hay ruta que
 * completar. Y durante la indexación, que es cuando el índice no se puede leer.
 */
internal class ProjectPathCompletion(private val project: Project) : TextCompletionProvider {

    override fun getAdvertisement(): String? = null

    override fun getPrefix(text: String, offset: Int): String = text.substring(0, offset)

    override fun applyPrefixMatcher(result: CompletionResultSet, prefix: String): CompletionResultSet =
        result.withPrefixMatcher(PlainPrefixMatcher(prefix, false))

    /** Todo lo que cabe en una ruta sigue filtrando; un espacio cierra la lista. */
    override fun acceptChar(c: Char): CharFilter.Result =
        if (c.isWhitespace()) CharFilter.Result.HIDE_LOOKUP else CharFilter.Result.ADD_TO_PREFIX

    override fun fillCompletionVariants(parameters: CompletionParameters, prefix: String, result: CompletionResultSet) {
        val typed = prefix.trim().replace('\\', '/').removePrefix("./")
        if (typed.isEmpty() || LINE.containsMatchIn(typed)) return
        if (DumbService.isDumb(project)) return

        val query = typed.substringAfterLast('/')
        val scope = GlobalSearchScope.projectScope(project)
        val names = ArrayList<String>()
        FilenameIndex.processAllFileNames(
            { name ->
                if (name.contains(query, ignoreCase = true)) names += name
                names.size < MAX_NAMES
            },
            scope,
            null,
        )

        val out = result.withPrefixMatcher(PlainPrefixMatcher(typed, false))
        var added = 0
        for (name in names) {
            ProgressManager.checkCanceled()
            for (file in FilenameIndex.getVirtualFilesByName(name, scope)) {
                if (file.isDirectory) continue
                val path = CodeAnchors.pathOf(project, file)
                if (!path.contains(typed, ignoreCase = true)) continue
                val element = LookupElementBuilder.create(path)
                    .withLookupString(file.name)
                    .withPresentableText(file.name)
                    .withTailText("  ${path.substringBeforeLast('/', "")}", true)
                    .withIcon(file.fileType.icon)
                out.addElement(PrioritizedLookupElement.withPriority(element, priorityOf(path, file.name, typed, query)))
                if (++added >= MAX_RESULTS) return
            }
        }
    }

    /**
     * Arriba lo que **empieza** como se escribió —la ruta entera, o si no el nombre—, y
     * a igualdad lo más corto: quien escribe `src/Main` quiere `src/Main.kt` antes que
     * `src/test/…/MainTest.kt`.
     */
    private fun priorityOf(path: String, name: String, typed: String, query: String): Double {
        var score = 0.0
        if (path.startsWith(typed, ignoreCase = true)) score += 2_000
        if (name.startsWith(query, ignoreCase = true)) score += 1_000
        return score - path.length
    }

    private companion object {
        /** Lo escrito ya lleva la línea: `ruta:28`, `ruta#L28`, `ruta(28`. */
        val LINE = Regex("""(:\d*$)|(#L\d*$)|(\(\d*$)""")

        const val MAX_NAMES = 2_000
        const val MAX_RESULTS = 100
    }
}
