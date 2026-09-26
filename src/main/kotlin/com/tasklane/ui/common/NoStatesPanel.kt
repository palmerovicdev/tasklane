package com.tasklane.ui.common

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.ui.settings.TasklaneConfigurable
import java.awt.FlowLayout
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Lo que se ve cuando se han quitado todos los estados de la tool window o del tablero en
 * los ajustes (2.17.1): lo dice, y lleva a los ajustes, que es donde se deshace. Un sitio en
 * blanco sin más parecería roto.
 */
internal fun noStatesPanel(project: Project, text: String): JComponent = JPanel(GridBagLayout()).apply {
    add(
        JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() })
            add(
                ActionLink(TasklaneBundle.message("board.empty.settings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, TasklaneConfigurable::class.java)
                },
            )
        },
    )
}
