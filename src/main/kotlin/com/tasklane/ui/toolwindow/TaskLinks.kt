package com.tasklane.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.TaskLink
import com.tasklane.domain.text.UrlShortener
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JTree

/**
 * Abrir un enlace **desde la fila**, sin entrar a editar la tarea. Es el criterio de
 * aceptación de la Fase 5 y la razón de que los enlaces se extraigan al escribir.
 *
 * Un clic basta: con un solo enlace se abre, con varios sale un `ListPopup` con las
 * URLs acortadas. El doble clic sigue siendo «editar», así que el gesto de editar no
 * se pierde en las filas que tienen enlaces —sólo deja de dispararse justo encima de
 * uno—.
 */
internal object TaskLinks {

    /** Presupuesto de acortado del popup: cabe más que en una fila del árbol. */
    private const val POPUP_MAX = 72

    fun install(tree: JTree, renderer: TaskTreeRenderer) {
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val links = renderer.linksAt(tree, e.point)
                tree.cursor = if (links.isEmpty()) {
                    Cursor.getDefaultCursor()
                } else {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
                // La URL completa vive en el modelo y en el tooltip; lo que se pinta
                // es la acortada. Con varios enlaces no se enseña ninguno: el popup
                // los lista todos.
                tree.toolTipText = links.singleOrNull()?.url
            }

            override fun mouseExited(e: MouseEvent) {
                tree.cursor = Cursor.getDefaultCursor()
                tree.toolTipText = null
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1 || e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
                val links = renderer.linksAt(tree, e.point)
                if (links.isEmpty()) return
                e.consume()
                open(links, RelativePoint(e))
            }
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)
    }

    fun open(links: List<TaskLink>, at: RelativePoint) {
        // El mismo enlace repetido en el cuerpo es un enlace: el popup preguntaría
        // dos veces lo mismo.
        val distinct = links.distinctBy { it.url }
        distinct.singleOrNull()?.let { browse(it.url); return }
        if (distinct.isEmpty()) return

        val step = object : BaseListPopupStep<TaskLink>(
            TasklaneBundle.message("link.popup.title"),
            distinct,
        ) {
            override fun getTextFor(value: TaskLink): String = UrlShortener.shorten(value.url, POPUP_MAX)

            override fun getIconFor(value: TaskLink): Icon = AllIcons.Ide.Link

            override fun onChosen(selectedValue: TaskLink, finalChoice: Boolean): PopupStep<*>? {
                browse(selectedValue.url)
                return PopupStep.FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).show(at)
    }

    /**
     * La frontera de seguridad, repetida a propósito aunque `LinkExtractor` ya sólo
     * produzca `http(s)`: esta es la línea donde una URL deja de ser texto y pasa a
     * ejecutarse, y no debe depender de que el extractor no cambie nunca.
     */
    private fun browse(url: String) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return
        BrowserUtil.browse(url)
    }
}
