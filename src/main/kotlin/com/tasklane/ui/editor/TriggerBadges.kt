package com.tasklane.ui.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.TagColor
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.TriggerParser
import com.tasklane.domain.text.TriggerParser.Target
import com.tasklane.ui.common.StateIcons
import com.tasklane.ui.common.TagPill
import com.tasklane.ui.toolwindow.KeymapShortcut
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.util.EnumMap
import javax.swing.Icon
import javax.swing.KeyStroke

/**
 * Los triggers del cuerpo del diálogo, hechos **pastilla** (2.30.0): `Revisar el login !!! `
 * deja `Revisar el login` y, donde estaba `!!!`, una pastilla *High* del color de la
 * prioridad; `@doing ` una *Doing* con el círculo del estado. Lo que se escribió pasa a ser lo
 * que significa, y el desplegable de debajo cambia a la vez.
 *
 * La pastilla **no es texto**: es un inlay del editor, y el cuerpo que se guarda no la lleva.
 * Lo que guarda es el desplegable. Por eso cada cosa que la toca deja el desplegable bien:
 *
 * - **Una por tipo.** Otro trigger de prioridad sustituye a la pastilla anterior; uno de
 *   estado convive con ella.
 * - **`⌫` justo detrás, o `Supr` justo delante, la quita** y el desplegable vuelve a lo que
 *   se eligió a mano. El editor por sí solo se saltaría el inlay y borraría la letra de al
 *   lado.
 * - **`⌘Z` deshace el gesto entero**: vuelve el texto del trigger, se va la pastilla y vuelve
 *   la que hubiera antes. Cada cambio de pastilla va como acción deshacible dentro del mismo
 *   comando que borra el texto.
 * - **Elegir a mano en el desplegable** —o con `⌘⌥←/→`— la quita sin más: manda lo último que
 *   se eligió, y una pastilla que dijera otra cosa mentiría. Eso lo pide el diálogo con [drop].
 * - Borrar una selección que la contiene la quita igual que `⌫`.
 *
 * Qué palabra es un trigger lo decide [TriggerParser]; esto es sólo el editor.
 */
