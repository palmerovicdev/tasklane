package com.tasklane.ui.toolwindow

import com.intellij.ui.CheckboxTree
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.ui.common.PriorityStripeBorder
import javax.swing.JTree

/**
 * Pinta una fila: franja de prioridad, checkbox, título y fecha atenuada.
 *
 * El renderer no decide nada: recibe el [TasklaneConfig] vigente y traduce IDs a
 * color y nombre. Toda la lógica vive en el dominio.
 */
internal class TaskTreeRenderer : CheckboxTree.CheckboxTreeCellRenderer() {

    var config: TasklaneConfig = TasklaneConfig.DEFAULT

    override fun customizeRenderer(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? TaskNode ?: return
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

        if (!state.terminal) {
            textRenderer.append("  ${state.name}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
