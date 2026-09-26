package com.tasklane.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.tasklane.code.CodeAnchors
import com.tasklane.domain.model.AnchorSnippet
import com.tasklane.domain.model.CodeAnchor

/**
 * Lo único que el renderer necesita saber del código de un ancla: si ya está, cuál es.
 *
 * Interfaz por lo mismo que [CardPreviews]: la implementación de verdad necesita el IDE,
 * y sin este hueco la tarjeta no se podría medir en un test normal.
 */
internal fun interface CodeSnippets {
    /** El fragmento de [anchor], o `null` mientras se lee o si no hay de dónde. **Nunca** va al disco. */
    fun snippetOf(anchor: CodeAnchor): AnchorSnippet?
}

/**
 * De dónde saca la lista el código de los bloques anclados (2.15.0): el que pinta la
 * tarjeta desplegada y el que enseña el tooltip de la ficha del ancla.
 *
 * La misma frontera que [CardImages] pone con el disco: el renderer corre en cada
 * repintado y no puede abrir un fichero, así que pregunta aquí; lo que no está se lee en
 * segundo plano y, al llegar, se avisa con [onChanged] para rehacer alturas.
 *
 * **Lo leído se enseña hasta que llega lo nuevo.** Cuando algo puede haberlo cambiado
 * —el modelo, con [refresh]; el propio fichero, al editarlo— lo guardado se marca como
 * viejo y se vuelve a leer, pero se sigue devolviendo mientras tanto. Olvidarlo sin más
 * haría que el bloque desapareciera y volviera a aparecer —la tarjeta encogiendo y
 * creciendo— cada vez que se marca una casilla de esa misma tarea. Y [onChanged] sólo
 * se llama si el código es **otro**: releer lo mismo no mueve nada.
 *
 * **Sigue al editor.** Escribir en un fichero con bloques anclados marca los suyos como
 * viejos y pide repintar; la tarjeta desplegada enseña el código de ahora, no el del
 * momento en que se desplegó.
 */
internal class CardSnippets(
    private val project: Project,
    parent: Disposable,
    private val onChanged: () -> Unit,
    private val repaint: () -> Unit,
) : CodeSnippets {

    /**
     * Quien quiere saber que llegó uno, cambie o no: el tooltip de la ficha, que se
     * compuso sin él y tiene que rehacerse si el ratón sigue encima. Ver [RowClicks].
     */
    var onLoaded: ((CodeAnchor) -> Unit)? = null

    /**
     * Por ancla, que es un `data class`: la misma ruta con otras líneas es otro bloque.
     * Acotado, por lo mismo que las tarjetas desplegadas: sólo se piden las que se ven.
     */
    private val cache = object : LinkedHashMap<CodeAnchor, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<CodeAnchor, Entry>): Boolean = size > MAX_ENTRIES
    }
    private val loading = HashSet<CodeAnchor>()

    /** Los que se marcaron como viejos **mientras** se leían: lo que llegue ya no vale. */
    private val stale = HashSet<CodeAnchor>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = changed(event.document)
            },
            parent,
        )
    }

    override fun snippetOf(anchor: CodeAnchor): AnchorSnippet? {
        val entry = cache[anchor]
        if (entry == null || !entry.fresh) load(anchor)
        return entry?.snippet
    }

    /** Todo lo guardado se vuelve a leer la próxima vez que se pida, sin dejar de enseñarse. */
    fun refresh() {
        for (entry in cache.values) entry.fresh = false
        stale += loading
    }

    private fun changed(document: Document) {
        if (cache.isEmpty() && loading.isEmpty()) return
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val path = CodeAnchors.pathOf(project, file)
        var any = false
        for ((anchor, entry) in cache) {
            if (anchor.path != path) continue
            entry.fresh = false
            any = true
        }
        loading.filterTo(stale) { it.path == path }
        // Nada que pida el renderer se va a enterar solo: pedirle que vuelva a pintar es
        // lo que hace que pregunte, y preguntar es lo que lo vuelve a leer.
        if (any) repaint()
    }

    private fun load(anchor: CodeAnchor) {
        if (!loading.add(anchor)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val snippet = runCatching { CodeAnchors.snippetOf(project, anchor) }.getOrNull()
            ApplicationManager.getApplication().invokeLater(
                {
                    loading -= anchor
                    val before = cache[anchor]
                    val fresh = !stale.remove(anchor)
                    cache[anchor] = Entry(snippet, fresh)
                    // Sin nada antes y sin nada ahora —un fichero que no está— no ha cambiado nada.
                    if (before?.snippet != snippet) onChanged()
                    onLoaded?.invoke(anchor)
                    // Lo que se editó mientras se leía: otra vuelta, que ya no la pide nadie.
                    if (!fresh) load(anchor)
                },
                // `any()` por lo mismo que en [CardImages]: sin ella la respuesta esperaría
                // detrás de cualquier diálogo abierto.
                ModalityState.any(),
            )
        }
    }

    private class Entry(val snippet: AnchorSnippet?, var fresh: Boolean)

    private companion object {
        const val MAX_ENTRIES = 64
    }
}
