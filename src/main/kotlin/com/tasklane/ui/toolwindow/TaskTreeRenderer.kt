package com.tasklane.ui.toolwindow

import com.intellij.ui.CheckboxTree
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.ui.common.DateGroupLabels
import com.tasklane.ui.common.PriorityStripeBorder
import javax.swing.JTree

/**
 * Pinta una fila: franja de prioridad, checkbox, título y fecha atenuada; y las
 * cabeceras de los grupos de fecha.
 *
 * El renderer no decide nada: recibe el [TasklaneConfig] vigente y traduce IDs a
 * color y nombre. Toda la lógica vive en el dominio.
 */
internal class TaskTreeRenderer : CheckboxTree.CheckboxTreeCellRenderer() {

    var config: TasklaneConfig = TasklaneConfig.DEFAULT

    /**
     * El nombre del estado sobra cuando la pestaña ya es ese estado. Se mantiene el
     * interruptor porque la vista de lista única —estados como nodos raíz— sí lo
     * necesita.
     */
    var showStateName: Boolean = false

    override fun customizeRenderer(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (value) {
            is GroupNode -> renderGroup(value)
            is TaskNode -> renderTask(value)
        }
    }

    private fun renderGroup(node: GroupNode) {
        // Sin franja: la prioridad es una propiedad de la tarea, no del grupo.
        border = null
        textRenderer.append(DateGroupLabels.of(node.group), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        textRenderer.append("  ${node.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    private fun renderTask(node: TaskNode) {
        val task = node.task
        val priority = config.priorityOrDefault(task.priorityId)
        val state = config.stateOrDefault(task.stateId)

        // JBColor resuelve claro/oscuro solo; por eso el modelo guarda ambos valores.
        border = PriorityStripeBorder(JBColor(priority.colorLight, priority.colorDark))

        val titleStyle = if (state.terminal) {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY)
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        textRenderer.append(task.title, titleStyle)

        if (task.hasDetail) {
            textRenderer.append("  …", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        val stamp = task.completedAt ?: task.updatedAt
        textRenderer.append(
            "   ${DateFormatUtil.formatPrettyDate(stamp.toEpochMilli())}",
            SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
        )

        if (showStateName) {
            textRenderer.append("  ${state.name}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