internal class TriggerBadges(
    private val project: Project,
    private val config: TasklaneConfig,
    /** `body` y no `field`, que dentro de un accesor nombra el campo de respaldo. */
    private val body: MarkdownField,
    private val parent: Disposable,
    /** Lo que eligió la pastilla de [Kind], o `null` al quitarla: vuelta a lo elegido a mano. */
    private val onChange: (Kind, Target?) -> Unit,
) {

    enum class Kind { PRIORITY, STATE }

    /**
     * Una pastilla y dónde está. [offset] se lleva aquí y no se lee del inlay: el inlay es del
     * editor, y `EditorTextField` tira el editor y crea otro si el campo sale y vuelve a la
     * jerarquía. Entonces se repinta en el mismo sitio. Ver [attach].
     */
    private class Badge(val target: Target, var offset: Int) {
        var inlay: Inlay<*>? = null
    }

    private val badges = EnumMap<Kind, Badge>(Kind::class.java)

    private val document: Document get() = body.component.document

    init {
        document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    follow(event)
                    val typed = event.newFragment
                    if (event.oldLength != 0 || typed.length != 1 || !typed[0].isWhitespace()) return
                    // Deshacer vuelve a poner texto, y no es que se esté escribiendo.
                    if (UndoManager.getInstance(project).isUndoOrRedoInProgress) return
                    val offset = event.offset
                    // El documento no se puede tocar desde dentro de su propio evento.
                    ApplicationManager.getApplication().invokeLater(
                        { takeTriggerBefore(offset, typed[0]) },
                        ModalityState.stateForComponent(body.component),
                    )
                }
            },
            parent,
        )
        body.onEditor(::attach)
    }

    /** Quita la pastilla de [kind] sin tocar el desplegable: es él quien acaba de cambiar. */
    fun drop(kind: Kind) {
        badges.remove(kind)?.let(::hide)
    }

    /**
     * Mueve las pastillas con el texto, como lo hace el inlay —que se queda pegado a lo de
     * delante—, y quita la que se iba dentro de un trozo borrado.
     */
    private fun follow(event: DocumentEvent) {
        val start = event.offset
        val end = start + event.oldLength
        badges.entries.toList().forEach { (kind, badge) ->
            when {
                badge.offset > end || (badge.offset == end && event.oldLength > 0) ->
                    badge.offset += event.newLength - event.oldLength

                badge.offset > start -> {
                    badge.offset = start
                    // Fuera del evento: deshacerse de un inlay mientras el editor aún está
                    // recolocando los suyos no es seguro.
                    ApplicationManager.getApplication().invokeLater(
                        { if (badges[kind] === badge) put(kind, null) },
                        ModalityState.stateForComponent(body.component),
                    )
                }
            }
        }
    }

    private fun takeTriggerBefore(offset: Int, separator: Char) {
        val text = document.text
        // Lo que se tecleó después ya pudo moverlo todo: sólo si el separador sigue ahí.
        if (text.getOrNull(offset) != separator) return
        val match = TriggerParser.endingAt(text, offset, config) ?: return
        val (first, last) = TriggerParser.cut(text, match)
        val kind = kindOf(match.target)
        val badge = Badge(match.target, first)
        WriteCommandAction.writeCommandAction(project)
            .withName(TasklaneBundle.message(if (kind == Kind.PRIORITY) PRIORITY_COMMAND else STATE_COMMAND))
            .run<RuntimeException> {
                document.deleteString(first, last)
                swap(kind, badge)
            }
        // El cursor detrás de la pastilla, que es donde se sigue escribiendo. Si el separador
        // fue un salto de línea, el cursor ya está en la línea siguiente y se queda.
        val editor = body.component.editor ?: return
        val inlay = badge.inlay ?: return
        if (editor.caretModel.offset == first) {
            editor.caretModel.moveToVisualPosition(inlay.visualPosition.let { VisualPosition(it.line, it.column + 1) })
        }
    }

    /** Quitarla con el teclado: con su comando, para que `⌘Z` la devuelva. */
    private fun remove(kind: Kind) {
        WriteCommandAction.writeCommandAction(project)
            .withName(TasklaneBundle.message(REMOVE_COMMAND))
            .run<RuntimeException> { swap(kind, null) }
    }

    /** Pone [badge] en el sitio de [kind] dentro del comando en curso, deshacible. */
    private fun swap(kind: Kind, badge: Badge?) {
        val before = badges[kind]
        UndoManager.getInstance(project).undoableActionPerformed(
            object : BasicUndoableAction(document) {
                override fun undo() = put(kind, before)

                override fun redo() = put(kind, badge)
            },
        )
        put(kind, badge)
    }

    private fun put(kind: Kind, badge: Badge?) {
        badges.remove(kind)?.let(::hide)
        if (badge != null) {
            badges[kind] = badge
            show(badge)
        }
        onChange(kind, badge?.target)
    }

    private fun show(badge: Badge) {
        val editor = body.component.editor ?: return
        val offset = badge.offset.coerceIn(0, editor.document.textLength)
        // Pegada a lo de delante: lo que se escriba justo ahí va detrás de la pastilla.
        badge.inlay = editor.inlayModel.addInlineElement(offset, true, renderer(badge.target))
    }

    private fun hide(badge: Badge) {
        badge.inlay?.let(Disposer::dispose)
        badge.inlay = null
    }

    /**
     * Cada editor que crea el campo: las pastillas se repintan en él, y `⌫`/`Supr` aprenden a
     * quitarlas. Con el atajo del Keymap, que es el que espera quien lo haya movido.
     */
    private fun attach(editor: EditorEx) {
        badges.values.forEach { it.inlay = null }
        badges.values.forEach(::show)
        removal(editor, IdeActions.ACTION_EDITOR_BACKSPACE, KeyEvent.VK_BACK_SPACE, behind = true)
        removal(editor, IdeActions.ACTION_EDITOR_DELETE, KeyEvent.VK_DELETE, behind = false)
    }

    /**
     * Apagada si el cursor no está pegado a una pastilla por ese lado: entonces la tecla sigue
     * su camino y borra texto, como siempre.
     */
    private fun removal(editor: EditorEx, actionId: String, key: Int, behind: Boolean) {
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = beside(editor, behind) != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                beside(editor, behind)?.let(::remove)
            }
        }.registerCustomShortcutSet(
            KeymapShortcut(actionId, CustomShortcutSet(KeyStroke.getKeyStroke(key, 0))),
            editor.contentComponent,
            parent,
        )
    }

    /**
     * La pastilla pegada al cursor: justo detrás de él si [behind], justo delante si no. Un
     * inlay en línea ocupa una columna visual, así que es mirar la de al lado.
     */
    private fun beside(editor: Editor, behind: Boolean): Kind? {
        val carets = editor.caretModel
        if (carets.caretCount != 1 || carets.currentCaret.hasSelection()) return null
        val caret = carets.visualPosition
        val at = if (!behind) caret else if (caret.column > 0) VisualPosition(caret.line, caret.column - 1) else return null
        val inlay = editor.inlayModel.getInlineElementAt(at) ?: return null
        return badges.entries.firstOrNull { it.value.inlay === inlay }?.key
    }

    private fun renderer(target: Target): EditorCustomElementRenderer = when (target) {
        is Target.Priority -> {
            val priority = config.priorityOrDefault(target.id)
            val color = TagColor(priority.colorLight, priority.colorDark)
            BadgeRenderer(priority.name, null, TagPill.fill(color), TagPill.text(color))
        }

        is Target.State -> {
            val state = config.stateOrDefault(target.id)
            BadgeRenderer(state.name, StateIcons.of(state, config.states), TagPill.fill(null), TagPill.text(null))
        }
    }

    private fun kindOf(target: Target): Kind = when (target) {
        is Target.Priority -> Kind.PRIORITY
        is Target.State -> Kind.STATE
    }

    private companion object {
        const val PRIORITY_COMMAND = "dialog.task.trigger.priority.command"
        const val STATE_COMMAND = "dialog.task.trigger.state.command"
        const val REMOVE_COMMAND = "dialog.task.trigger.remove.command"
    }
}

