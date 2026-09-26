package com.tasklane.ui.common

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.GotItTooltip
import com.tasklane.TasklaneBundle
import com.tasklane.ui.settings.TasklaneConfigurable
import java.awt.Point
import javax.swing.JComponent

/**
 * Los *Got It* de la primera vez (P35): la primera ancla y la primera captura.
 *
 * Son los dos gestos en los que Tasklane hace algo que no se ve venir —la línea del editor
 * se marca y el ancla la sigue; la captura se amplía, se fija encima del editor y no va a
 * Git—, y el sitio de contarlo es justo donde acaba de pasar: el del ancla, en el editor,
 * debajo de la línea (ver `AnchorGotIt`); el de la captura, en la miniatura de la tarjeta
 * (ver `TasklanePanel`).
 *
 * **Que ya se vio lo guarda la plataforma**, por id y para toda la aplicación, y cuenta
 * sólo cuando se pulsa *Got It* o `Escape`: cerrarlo pinchando fuera no es haberlo leído, y
 * vuelve la próxima vez. Se piden los textos aquí y no en quien los enseña para que el mismo
 * id diga siempre lo mismo.
 */
internal object FirstStepTips {

    const val ANCHOR = "tasklane.first.anchor"
    const val IMAGE = "tasklane.first.image"

    /**
     * Los que están a la vista o esperando turno, **sólo en el EDT**. La cola de *Got It* de
     * la plataforma enseña el siguiente al cerrar el anterior sin volver a mirar si ya se
     * vio, así que dos iguales —la misma tarea en la ventana y en el tablero— saldrían una
     * detrás de otra.
     */
    private val live = HashSet<String>()

    /** Si todavía no se ha dicho *Got It* a [id]. */
    fun canShow(id: String): Boolean {
        val probe = GotItTooltip(id, "", null)
        return try {
            probe.canShow()
        } finally {
            Disposer.dispose(probe)
        }
    }

    /**
     * Enseña [id] sobre [component], señalando lo que diga [point] —en coordenadas de
     * [component]—, si todavía toca y no hay otro igual a la vista.
     *
     * [point] se vuelve a preguntar cada vez que la plataforma recoloca el globo, así que
     * puede seguir a lo que señala; `null` es «no lo encuentro», y entonces se queda donde
     * estaba. Si lo señalado sale de la parte visible, el globo se esconde solo.
     *
     * **No se enseña si lo señalado no se ve ya**, y no es cortesía: la plataforma esconde el
     * globo cuyo punto cae fuera de lo visible como si se hubiera pulsado *Got It*, y lo
     * daría por visto sin que nadie lo viera.
     */
    fun show(id: String, project: Project, parent: Disposable, component: JComponent, point: () -> Point?) {
        if (id in live || !component.isShowing || !canShow(id)) return
        val first = point()?.takeIf { component.visibleRect.contains(it) } ?: return
        var last = first
        val tip = build(id, project, parent).withPosition(Balloon.Position.below)
        tip.setOnBalloonCreated { balloon ->
            live += id
            balloon.addListener(
                object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        live -= id
                        // Después: la plataforma tiene su propio oyente de cierre, que es el
                        // que apunta el *Got It*, y el orden entre los dos no es de nadie.
                        ApplicationManager.getApplication().invokeLater { Disposer.dispose(tip) }
                    }
                },
            )
        }
        tip.show(component) { _, _ -> (point() ?: last).also { last = it } }
    }

    private fun build(id: String, project: Project, parent: Disposable): GotItTooltip = when (id) {
        ANCHOR -> GotItTooltip(id, TasklaneBundle.message("tip.anchor.text"), parent)
            .withHeader(TasklaneBundle.message("tip.anchor.header"))
            .withLink(TasklaneBundle.message("tip.anchor.link")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, TasklaneConfigurable::class.java)
            }

        IMAGE -> GotItTooltip(id, { TasklaneBundle.message("tip.image.text", code(".idea/tasklane")) }, parent)
            .withHeader(TasklaneBundle.message("tip.image.header"))

        else -> error("Unknown tip $id")
    }
}
