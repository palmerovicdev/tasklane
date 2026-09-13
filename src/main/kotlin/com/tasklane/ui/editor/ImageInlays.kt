package com.tasklane.ui.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ScreenUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.text.ImageRefParser
import com.tasklane.service.AttachmentService
import javax.swing.ImageIcon
import javax.swing.JLabel

/**
 * Mantiene sincronizado el texto del editor con lo que se ve: cada
 * `![](tasklane:<sha>)` se **pliega** a un `[image]` y se le cuelga debajo la vista
 * previa.
 *
 * **El plegado no es decoración.** Con la referencia cruda a la vista, el cuerpo de
 * una tarea con dos capturas son dos líneas de 80 caracteres de SHA que no significan
 * nada para nadie. Plegada, la referencia ocupa una palabra, se selecciona como
 * texto y se borra con `Supr` —y al borrarla desaparece la imagen—, que es
 * exactamente el gesto que pedía la arquitectura para quitar una imagen, resuelto por
 * la plataforma en vez de con una acción propia que habría que descubrir.
 *
 * **Recalcular va con debounce** porque el disparador es cada pulsación de tecla, y
 * rehacer inlays y regiones plegadas en cada letra se notaría al escribir.
 *
 * **Las imágenes se cargan fuera del EDT.** Mientras tanto el inlay ya está puesto,
 * con su marcador: el hueco no espera al disco. Ver `docs/architecture.html` §9.
 */
internal class ImageInlays(
    project: Project,
    private val repo: RepoKey,
    private val editor: EditorEx,
    parent: Disposable,
) {

    private val service = AttachmentService.getInstance(project)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)

    private val inlays = mutableListOf<Inlay<*>>()
    private val folds = mutableListOf<FoldRegion>()

    /** `true` = la imagen está; `false` = el blob no aparece; ausente = sin cargar. */
    private val loaded = HashMap<AttachmentId, Boolean>()
    private val loading = HashSet<AttachmentId>()

    /** Qué imagen hay bajo cada inlay, para resolver el clic sin volver a parsear. */
    private val byInlay = HashMap<Inlay<*>, AttachmentId>()

    init {
        editor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = schedule()
            },
            parent,
        )
        editor.addEditorMouseListener(
            object : EditorMouseListener {
                override fun mouseClicked(event: EditorMouseEvent) = onClick(event)
            },
            parent,
        )
        rebuild()
    }

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest({ rebuild() }, DEBOUNCE_MS)
    }

    // ------------------------------------------------------------ construcción

    private fun rebuild() {
        if (editor.isDisposed) return
        val refs = ImageRefParser.parse(editor.document.text)

        inlays.forEach(Disposer::dispose)
        inlays.clear()
        byInlay.clear()

        editor.foldingModel.runBatchFoldingOperation {
            folds.forEach(editor.foldingModel::removeFoldRegion)
            folds.clear()

            for (ref in refs) {
                val region = editor.foldingModel.addFoldRegion(
                    ref.range.first,
                    ref.range.last + 1,
                    TasklaneBundle.message("editor.image.folded"),
                ) ?: continue
                region.isExpanded = false
                folds += region
            }
        }

        for (ref in refs) {
            val state = when (loaded[ref.id]) {
                true -> ImageInlayRenderer.State.READY
                false -> ImageInlayRenderer.State.MISSING
                null -> {
                    load(ref.id)
                    ImageInlayRenderer.State.LOADING
                }
            }
            // relatesToPrecedingText: el bloque pertenece a la línea de la referencia,
            // así que al borrarla se va con ella en vez de quedarse colgando arriba.
            val inlay = editor.inlayModel.addBlockElement(
                ref.range.last + 1,
                true,
                false,
                0,
                ImageInlayRenderer(service, repo, ref.id, state),
            ) ?: continue
            inlays += inlay
            byInlay[inlay] = ref.id
        }
    }

    /**
     * Decodifica en segundo plano y vuelve al EDT sólo para rehacer los inlays.
     *
     * `ModalityState.any()`: esto vive dentro de un diálogo modal, y sin ella la
     * respuesta se quedaría en la cola hasta que el diálogo se cerrara —justo cuando
     * ya no hay nada que pintar—.
     */
    private fun load(id: AttachmentId) {
        if (!loading.add(id)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val present = service.image(repo, id) != null
            ApplicationManager.getApplication().invokeLater(
                {
                    loading -= id
                    loaded[id] = present
                    if (!editor.isDisposed) rebuild()
                },
                ModalityState.any(),
            )
        }
    }

    /** Una imagen recién pegada puede sustituir a un blob que antes no estaba. */
    fun invalidate(id: AttachmentId) {
        loaded -= id
        schedule()
    }

    // ------------------------------------------------------------------ clic

    /**
     * Clic sobre la vista previa: se amplía en un popup.
     *
     * La arquitectura pedía abrir el fichero con `FileEditorManager` para heredar el
     * zoom del plugin *Images*, y la implementación lo descartó por una razón de
     * bulto: este editor vive dentro de un **diálogo modal**, así que el fichero se
     * abriría detrás y no se podría tocar hasta cerrar la tarea. Un popup sí se ve
     * encima, y es lo que se pedía de verdad: mirar la captura de cerca.
     */
    private fun onClick(event: EditorMouseEvent) {
        val point = event.mouseEvent.point
        val inlay = editor.inlayModel.getElementAt(point) ?: return
        val id = byInlay[inlay] ?: return
        if (loaded[id] != true) return

        val screen = ScreenUtil.getScreenRectangle(editor.contentComponent)
        val image = service.preview(
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
            .show(RelativePoint(event.mouseEvent))

        event.consume()
    }

    private companion object {
        const val DEBOUNCE_MS = 250

        /** Cuánto de la pantalla puede ocupar la ampliación. */
        const val SCREEN_SHARE = 0.8
    }
}
