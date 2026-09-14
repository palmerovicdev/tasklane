package com.tasklane.ui.editor

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.text.TagParser
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

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
 */
internal class TagChipsField(initial: List<String>) : JPanel(ChipsLayout(stretchLast = true)) {

    private var chips: List<String> = TagParser.parse(initial.joinToString(","))

    private val editor = JBTextField().apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        columns = COLUMNS
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

        editor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // El documento no se puede tocar desde dentro de su propio evento.
                SwingUtilities.invokeLater(::commitTyped)
            }
        })
        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_BACK_SPACE || editor.text.isNotEmpty() || chips.isEmpty()) return
                // Retroceso con el campo vacío se lleva la última ficha. Es el gesto
                // que todo el mundo prueba primero.
                e.consume()
                chips = chips.dropLast(1)
                rebuild()
            }
        })
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

    private fun rebuild() {
        removeAll()
        chips.forEach { tag -> add(chipFor(tag)) }
        editor.emptyText.text = if (chips.isEmpty()) TasklaneBundle.message("dialog.task.tags.hint") else ""
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
    ) {
        chips = chips.filterNot { it == tag }
        rebuild()
    }

    private companion object {
        const val PADDING = 3
        const val COLUMNS = 8
    }
}
