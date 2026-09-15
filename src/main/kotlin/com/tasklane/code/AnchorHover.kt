package com.tasklane.code

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Point
import javax.swing.JLabel
import javax.swing.ToolTipManager

/**
 * El hint de la pastilla del editor: aparece al pararse encima y **se queda mientras
 * sigas encima**.
 *
 * Esto lo hacía `IdeTooltipManager` y se cambió porque quien decidía cuándo esconderlo
 * era la plataforma. Su gestor vigila el ratón de toda la interfaz y esconde el tooltip
 * en cuanto llega un movimiento que no reconoce como «dentro» — y un inlay no es un
 * componente Swing, así que no lo reconoce nunca: el hint se iba al primer píxel de
 * movimiento, antes de que diera tiempo a leerlo. Peleárselo pedía heredar de `IdeTooltip`
 * para redefinir qué es «dentro», que es construir sobre cómo está escrito hoy el gestor.
 *
 * Un `Balloon` no tiene ese dueño: se muestra y se esconde cuando se le dice. Con
 * `fadeoutTime = 0` no caduca, y sin esconderse por clic, tecla o acción, las únicas
 * razones para que desaparezca son las que decide [AnchorMarkers]: que el ratón se vaya
 * de la pastilla, que salga del editor, que se pulse, que se recoloquen las marcas o que
 * se desplace el editor —al hacer *scroll* el texto se mueve y el globo se quedaría
 * señalando a otra línea—.
 *
 * El retardo sale del IDE (`ToolTipManager.initialDelay`) para que aparezca al mismo ritmo
 * que los demás, pero **con tope**: ese ajuste viene de fábrica en algo más de un segundo,
 * y aquí el ratón ya está parado sobre una marca diminuta que el usuario ha ido a buscar a
 * propósito. Esperar más de medio segundo a eso se lee como que no hay nada que enseñar.
 *
 * **El texto se construye en segundo plano.** Desde que el tooltip enseña las capturas de
 * la tarea, componerlo significa leer y medir los blobs del disco, y eso en el EDT congela
 * el editor justo mientras se mueve el ratón por él. Se compone fuera, se vuelve al EDT
 * sólo a enseñar el globo, y al volver se repiten las comprobaciones: durante esa ida y
 * vuelta el ratón ha podido irse de la pastilla o el editor cerrarse.
 */
internal class AnchorHover(parent: Disposable) {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)

    private var shown: Balloon? = null

    /** Sobre qué pastilla está el ratón ahora. `null` = sobre ninguna. */
    private var target: Inlay<*>? = null

    /**
     * Pide enseñar el hint de [inlay]. Si ya es el que está —o el que está a punto de
     * salir—, no hace nada: reprogramarlo en cada movimiento del ratón dentro de la
     * misma pastilla es exactamente el parpadeo que esto viene a quitar.
     */
    fun show(editor: Editor, inlay: Inlay<*>, point: Point, html: () -> String) {
        if (inlay === target) return
        hide()
        target = inlay
        val delay = minOf(ToolTipManager.sharedInstance().initialDelay, MAX_DELAY_MS)
        alarm.addRequest({ reveal(editor, inlay, point, html) }, delay)
    }

    fun hide() {
        alarm.cancelAllRequests()
        target = null
        shown?.let { if (!it.isDisposed) it.hide() }
        shown = null
    }

    private fun reveal(editor: Editor, inlay: Inlay<*>, point: Point, html: () -> String) {
        // El ratón pudo irse mientras corría el retardo, y el editor cerrarse.
        if (target !== inlay || editor.isDisposed || !editor.contentComponent.isShowing) return

        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val text = html()
            // `any()` por lo mismo que en las vistas previas de la lista: sin ella el
            // globo se quedaría en la cola detrás de cualquier modal abierto.
            application.invokeLater({ show(editor, inlay, point, text) }, ModalityState.any())
        }
    }

    private fun show(editor: Editor, inlay: Inlay<*>, point: Point, html: String) {
        // Otra vez, y no por repetirse: entre la comprobación de [reveal] y esta línea
        // se ha ido al disco a leer las capturas.
        if (target !== inlay || editor.isDisposed || !editor.contentComponent.isShowing) return

        val label = JLabel(html).apply {
            foreground = UIUtil.getToolTipForeground()
            border = JBUI.Borders.empty(4, 6)
        }
        shown = JBPopupFactory.getInstance()
            .createBalloonBuilder(label)
            .setFillColor(UIUtil.getToolTipBackground())
            .setBorderColor(JBColor.border())
            .setBorderInsets(JBUI.emptyInsets())
            .setAnimationCycle(ANIMATION_MS)
            // Sin caducidad: mientras el ratón siga encima, el texto sigue ahí.
            .setFadeoutTime(0)
            .setHideOnClickOutside(false)
            .setHideOnKeyOutside(false)
            .setHideOnAction(false)
            .setHideOnFrameResize(true)
            .setBlockClicksThroughBalloon(false)
            .setShadow(true)
            .createBalloon()
        // Encima del punto: debajo taparía la línea siguiente y, sobre todo, se comería
        // el sitio hacia donde se mueve el ratón para seguir leyendo el código.
        shown?.show(RelativePoint(editor.contentComponent, point), Balloon.Position.above)
    }

    private companion object {
        const val ANIMATION_MS = 120

        /** Ver la nota de arriba sobre el retardo. */
        const val MAX_DELAY_MS = 500
    }
}
