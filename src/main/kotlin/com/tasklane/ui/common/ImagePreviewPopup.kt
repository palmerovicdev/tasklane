package com.tasklane.ui.common

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.ComponentPopupBuilder
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ActiveComponent
import com.intellij.ui.InplaceButton
import com.intellij.ui.ScreenUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.service.AttachmentService
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Ver una captura de cerca: la vista previa ampliada, en un popup.
 *
 * La arquitectura pedía abrir el fichero con `FileEditorManager` para heredar el zoom
 * del plugin *Images*, y la implementación lo descartó por una razón de bulto: el
 * editor del cuerpo vive dentro de un **diálogo modal**, así que el fichero se abriría
 * detrás y no se podría tocar hasta cerrar la tarea. Un popup sí se ve encima, y es lo
 * que se pedía de verdad.
 *
 * Vive aquí y no dentro del diálogo porque desde la `1.3.0` hay **tres** sitios donde
 * se pide ampliar —el editor del cuerpo, la vista previa de la tarjeta desplegada y,
 * desde la `2.6.0`, el contador de imágenes de la fila plegada—, y ampliar tiene que
 * hacer lo mismo en los tres.
 *
 * Escala a una fracción de la **pantalla** y no de la ventana: mirar de cerca es
 * precisamente querer más sitio del que tiene la tool window.
 *
 * ## Fijarlo (2.6.0)
 *
 * El popup se cierra al pulsar fuera, que es lo correcto para echar un vistazo y
 * seguir. Pero media función de pegar una captura en una tarea es **mirarla mientras
 * se escribe el código**, y para eso el vistazo no sirve: en cuanto vuelves al editor
 * la imagen se va. La chincheta de la cabecera cambia esa regla —y sólo esa—: fijado,
 * el popup se queda encima del editor y deja de escuchar los clics de fuera, así que
 * se puede teclear con la captura delante. Se suelta con la misma chincheta y se
 * cierra con la ✕ o con Esc.
 *
 * Fijar **rehace** el popup en vez de cambiarle una propiedad: `cancelOnClickOutside`
 * y compañía se deciden al construirlo y la plataforma no las deja tocar después.
 * Como se vuelve a abrir en las mismas coordenadas de pantalla y con el mismo tamaño,
 * lo que se ve es la chincheta cambiando de estado.
 */
internal object ImagePreviewPopup {

    /**
     * @param over el componente desde el que se pulsa. Sólo se usa para saber en qué
     *   pantalla está, que con varios monitores no es la misma para todo el mundo.
     */
    fun show(project: Project, repo: RepoKey, id: AttachmentId, at: RelativePoint, over: Component) =
        show(project, repo, listOf(id), at, over)

