package com.tasklane.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import com.tasklane.TasklaneBundle
import com.tasklane.code.CodeAnchors
import com.tasklane.ui.common.ImagePreviewPopup
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree

/**
 * Lo que se abre con **un** clic desde la fila, sin entrar a editar la tarea: sus
 * enlaces, sus anclas de código y su prioridad; y de paso, qué cursor se ve sobre
 * cada sitio.
 *
 * Los tres van juntos y no en un oyente cada uno porque comparten el estado que se ve
 * —el cursor y el tooltip del árbol son uno solo— y el trabajo caro: resolver qué hay
 * bajo el ratón obliga a preparar y medir la fila, y tres oyentes lo harían tres veces
 * por cada píxel recorrido. Por eso se pregunta una vez, a [TaskTreeRenderer.hotspotAt].
 * Quien hace el trabajo de verdad sigue siendo cada uno por su lado: [TaskLinks] abre
 * enlaces, [CodeAnchors] navega al código, [PriorityPopup] cambia la prioridad y
 * [ImagePreviewPopup] amplía una captura.
 *
 * El doble clic sigue siendo «editar», así que el gesto de editar no se pierde en las
 * filas que tienen enlaces o anclas: sólo deja de dispararse justo encima de uno.
 */
internal object RowClicks {

    fun install(tree: JTree, renderer: TaskTreeRenderer, project: Project) {
        val mouse = object : MouseAdapter() {

            override fun mouseMoved(e: MouseEvent) {
                val hotspot = renderer.hotspotAt(tree, e.point)
                tree.cursor = when {
                    hotspot != null -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    // La I del texto es la única pista de que la tarjeta se puede
                    // seleccionar: sobre una lista nadie prueba a arrastrar por si
                    // acaso. Cuesta montar y medir la fila en cada movimiento, que es
                    // el mismo precio que ya paga resolver lo pulsable.
                    renderer.caretAt(tree, e.point) != null -> Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                    else -> Cursor.getDefaultCursor()
                }
                // Lo que se pinta es corto —la URL acortada, `Fichero.kt:42`— y el
                // tooltip lleva lo largo. Con varios enlaces no se enseña ninguno: el
                // popup los lista todos. El de la prioridad es el único que no amplía
                // nada de lo escrito: dice qué pasa al pulsar, que es lo que un
                // distintivo gris no consigue anunciar por su cuenta.
                tree.toolTipText = when (hotspot) {
                    is TaskTreeRenderer.Hotspot.Anchor -> hotspot.anchor.path
                    is TaskTreeRenderer.Hotspot.Links -> hotspot.links.singleOrNull()?.url
                    is TaskTreeRenderer.Hotspot.Priority ->
                        TasklaneBundle.message("toolwindow.row.priority.tooltip")

                    is TaskTreeRenderer.Hotspot.Image ->
                        TasklaneBundle.message("toolwindow.row.image.tooltip")

                    null -> null
                }
            }

            override fun mouseExited(e: MouseEvent) {
                tree.cursor = Cursor.getDefaultCursor()
                tree.toolTipText = null
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1 || e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
                when (val hotspot = renderer.hotspotAt(tree, e.point)) {
                    is TaskTreeRenderer.Hotspot.Links -> {
                        e.consume()
                        TaskLinks.open(hotspot.links, RelativePoint(e))
                    }

                    is TaskTreeRenderer.Hotspot.Anchor -> {
                        e.consume()
                        CodeAnchors.open(project, hotspot.anchor)
                    }

                    is TaskTreeRenderer.Hotspot.Priority -> {
                        e.consume()
                        PriorityPopup.show(project, renderer.config, hotspot.task, RelativePoint(e))
                    }

                    is TaskTreeRenderer.Hotspot.Image -> {
                        e.consume()
                        ImagePreviewPopup.show(project, hotspot.repo, hotspot.id, RelativePoint(e), tree)
                    }

                    null -> Unit
                }
            }
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)
    }
}
