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
import javax.swing.Icon

/**
 * Abrir un enlace **desde la fila**, sin entrar a editar la tarea. Es el criterio de
 * aceptación de la Fase 5 y la razón de que los enlaces se extraigan al escribir.
 *
 * Un clic basta: con un solo enlace se abre, con varios sale un `ListPopup` con las
 * URLs acortadas. Quien atiende ese clic es [RowClicks], que es el que sabe si el ratón
 * está sobre un enlace o sobre otra cosa de la fila; aquí queda sólo lo que hay que
 * hacer una vez que se sabe.
 */
internal object TaskLinks {

    /** Presupuesto de acortado del popup: cabe más que en una fila del árbol. */
    private const val POPUP_MAX = 72

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
