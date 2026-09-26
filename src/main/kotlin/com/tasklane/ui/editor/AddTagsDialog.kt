package com.tasklane.ui.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.TasklaneConfig
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * *Tags ▸ Add…* (P31): las etiquetas que se suman a las [count] tareas de la selección.
 *
 * Es el campo de fichas del diálogo de la tarea, con sus sugerencias de [repo]: lo que
 * evita que nazca `#apis` al lado de `#api` es ver la que ya existe mientras se escribe, y
 * eso vale igual para una tarea que para cuarenta. Intro acepta, como allí; con la lista
 * de sugerencias abierta, elige.
 */
internal class AddTagsDialog(
    project: Project,
    repo: RepoKey,
    config: TasklaneConfig,
    count: Int,
) : DialogWrapper(project) {

    private val chips = TagChipsField(project, repo, emptyList(), config, disposable)

    /** Lo que se escribió, ya partido y sin repetidas. Sólo tiene sentido si se aceptó. */
    val tags: List<String> get() = chips.tags

    init {
        title = TasklaneBundle.message("dialog.addTags.title", count)
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(chips, BorderLayout.CENTER)
        preferredSize = Dimension(JBUI.scale(WIDTH), preferredSize.height)
    }

    override fun getPreferredFocusedComponent(): JComponent = chips.focusTarget

    private companion object {
        const val WIDTH = 320
    }
}
