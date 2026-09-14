package com.tasklane.code

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.tasklane.domain.model.AnchoredTask
import com.tasklane.ui.toolwindow.TaskReveal
import javax.swing.Icon

/**
 * El icono del margen: «en esta línea hay tarea».
 *
 * Un `GutterIconRenderer` y no un `LineMarkerProvider` —que es por donde la plataforma
 * suele ofrecer esto— por dos razones. Los *line markers* se calculan en el pase del
 * analizador y **necesitan PSI**: aquí el ancla puede estar en un `.txt`, en un `.csv` o
 * en un fichero de un lenguaje que este IDE no conozca, y el sitio seguiría siendo el
 * sitio. Y se recalculan cuando el analizador quiere: mover una tarea a *Done* tiene que
 * apagar su marca **en ese momento**, no en el siguiente pase. Colgando el icono de un
 * `RangeHighlighter` propio, quien manda sobre lo que se ve es el modelo de tareas. Ver
 * [AnchorMarkers].
 *
 * [equals] y [hashCode] no son formalidad: la plataforma los usa para decidir si el
 * margen cambió, y sin ellos repintaría —y perdería el estado del ratón— en cada
 * repintado del editor.
 */
internal class AnchorGutter(
    private val project: Project,
    private val entries: List<AnchoredTask>,
    private val icon: Icon,
    private val tooltip: String,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = icon

    override fun getTooltipText(): String = tooltip

    /**
     * Pegado al número de línea, que es la columna de los iconos que **hacen algo** —los
     * puntos de interrupción, el *Run* de un test—. La de la derecha, junto al código, la
     * usan las anotaciones de VCS y los avisos del analizador, que sólo informan.
     */
    override fun getAlignment(): Alignment = Alignment.LEFT

    /** Cambia el cursor a la mano: lo que parece pulsable, lo es. */
    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = click

    private val click = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            TaskReveal.show(project, entries.map { it.task })
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AnchorGutter && other.entries == entries && other.icon == icon && other.tooltip == tooltip

    override fun hashCode(): Int = 31 * entries.hashCode() + icon.hashCode()
}
