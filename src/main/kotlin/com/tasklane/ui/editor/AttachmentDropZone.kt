package com.tasklane.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * La franja de «suelta una imagen aquí» bajo la barra de formato.
 *
 * Existe porque adjuntar sólo se podía pegando, y eso no se descubre: no hay ningún
 * sitio en el diálogo que lo diga. Una zona con borde discontinuo sí se reconoce sin
 * explicación, y de paso hace de botón — un clic abre el selector de ficheros, que es
 * lo que hace quien tiene la imagen guardada y no en el portapapeles.
 *
 * Es una franja baja y no el rectángulo grande del boceto: el cuerpo de la tarea es
 * lo que importa en este diálogo, y una caja de adjuntos de cien píxeles le robaría
 * la mitad del sitio para algo que la mayoría de tareas no usa.
 *
 * El `DropTarget` va a pelo en vez de por `TransferHandler` porque hace falta saber
 * cuándo el ratón **entra y sale** con algo encima para poder iluminarla, y eso el
 * `TransferHandler` no lo cuenta.
 */
internal class AttachmentDropZone(
    private val onFiles: (List<File>) -> Unit,
    private val onClick: () -> Unit,
) : JPanel(BorderLayout()) {

    private var dragging = false

    private val label = JBLabel(
        TasklaneBundle.message("dialog.task.attach.hint"),
        AllIcons.FileTypes.Image,
        SwingConstants.CENTER,
    ).apply {
        font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(PADDING)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(label, BorderLayout.CENTER)

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) onClick()
            }
        })

        dropTarget = DropTarget(
            this,
            DnDConstants.ACTION_COPY,
            object : DropTargetAdapter() {
                override fun dragEnter(event: DropTargetDragEvent) = highlight(true)

                override fun dragExit(event: DropTargetEvent) = highlight(false)

                override fun drop(event: DropTargetDropEvent) {
                    highlight(false)
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    val files = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                    }.getOrNull()
                    event.dropComplete(true)
                    // Se filtra aquí y no en quien adjunta: soltar un PDF encima no es
                    // un error que merezca un aviso, simplemente no era una imagen.
                    files?.filter(ImageInserter::isImage)?.takeIf { it.isNotEmpty() }?.let(onFiles)
                }
            },
        )
    }

    private fun highlight(value: Boolean) {
        if (dragging == value) return
        dragging = value
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(ARC).toFloat()
            g2.stroke = BasicStroke(
                JBUI.scale(1).toFloat(),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                0f,
                floatArrayOf(JBUI.scale(DASH).toFloat(), JBUI.scale(DASH).toFloat()),
                0f,
            )
            g2.color = if (dragging) JBColor.namedColor("Link.activeForeground", JBColor.BLUE) else borderColor()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc.toInt(), arc.toInt())
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun borderColor() = JBColor.namedColor("Component.borderColor", JBColor.GRAY)

    private companion object {
        const val PADDING = 4
        const val ARC = 8
        const val DASH = 3
    }
}
