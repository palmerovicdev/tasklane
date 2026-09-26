package com.tasklane.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.awt.RelativePoint
import com.tasklane.TasklaneBundle
import com.tasklane.code.CodeAnchors
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.text.TaskCopyText
import com.tasklane.domain.command.TaskCommand
import com.tasklane.service.AttachmentService
import com.tasklane.service.TaskService
import com.tasklane.ui.common.ImagePreviewPopup
import com.tasklane.ui.common.ImageTooltip
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree

/**
 * Lo que se abre o copia con **un** clic desde la fila, sin entrar a editar la tarea:
 * sus enlaces, sus anclas de código, sus capturas, su prioridad y su texto; y de paso,
 * qué cursor se ve sobre cada sitio.
 *
 * Todos van juntos y no en un oyente cada uno porque comparten el estado que se ve
 * —el cursor y el tooltip del árbol son uno solo— y el trabajo caro: resolver qué hay
 * bajo el ratón obliga a preparar y medir la fila, y tres oyentes lo harían tres veces
 * por cada píxel recorrido. Por eso se pregunta una vez, a [TaskTreeRenderer.hotspotAt].
 * Quien hace el trabajo de verdad sigue siendo cada uno por su lado: [TaskLinks] abre
 * enlaces, [CodeAnchors] navega al código, [PriorityPopup] cambia la prioridad y
 * [ImagePreviewPopup] amplía una captura y [TaskCopyText] prepara el texto plano.
 *
 * El doble clic sigue siendo «editar», así que el gesto de editar no se pierde en las
 * filas que tienen enlaces o anclas: sólo deja de dispararse justo encima de uno.
 */
internal object RowClicks {

    fun install(tree: JTree, renderer: TaskTreeRenderer, project: Project) {
        val shots = RowImageTips(project) { tree.toolTipText = it }
        val mouse = object : MouseAdapter() {

            override fun mouseMoved(e: MouseEvent) {
                // El asa de una pestaña a mano va primero: es el único sitio donde
                // arrastrar reordena en vez de seleccionar texto (2.11.0).
                if (renderer.isOnHandle(tree, e.point)) {
                    tree.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    tree.toolTipText = TasklaneBundle.message("toolwindow.row.reorder.tooltip")
                    shots.over(null)
                    return
                }
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
                // Sobre qué capturas está el ratón, para que las que lleguen tarde del
                // disco sólo se enseñen si sigue encima de las suyas.
                shots.over(hotspot as? TaskTreeRenderer.Hotspot.Images)
                // Lo que se pinta es corto —la URL acortada, `Fichero.kt:42`— y el
                // tooltip lleva lo largo. Con varios enlaces no se enseña ninguno: el
                // popup los lista todos. El de la prioridad es el único que no amplía
                // nada de lo escrito: dice qué pasa al pulsar, que es lo que un
                // distintivo gris no consigue anunciar por su cuenta. Y el del contador
                // de capturas no es texto: es la captura.
                tree.toolTipText = when (hotspot) {
                    is TaskTreeRenderer.Hotspot.Anchor ->
                        if (hotspot.anchor.path in renderer.brokenAnchors) {
                            TasklaneBundle.message(
                                "toolwindow.row.anchor.broken.tooltip",
                                StringUtil.escapeXmlEntities(hotspot.anchor.path),
                            )
                        } else {
                            hotspot.anchor.path
                        }

                    is TaskTreeRenderer.Hotspot.Links -> hotspot.links.singleOrNull()?.url
                    is TaskTreeRenderer.Hotspot.Priority ->
                        TasklaneBundle.message("toolwindow.row.priority.tooltip")

                    is TaskTreeRenderer.Hotspot.Image ->
                        TasklaneBundle.message("toolwindow.row.image.tooltip")

                    // Mientras la captura se lee del disco, lo que hace el
                    // distintivo; en cuanto está, la captura. La frase no sobra: sin
                    // ella `toolTipText` se quedaría a nulo, que da de baja al árbol en
                    // el gestor de tooltips, y el primer tooltip de una fila nueva se
                    // perdería aunque la imagen llegara un milisegundo después.
                    is TaskTreeRenderer.Hotspot.Images ->
                        shots.html(hotspot) ?: TasklaneBundle.message("toolwindow.row.images.tooltip")

                    is TaskTreeRenderer.Hotspot.Copy ->
                        TasklaneBundle.message("toolwindow.row.copy.tooltip")

                    is TaskTreeRenderer.Hotspot.Check -> null

                    null -> null
                }
            }

            override fun mouseExited(e: MouseEvent) {
                tree.cursor = Cursor.getDefaultCursor()
                tree.toolTipText = null
                shots.over(null)
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

                    is TaskTreeRenderer.Hotspot.Images -> {
                        e.consume()
                        ImagePreviewPopup.show(project, hotspot.repo, hotspot.ids, RelativePoint(e), tree)
                    }

                    is TaskTreeRenderer.Hotspot.Copy -> {
                        e.consume()
                        CardTextSelection.copy(TaskCopyText.plain(hotspot.task))
                    }

                    // La casilla escribe su `x` en el cuerpo (2.11.0). En un repositorio en
                    // solo lectura el servicio no aplica el comando y la casilla se queda
                    // como estaba, igual que la de completar.
                    is TaskTreeRenderer.Hotspot.Check -> {
                        e.consume()
                        TaskService.getInstance(project).apply(
                            TaskCommand.ToggleCheck(hotspot.task.repo, hotspot.task.id, hotspot.offset),
                        )
                    }

                    null -> Unit
                }
            }
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)
    }
}