    /**
     * Lo mismo con varias: es lo que abre el contador de imágenes de la fila, que
     * habla de todas las de la tarea. Van una debajo de otra dentro del mismo panel
     * desplazable — son la misma tarea, y abrir cuatro ventanas por pulsar una vez no
     * es lo que nadie pidió.
     */
    fun show(project: Project, repo: RepoKey, ids: List<AttachmentId>, at: RelativePoint, over: Component) {
        if (ids.isEmpty()) return
        val screen = ScreenUtil.getScreenRectangle(over)
        // En segundo plano: desde la 2.3 el original es la captura a su tamaño, y
        // descodificarla en el EDT congelaría el clic.
        ApplicationManager.getApplication().executeOnPooledThread {
            val service = AttachmentService.getInstance(project)
            val width = (screen.width * SCREEN_SHARE).toInt()
            // Con varias, el alto se reparte: cuatro capturas a 0,8 de pantalla cada
            // una son cuatro pantallas de desplazamiento para ver la última.
            val height = (screen.height * SCREEN_SHARE).toInt() / ids.size.coerceAtMost(MAX_STACKED)
            val images = ids.take(MAX_STACKED).mapNotNull { service.fullPreview(repo, it, width, height) }
            if (images.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !over.isShowing) return@invokeLater
                    open(project, images, at, over, pinned = false, bounds = null)
                },
                // El editor del cuerpo vive en un diálogo modal: sin esto el popup esperaría
                // a que se cerrara.
                ModalityState.any(),
            )
        }
    }

    /** Dónde estaba el popup que se rehace al fijarlo o soltarlo. */
    private class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun open(
        project: Project,
        images: List<BufferedImage>,
        at: RelativePoint,
        over: Component,
        pinned: Boolean,
        bounds: Bounds?,
    ) {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            images.forEach {
                add(
                    JLabel(ImageIcon(it)).apply {
                        border = JBUI.Borders.empty()
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
            }
        }
        lateinit var popup: JBPopup
        val builder = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(content), null)
            .setProject(project)
            .setResizable(true)
            .setMovable(true)
            .setTitle(TasklaneBundle.message("editor.image.popup.title"))
            .setCommandButton(
                header(
                    pinned,
                    onPin = { popup.toggle(project, images, at, over, pinned) },
                    onClose = { popup.cancel() },
                ),
            )
        popup = builder.forPin(pinned).createPopup()
        val where = bounds?.let { anchor(project, over) }
        if (where == null) {
            popup.show(at)
        } else {
            // Rehecho: exactamente donde estaba y del mismo tamaño, para que fijar se
            // vea como pulsar una chincheta y no como abrir otra ventana.
            popup.showInScreenCoordinates(where, Point(bounds.x, bounds.y))
            popup.size = Dimension(bounds.width, bounds.height)
        }
    }

    /**
     * Desde qué componente se vuelve a abrir el popup al fijarlo.
     *
     * El de siempre es el que se pulsó, pero puede no estar a la vista: fijar la
     * ventana y **plegar la tool window** para dejarle sitio al editor es justo lo que
     * esto viene a permitir, y ahí el árbol ya no se ve. El marco del proyecto sí, y
     * para colocar una ventana en coordenadas de pantalla da igual cuál de los dos sea.
     */
    private fun anchor(project: Project, over: Component): Component? =
        over.takeIf { it.isShowing } ?: WindowManager.getInstance().getFrame(project)?.rootPane

    /**
     * Las dos formas de vivir del popup.
     *
     * Suelto: el de siempre —pide el foco, se va al pulsar fuera o al irse la ventana—.
     * Fijado: no pide el foco —se sigue tecleando en el editor, que es el sentido de
     * fijarlo—, y las tres razones por las que un popup se cierra solo quedan apagadas.
     */
    private fun ComponentPopupBuilder.forPin(pinned: Boolean): ComponentPopupBuilder = this
        .setRequestFocus(!pinned)
        .setFocusable(true)
        .setCancelOnClickOutside(!pinned)
        .setCancelOnWindowDeactivation(!pinned)
        .setCancelOnOtherWindowOpen(!pinned)
        .setCancelKeyEnabled(true)
        // Fuera de la pila global de popups mientras está fijado: si no, un Esc dado
        // en el editor —donde se está escribiendo, que es el sentido de fijarlo— lo
        // cerraría. Fijado se cierra con su ✕, o con Esc estando encima de él.
        .setBelongsToGlobalPopupStack(!pinned)

    private fun JBPopup.toggle(
        project: Project,
        images: List<BufferedImage>,
        at: RelativePoint,
        over: Component,
        pinned: Boolean,
    ) {
        val where = if (isVisible) {
            val origin = locationOnScreen
            val size = size
            Bounds(origin.x, origin.y, size.width, size.height)
        } else {
            null
        }
        cancel()
        open(project, images, at, over, pinned = !pinned, bounds = where)
    }

    /**
     * La chincheta y la ✕ de la cabecera.
     *
     * Van juntas en un componente porque la plataforma sólo admite **uno** en la
     * cabecera del popup (`setCommandButton`), y fijado hace falta la ✕: sin clic de
     * fuera que lo cierre, cerrarlo tiene que ser un botón.
     *
     * **El margen se pone a mano** (2.6.0). El botón propio de la plataforma —el de
     * cancelar, que pasa por `IconButton` a secas— entra en `CaptionPanel` con 4 px de
     * borde de fábrica; el que se cuelga por [ComponentPopupBuilder.setCommandButton]
     * entra **sin ninguno**. Con el margen que traía antes —4 px a los lados y nada
     * arriba— el icono quedaba pegado a la esquina redondeada de la ventana, que es
     * donde el recorte del popup empieza a comerse píxeles: con un icono se notaba
     * poco, con dos —fijado, chincheta y ✕ juntas— quedaban encajados en la esquina.
     * Este margen deja el mismo aire que el botón de la plataforma y un poco más a la
     * derecha, que es el lado que da a la curva.
     */
    private fun header(pinned: Boolean, onPin: () -> Unit, onClose: () -> Unit): ActiveComponent {
        val pin = InplaceButton(
            IconButton(
                TasklaneBundle.message(if (pinned) "editor.image.popup.unpin" else "editor.image.popup.pin"),
                if (pinned) AllIcons.General.PinSelected else AllIcons.General.Pin,
                if (pinned) AllIcons.General.PinSelectedHovered else AllIcons.General.PinHovered,
            ),
        ) { onPin() }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(GAP), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(HEADER_MARGIN, GAP, HEADER_MARGIN, HEADER_RIGHT_MARGIN)
            add(pin)
            if (pinned) {
                add(
                    InplaceButton(
                        IconButton(
                            TasklaneBundle.message("editor.image.popup.close"),
                            AllIcons.Actions.Close,
                            AllIcons.Actions.CloseHovered,
                        ),
                    ) { onClose() },
                )
            }
        }
        return object : ActiveComponent {
            override fun setActive(active: Boolean) = Unit
            override fun getComponent(): JComponent = buttons
        }
    }

    /** Cuánto de la pantalla puede ocupar la ampliación. */
    private const val SCREEN_SHARE = 0.8

    /**
     * Cuántas capturas entran en una ampliación. Una tarea con veinte no se mira en un
     * popup, se abre.
     */
    private const val MAX_STACKED = 4

    private const val GAP = 4

    /** Igual que el borde de fábrica del botón propio de la plataforma. Ver [header]. */
    private const val HEADER_MARGIN = 12

    /**
     * Más aire por el lado que da a la esquina redondeada del popup: es donde el
     * recorte de la ventana empieza a comerse píxeles del icono. Ver [header].
     */
    private const val HEADER_RIGHT_MARGIN = 0
}
