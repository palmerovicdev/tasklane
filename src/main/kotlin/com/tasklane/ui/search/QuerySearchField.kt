package com.tasklane.ui.search

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.TagCount
import com.tasklane.domain.query.QueryCompletion
import com.tasklane.domain.query.QuerySuggestion
import com.tasklane.domain.query.QueryVocabulary
import com.tasklane.service.TaskService
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * El buscador de la ventana y del tablero, con autocompletado de la consulta (2.21.0).
 *
 * Qué se ofrece en cada momento lo decide [QueryCompletion], que es dominio y tiene sus
 * tests; esto sólo lo enseña y lo inserta. La lista **no se lleva el foco**: se sigue
 * escribiendo en el campo, y las teclas que la manejan —`↑`, `↓`, `Tab`, `Enter`, `Esc`—
 * se le pasan desde [preprocessEventForTextField], que la plataforma llama antes que a
 * cualquier `KeyListener` del campo. Por eso `Esc` cierra la lista sin borrar la búsqueda y
 * `Enter` con algo elegido lo inserta en vez de saltar a las tareas.
 *
 * `Tab` es tecla de foco, y Swing la reparte antes de que llegue a nadie: mientras la lista
 * está abierta el campo deja de usarla para moverse, y al cerrarla la recupera.
 */
internal class QuerySearchField(historyProperty: String, private val project: Project) : SearchTextField(historyProperty) {