/**
 * Los tooltips con la captura dentro, compuestos **fuera del EDT** y recordados.
 *
 * Existe por lo mismo que [CardImages]: componer uno lee y mide blobs del disco, y
 * esto corre en cada movimiento del ratón por la lista. Aquí sólo se pregunta si el
 * de esta fila ya está; el que no está se pide al hilo de fondo y se enseña al volver,
 * si para entonces el ratón sigue sobre el mismo distintivo.
 *
 * Se recuerda también lo que **no** hay —una tarea que referencia blobs que ya no
 * están—: sin eso, pasar el ratón por esa fila volvería a ir al disco cada vez. Y se
 * olvida todo cuando el servicio cambia de época, que es lo que pasa al borrar
 * imágenes desde los ajustes; si no, se seguiría enseñando una captura que ya no está.
 */
private class RowImageTips(project: Project, private val onReady: (String) -> Unit) {

    private val service = AttachmentService.getInstance(project)

    private val cache = HashMap<Key, String?>()
    private val loading = HashSet<Key>()

    /** Sobre qué distintivo está el ratón ahora mismo. */
    private var hovered: Key? = null

    private var epoch = service.epoch

    fun over(hotspot: TaskTreeRenderer.Hotspot.Images?) {
        hovered = hotspot?.let { Key(it.repo, it.ids) }
    }

    /** El tooltip de [hotspot], o `null` mientras se compone —o si no hay nada—. */
    fun html(hotspot: TaskTreeRenderer.Hotspot.Images): String? {
        val now = service.epoch
        if (now != epoch) {
            epoch = now
            cache.clear()
        }
        val key = Key(hotspot.repo, hotspot.ids)
        if (key in cache) return cache[key]
        load(key)
        return null
    }

    private fun load(key: Key) {
        if (!loading.add(key)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = ImageTooltip.html(service, key.repo, key.ids)
            ApplicationManager.getApplication().invokeLater(
                {
                    loading -= key
                    cache[key] = html
                    // Sólo si el ratón no se ha ido: poner ahora el tooltip de una fila
                    // que ya no se señala lo enseñaría sobre otra.
                    if (html != null && hovered == key) onReady(html)
                },
                // `any()` por lo mismo que en las vistas previas de la lista: sin ella
                // la respuesta se quedaría en la cola detrás de cualquier modal.
                ModalityState.any(),
            )
        }
    }

    private data class Key(val repo: RepoKey, val ids: List<AttachmentId>)
}
