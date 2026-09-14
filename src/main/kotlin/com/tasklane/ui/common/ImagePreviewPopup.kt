package com.tasklane.ui.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ScreenUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.service.AttachmentService
import java.awt.Component
import javax.swing.ImageIcon
import javax.swing.JLabel

/**
 * Ver una captura de cerca: la vista previa ampliada, en un popup.
 *
 * La arquitectura pedía abrir el fichero con `FileEditorManager` para heredar el zoom
 * del plugin *Images*, y la implementación lo descartó por una razón de bulto: el
 * editor del cuerpo vive dentro de un **diálogo modal**, así que el fichero se abriría
 * detrás y no se podría tocar hasta cerrar la tarea. Un popup sí se ve encima, y es lo
 * que se pedía de verdad.
 *
 * Vive aquí y no dentro del diálogo porque desde la `1.3.0` hay **dos** sitios donde
 * se pulsa una vista previa —el editor del cuerpo y la tarjeta desplegada de la
 * lista—, y ampliar tiene que hacer lo mismo en los dos.
 *
 * Escala a una fracción de la **pantalla** y no de la ventana: mirar de cerca es
 * precisamente querer más sitio del que tiene la tool window.
 */
internal object ImagePreviewPopup {

    /**
     * @param over el componente desde el que se pulsa. Sólo se usa para saber en qué
     *   pantalla está, que con varios monitores no es la misma para todo el mundo.
     */
    fun show(project: Project, repo: RepoKey, id: AttachmentId, at: RelativePoint, over: Component) {
        val screen = ScreenUtil.getScreenRectangle(over)
        val image = AttachmentService.getInstance(project).preview(
            repo,
            id,
            (screen.width * SCREEN_SHARE).toInt(),
            (screen.height * SCREEN_SHARE).toInt(),
        ) ?: return

        val label = JLabel(ImageIcon(image)).apply { border = JBUI.Borders.empty() }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(label), null)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setTitle(TasklaneBundle.message("editor.image.popup.title"))
            .createPopup()
            .show(at)
    }

    /** Cuánto de la pantalla puede ocupar la ampliación. */
    private const val SCREEN_SHARE = 0.8
}