/**
 * La pastilla: la de las etiquetas de la tarjeta y del campo de fichas ([TagPill]), con la
 * letra pequeña de las fichas y, si la hay, un icono delante.
 */
private class BadgeRenderer(
    private val text: String,
    private val icon: Icon?,
    private val fill: Color,
    private val foreground: Color,
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val iconWidth = icon?.let { it.iconWidth + JBUI.scale(GAP) } ?: 0
        return 2 * JBUI.scale(MARGIN) + 2 * JBUI.scale(PADDING) + iconWidth + metrics(inlay.editor).stringWidth(text)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, region: Rectangle, attributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val editor = inlay.editor
            val metrics = metrics(editor)
            val margin = JBUI.scale(MARGIN)
            val height = minOf(region.height, maxOf(metrics.height, icon?.iconHeight ?: 0) + JBUI.scale(2))
            val top = region.y + (region.height - height) / 2
            val arc = JBUI.scale(ARC)
            g2.color = fill
            g2.fillRoundRect(region.x + margin, top, region.width - 2 * margin, height, arc, arc)

            var x = region.x + margin + JBUI.scale(PADDING)
            icon?.let {
                it.paintIcon(editor.contentComponent, g2, x, top + (height - it.iconHeight) / 2)
                x += it.iconWidth + JBUI.scale(GAP)
            }
            g2.font = font()
            g2.color = foreground
            g2.drawString(text, x, top + (height - metrics.height) / 2 + metrics.ascent)
        } finally {
            g2.dispose()
        }
    }

    private fun font(): Font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)

    private fun metrics(editor: Editor) = editor.contentComponent.getFontMetrics(font())

    private companion object {
        /** Entre la pastilla y el texto de cada lado: que se lea como una palabra más. */
        const val MARGIN = 2
        const val PADDING = 6
        const val GAP = 3

        /** El de las fichas del diálogo, ver [Chip]. */
        const val ARC = 10
    }
}