    private val model = DefaultListModel<QuerySuggestion>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SuggestionRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (locationToIndex(e.point) >= 0) accept(selectedValue)
            }
        })
    }

    private var popup: JBPopup? = null

    /** Mientras se inserta una sugerencia: el cambio del texto no tiene que volver a abrir nada. */
    private var inserting = false

    /**
     * Las etiquetas se leen al entrar en el campo y no a cada tecla —ver `TaskStore.tagCounts`:
     * recorre las de todo el repositorio—, y fuera del EDT.
     */
    @Volatile
    private var tags: List<TagCount> = emptyList()

    init {
        // El atajo va en el tooltip porque es la única pista de que se pueden pedir los
        // operadores; como en el campo, en una lambda y no con `KeymapUtil::getShortcutText`
        // —ver `TasklanePanel.installSearchField`—.
        val shortcut = completionShortcuts().firstOrNull()?.let { KeymapUtil.getShortcutText(it) }
        textEditor.toolTipText = TasklaneBundle.message("search.tooltip", shortcut ?: FALLBACK_COMPLETION_TEXT)
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // Sólo lo que se teclea aquí: el texto que llega de la otra copia del buscador
                // —tablero o ventana— o del historial no abre sugerencias.
                if (!inserting && textEditor.isFocusOwner) ApplicationManager.getApplication().invokeLater { refresh(explicit = false) }
            }
        })
    }

    override fun preprocessEventForTextField(e: KeyEvent): Boolean {
        if (e.id != KeyEvent.KEY_PRESSED) return super.preprocessEventForTextField(e)
        if (popup?.isVisible != true) {
            if (isCompletionShortcut(e)) {
                refresh(explicit = true)
                return true
            }
            return super.preprocessEventForTextField(e)
        }
        when (e.keyCode) {
            KeyEvent.VK_DOWN -> select(list.selectedIndex + 1)
            KeyEvent.VK_UP -> select(list.selectedIndex - 1)
            KeyEvent.VK_TAB -> accept(list.selectedValue ?: model.firstElement())
            KeyEvent.VK_ESCAPE -> hideSuggestions()
            KeyEvent.VK_ENTER -> {
                val chosen = list.selectedValue
                if (chosen == null) {
                    // Sin nada elegido, `Enter` es el de siempre: ir a la lista de tareas.
                    hideSuggestions()
                    return super.preprocessEventForTextField(e)
                }
                accept(chosen)
            }
            else -> return super.preprocessEventForTextField(e)
        }
        e.consume()
        return true
    }

    /** `↓` sin nada elegido va a la primera; en los extremos se queda donde está. */
    private fun select(index: Int) {
        if (model.isEmpty) return
        list.selectedIndex = index.coerceIn(0, model.size() - 1)
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    override fun onFocusGained() {
        super.onFocusGained()
        val repo = TaskService.getInstance(project).snapshot.value.activeRepo
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed) tags = TaskService.getInstance(project).tagCounts(repo)
        }
    }

    override fun onFocusLost() {
        super.onFocusLost()
        hideSuggestions()
    }

    override fun removeNotify() {
        hideSuggestions()
        super.removeNotify()
    }

    private fun refresh(explicit: Boolean) {
        if (project.isDisposed || !isShowing) return
        val found = QueryCompletion.complete(text, textEditor.caretPosition, vocabulary(), explicit)
            ?: return hideSuggestions()

        model.clear()
        found.suggestions.forEach(model::addElement)
        list.visibleRowCount = found.suggestions.size.coerceAtMost(MAX_ROWS)
        if (found.preselect) list.selectedIndex = 0 else list.clearSelection()
        showSuggestions()
    }

    private fun showSuggestions() {
        val current = popup
        val view = list.preferredScrollableViewportSize
        // Al menos tan ancha como el campo: en la ventana estrecha, la lista cabe debajo sin asomar.
        val size = Dimension(maxOf(view.width + JBUI.scale(8), width), view.height + JBUI.scale(4))
        if (current != null && current.isVisible) {
            current.size = size
            return
        }
        val pane = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            preferredSize = size
        }
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(pane, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(false)
            .setResizable(false)
            .setMovable(false)
            .setAdText(TasklaneBundle.message("search.complete.ad"))
            .createPopup()
        created.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (popup === created) {
                    popup = null
                    textEditor.focusTraversalKeysEnabled = true
                }
            }
        })
        popup = created
        textEditor.focusTraversalKeysEnabled = false
        created.show(RelativePoint(this, Point(0, height)))
    }

    private fun hideSuggestions() {
        val current = popup ?: return
        popup = null
        textEditor.focusTraversalKeysEnabled = true
        current.cancel()
    }

    /**
     * Sustituye el token por la sugerencia. Un valor ya completo lleva un espacio detrás
     * para seguir escribiendo; un operador (`state:`) no, y vuelve a preguntar: lo
     * siguiente que se quiere ver son sus valores.
     */
    private fun accept(suggestion: QuerySuggestion?) {
        if (suggestion == null) return hideSuggestions()
        // Los límites del token se vuelven a medir ahora y no se toman de cuando se ofreció:
        // entre una cosa y otra puede haber llegado otra tecla.
        val (from, to) = QueryCompletion.token(text, textEditor.caretPosition)
        val operator = suggestion.text.endsWith(":") || suggestion.text.endsWith("#")
        val next = text.getOrNull(to)
        val insert = if (operator || next?.isWhitespace() == true) suggestion.text else suggestion.text + " "

        val document = textEditor.document
        inserting = true
        try {
            document.remove(from, to - from)
            document.insertString(from, insert, null)
        } finally {
            inserting = false
        }
        textEditor.caretPosition = from + insert.length + if (!operator && next?.isWhitespace() == true) 1 else 0
        if (operator) refresh(explicit = false) else hideSuggestions()
    }

    private fun vocabulary(): QueryVocabulary {
        val snapshot = TaskService.getInstance(project).snapshot.value
        val config = snapshot.config
        return QueryVocabulary(
            states = config.states.map { it.name },
            priorities = config.priorities.sortedByDescending { it.order }.map { it.name },
            repos = snapshot.repositories.map { it.displayName },
            tags = tags,
        )
    }

    /** El atajo de *Basic Completion* del keymap —`⌃Espacio` de fábrica—, para que pedirlo aquí sea lo mismo que en el editor. */
    private fun isCompletionShortcut(e: KeyEvent): Boolean {
        val strokes = completionShortcuts().map { it.firstKeyStroke }
        return KeyStroke.getKeyStrokeForEvent(e) in strokes.ifEmpty { listOf(FALLBACK_COMPLETION) }
    }

    private fun completionShortcuts(): List<KeyboardShortcut> =
        KeymapManager.getInstance()?.activeKeymap?.getShortcuts(IdeActions.ACTION_CODE_COMPLETION).orEmpty()
            .filterIsInstance<KeyboardShortcut>()
            .filter { it.secondKeyStroke == null }

    private class SuggestionRenderer : ColoredListCellRenderer<QuerySuggestion>() {
        override fun customizeCellRenderer(
            list: JList<out QuerySuggestion>,
            value: QuerySuggestion?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            append(value.label)
            val hint = value.hintKey?.let { TasklaneBundle.message(it) } ?: value.hint
            if (!hint.isNullOrEmpty()) append("  $hint", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private companion object {
        const val MAX_ROWS = 10
        val FALLBACK_COMPLETION: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)
        const val FALLBACK_COMPLETION_TEXT = "Ctrl+Space"
    }
}
