package com.tasklane.ui.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.TagParser
import com.tasklane.service.TaskService
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Las etiquetas de una tarea como fichas: cada una con su aspa, y un hueco al final
 * donde se escribe la siguiente.
 *
 * Era la deuda más gorda del rediseño y se pagó entera porque el campo con comas
 * mentía sobre el modelo: se veía **un** texto donde hay una **lista**, y borrar una
 * etiqueta de en medio obligaba a cazar la coma correcta. Con fichas, quitar una es
 * pulsar su aspa.
 *
 * Lo que **no** trae, y es deliberado: no hay navegación con flechas entre fichas ni
 * foco en cada una. El foco vive siempre en el campo de escribir, que es donde se
 * teclea; retroceso sobre el campo vacío se lleva la última, que es el gesto que de
 * verdad se usa. Un control de fichas con foco propio por ficha es otro proyecto, y
 * éste ya cubre lo que el boceto pedía.
 *
 * El texto a medio escribir **cuenta como etiqueta** al leer [tags]. Aceptar el
 * diálogo con `api` escrito y sin cerrar no puede perderlo: nadie entiende que una
 * etiqueta que está viendo escrita no se guarde.
 *
 * Desde la P30 el hueco **sugiere** las etiquetas que ya existen en [repo], con cuántas
 * tareas lleva cada una —ver [TagCompletion]—, y las fichas llevan el color de la
 * etiqueta si lo tiene. Por eso el hueco es un campo de editor de la plataforma, como el
 * de las anclas, y sus teclas son acciones: ver [AnchorChipsField].
 */
internal class TagChipsField(
    project: Project,
    repo: RepoKey,
    initial: List<String>,
    private val config: TasklaneConfig,
    parentDisposable: Disposable,
) : JPanel(ChipsLayout(stretchLast = true)) {

    /** Volátil: la lista de sugerencias la lee fuera del EDT para no ofrecer las que ya están. */
    @Volatile
    private var chips: List<String> = TagParser.parse(initial.joinToString(","))

    private val editor = TextFieldWithCompletion(
        project,
        TagCompletion(
            load = { TaskService.getInstance(project).tagCounts(repo) },
            taken = { chips },
            colorOf = config::tagColor,
            onPicked = ::commitAll,
        ),
        "",
        true,
        true,
        false,
    ).apply {
        border = JBUI.Borders.empty()
    }

    val tags: List<String> get() = TagParser.add(chips, editor.text)

    init {
        isOpaque = true
        background = UIUtil.getTextFieldBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.namedColor("Component.borderColor", JBColor.GRAY), 1),
            JBUI.Borders.empty(PADDING),
        )
        // Pulsar el fondo escribe: el hueco entre fichas es parte del campo, no un
        // sitio muerto.
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                editor.requestFocusInWindow()
            }
        })

        editor.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // El documento no se puede tocar desde dentro de su propio evento, y
                // cambiarlo es una escritura: la cola de la aplicación y no la de Swing.
                ApplicationManager.getApplication().invokeLater(
                    ::commitTyped,
                    ModalityState.stateForComponent(this@TagChipsField),
                )
            }
        })
        // Retroceso con el campo vacío se lleva la última ficha. Es el gesto que todo el
        // mundo prueba primero. Apagada en cualquier otro caso, la tecla borra texto.
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = editor.text.isEmpty() && chips.isNotEmpty()
            }

            override fun actionPerformed(e: AnActionEvent) {
                chips = chips.dropLast(1)
                rebuild()
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0)),
            editor,
            parentDisposable,
        )
        rebuild()
    }

    /**
     * Cierra en fichas lo que ya lleve separador y deja en el campo lo que se siga
     * escribiendo. Se hace al escribir y no sólo al pulsar Intro porque Intro es del
     * diálogo —acepta— y robárselo aquí sorprendería más de lo que ayuda.
     */
    private fun commitTyped() {
        val text = editor.text
        val cut = text.indexOfLast(TagParser::isSeparator) + 1
        if (cut <= 0) return

        val closed = TagParser.parse(text.take(cut))
        editor.text = text.substring(cut)
        if (closed.isEmpty()) return
        chips = TagParser.parse((chips + closed).joinToString(","))
        rebuild()
    }

    /** Una sugerencia elegida: lo que hay escrito ya es la etiqueta entera. */
    private fun commitAll() {
        val closed = TagParser.parse(editor.text)
        editor.text = ""
        if (closed.isEmpty()) return
        chips = TagParser.parse((chips + closed).joinToString(","))
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        chips.forEach { tag -> add(chipFor(tag)) }
        editor.setPlaceholder(if (chips.isEmpty()) TasklaneBundle.message("dialog.task.tags.hint") else null)
        add(editor)
        revalidate()
        repaint()
        // Sólo si ya se está viendo: en el constructor esto correría antes de que el
        // diálogo reparta el foco, y la primera pulsación acabaría aquí en vez de en
        // el cuerpo de la tarea.
        if (isShowing) editor.requestFocusInWindow()
    }

    private fun chipFor(tag: String) = Chip(
        text = tag,
        removeTooltip = TasklaneBundle.message("dialog.task.tags.remove", tag),
        foreground = config.tagColor(tag)?.let { JBColor(it.light, it.dark) },
    ) {
        chips = chips.filterNot { it == tag }
        rebuild()
    }

    private companion object {
        const val PADDING = 3
    }
}
