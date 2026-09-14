package com.tasklane.ui.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.tasklane.domain.model.TaskId
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree

/**
 * Un punto dentro del texto de una tarjeta: la línea **pintada** y el carácter
 * dentro de ella.
 *
 * Se cuenta por líneas pintadas y no por posición en el cuerpo de la tarea porque
 * es lo único que existe a la altura del ratón: el cuerpo pasa por el troceado de
 * Markdown y por el envoltorio antes de llegar a la pantalla, y deshacer ese camino
 * para volver al índice original sería reconstruir información que nadie necesita.
 * El precio es que ensanchar la ventana reenvuelve el texto y deja la posición
 * apuntando a otro sitio; por eso [TasklanePanel] tira la selección al redimensionar.
 */
internal data class TextPos(val line: Int, val offset: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos): Int =
        compareValuesBy(this, other, TextPos::line, TextPos::offset)
}

/**
 * El texto seleccionado a mano en una tarjeta.
 *
 * Va por [TaskId] y **no** por fila: cualquier cambio en cualquier tarea reconstruye
 * el árbol entero, y una selección guardada por índice se habría mudado a otra
 * tarjeta al primer repintado.
 *
 * [anchor] es donde se pulsó y [focus] donde está el ratón ahora, así que arrastrar
 * hacia arriba es tan válido como hacia abajo; quien pinta y quien copia miran
 * [from] y [to], que ya vienen ordenados.
 */
internal class CardSelection(val id: TaskId, val anchor: TextPos, val focus: TextPos) {
    val from: TextPos get() = minOf(anchor, focus)
    val to: TextPos get() = maxOf(anchor, focus)

    /** Un clic sin arrastrar. No se pinta ni se copia, pero sí es un ancla viva. */
    val isEmpty: Boolean get() = anchor == focus
}

/** Dónde cayó el ratón: la tarjeta, su fila —para poder medirla— y el punto exacto. */
internal class TextCaret(val id: TaskId, val row: Int, val pos: TextPos)

/**
 * Seleccionar texto dentro de una tarjeta con el ratón, y copiarlo.
 *
 * **Por qué a mano.** Las filas no son componentes: las pinta un único renderer que
 * se reutiliza para todas, así que no hay ningún `JTextArea` al que pedirle una
 * selección. Lo que sí hay es la misma aritmética con la que la fila resuelve dónde
 * se ha pulsado un enlace, y sobre ella se apoya esto: el renderer traduce un punto
 * a [TextPos] y se queda con el tramo, que pinta como fondo de los caracteres
 * elegidos —igual que ya pintaba el de los tramos de código—. No hay geometría de
 * por medio al pintar, y por eso el resalte no se puede desalinear del texto.
 *
 * La selección se limita a **una** tarjeta. Arrastrar fuera de ella no sigue a la
 * siguiente, se pega al principio o al final de la que se empezó: copiar media
 * tarjeta y media de otra no es nada que nadie quiera pegar, y a cambio el gesto
 * nunca se lleva por delante la lista entera.
 *
 * El arrastre estaba libre —`JTree` no selecciona filas arrastrando— así que no le
 * quita el sitio a ningún gesto. El clic simple sigue abriendo enlaces y anclas: un
 * clic no es un arrastre, y AWT sólo manda `mouseClicked` si el ratón no se movió.
 */
internal object CardTextSelection {

    fun install(tree: JTree, renderer: TaskTreeRenderer) {
        val mouse = object : MouseAdapter() {

            /** La fila donde empezó el arrastre. Es la única que la selección puede tocar. */
            private var anchorRow = -1

            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
                val caret = renderer.caretAt(tree, e.point)
                anchorRow = caret?.row ?: -1
                // Pulsar en cualquier otro sitio —otra tarjeta, un control, el hueco—
                // deshace la selección: es lo que hace cualquier texto de cualquier
                // sitio, y deja el gesto sin ninguna forma de quedarse pegado.
                val had = renderer.selection != null
                renderer.selection = caret?.let { CardSelection(it.id, it.pos, it.pos) }
                if (had) tree.repaint()
            }

            override fun mouseDragged(e: MouseEvent) {
                val current = renderer.selection ?: return
                if (anchorRow < 0) return
                val focus = renderer.caretTowards(tree, anchorRow, e.point) ?: return
                if (focus == current.focus) return
                renderer.selection = CardSelection(current.id, current.anchor, focus)
                repaintRow(tree, anchorRow)
            }
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)
    }

    /**
     * Lo que hay seleccionado ahora mismo, o `null`.
     *
     * Se vuelve a buscar la fila por [TaskId] en vez de guardarla: entre el
     * arrastre y el `⌘C` puede haber pasado cualquier cosa que reconstruya el árbol,
     * y una fila guardada apuntaría entonces a otra tarea.
     */
    fun selectedText(tree: JTree, renderer: TaskTreeRenderer): String? {
        val selection = renderer.selection?.takeIf { !it.isEmpty } ?: return null
        val row = rowOf(tree, selection.id) ?: return null
        val lines = renderer.cardText(tree, row)
        if (lines.isEmpty()) return null

        val from = selection.from
        val to = selection.to
        val first = from.line.coerceIn(0, lines.size - 1)
        val last = to.line.coerceIn(first, lines.size - 1)
        return (first..last).joinToString("\n") { index ->
            val text = lines[index]
            val start = if (index == from.line) from.offset.coerceIn(0, text.length) else 0
            val end = if (index == to.line) to.offset.coerceIn(start, text.length) else text.length
            text.substring(start, end)
        }.takeIf { it.isNotEmpty() }
    }

    fun copy(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun rowOf(tree: JTree, id: TaskId): Int? =
        (0 until tree.rowCount).firstOrNull { row ->
            (tree.getPathForRow(row)?.lastPathComponent as? TaskNode)?.task?.id == id
        }

    /** El ancho entero: el resalte llega hasta donde llega la tarjeta. */
    private fun repaintRow(tree: JTree, row: Int) {
        val bounds = tree.getRowBounds(row) ?: return
        tree.repaint(0, bounds.y, tree.width, bounds.height)
    }
}
