package com.tasklane.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.domain.text.TagParser
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
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
internal class TagChipsField(initial: List<String>) : JPanel(ChipsLayout()) {

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
        chips.forEach { add(Chip(it)) }
        editor.emptyText.text = if (chips.isEmpty()) TasklaneBundle.message("dialog.task.tags.hint") else ""
        add(editor)
        revalidate()
        repaint()
        // Sólo si ya se está viendo: en el constructor esto correría antes de que el
        // diálogo reparta el foco, y la primera pulsación acabaría aquí en vez de en
        // el cuerpo de la tarea.
        if (isShowing) editor.requestFocusInWindow()
    }

    private inner class Chip(private val tag: String) : JPanel(BorderLayout(JBUI.scale(GAP), 0)) {

        init {
            isOpaque = false
            border = JBUI.Borders.empty(1, CHIP_PADDING, 1, 2)
            add(
                JBLabel(tag).apply { font = UIUtil.getFont(UIUtil.FontSize.SMALL, font) },
                BorderLayout.CENTER,
            )
            add(closeButton(), BorderLayout.EAST)
        }

        private fun closeButton() = JBLabel(AllIcons.Actions.Close).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = TasklaneBundle.message("dialog.task.tags.remove", tag)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    icon = AllIcons.Actions.CloseHovered
                }

                override fun mouseExited(e: MouseEvent) {
                    icon = AllIcons.Actions.Close
                }

                override fun mousePressed(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    chips = chips.filterNot { it == tag }
                    rebuild()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                g2.color = JBUI.CurrentTheme.ActionButton.pressedBackground()
                val arc = JBUI.scale(ARC)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private companion object {
        const val GAP = 4
        const val PADDING = 3
        const val CHIP_PADDING = 6
        const val ARC = 10
        const val COLUMNS = 8
        const val MIN_EDITOR = 60
    }

    /**
     * Coloca las fichas de izquierda a derecha y salta de línea cuando no caben. El
     * último hijo —el campo de escribir— se queda con lo que sobre de su fila.
     *
     * No es `FlowLayout` porque éste no sabe decir cuánto mide envolviendo: preguntado
     * por su tamaño preferido contesta el de una sola fila, y el campo se quedaría con
     * la mitad de las fichas fuera en cuanto hubiera unas cuantas.
     */
    private class ChipsLayout : LayoutManager {

        override fun addLayoutComponent(name: String?, comp: Component) = Unit

        override fun removeLayoutComponent(comp: Component) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension = measure(parent)

        override fun minimumLayoutSize(parent: Container): Dimension = measure(parent)

        override fun layoutContainer(parent: Container) {
            val insets = parent.insets
            val gap = JBUI.scale(GAP)
            val limit = parent.width - insets.right
            val children = parent.components.filter { it.isVisible }

            var x = insets.left
            var y = insets.top
            var rowHeight = 0
            children.forEachIndexed { index, child ->
                val size = child.preferredSize
                val last = index == children.lastIndex
                val needed = if (last) JBUI.scale(MIN_EDITOR) else size.width
                if (x > insets.left && x + needed > limit) {
                    x = insets.left
                    y += rowHeight + gap
                    rowHeight = 0
                }
                val width = if (last) (limit - x).coerceAtLeast(JBUI.scale(MIN_EDITOR)) else size.width
                child.setBounds(x, y, width, size.height)
                x += width + gap
                rowHeight = maxOf(rowHeight, size.height)
            }
        }

        private fun measure(parent: Container): Dimension {
            val insets = parent.insets
            val gap = JBUI.scale(GAP)
            val children = parent.components.filter { it.isVisible }
            if (children.isEmpty()) return Dimension(insets.left + insets.right, insets.top + insets.bottom)

            // Sin ancho todavía —la primera medida— se supone una sola fila; cuando el
            // contenedor ya tiene tamaño, `rebuild` revalida y la cuenta sale bien.
            val available = (parent.width - insets.left - insets.right).takeIf { it > 0 } ?: Int.MAX_VALUE
            var x = 0
            var widest = 0
            var rows = 1
            var rowHeight = 0
            for (child in children) {
                val size = child.preferredSize
                if (x > 0 && x + size.width > available) {
                    widest = maxOf(widest, x - gap)
                    rows++
                    x = 0
                }
                x += size.width + gap
                rowHeight = maxOf(rowHeight, size.height)
            }
            widest = maxOf(widest, x - gap)
            return Dimension(
                minOf(widest, available) + insets.left + insets.right,
                rows * rowHeight + (rows - 1) * gap + insets.top + insets.bottom,
            )
        }
    }
}
