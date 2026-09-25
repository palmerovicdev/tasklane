package com.tasklane.ui.quickadd

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.code.TodoComments
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Qué TODO del proyecto pasan a Tasklane. Marcados todos de salida **menos los que ya
 * están**: importar dos veces no puede duplicar la lista. Ver [ImportTodosAction].
 */
internal class ImportTodosDialog(
    project: Project,
    private val found: List<TodoComments.Found>,
    private val imported: Set<TodoComments.Found>,
) : DialogWrapper(project) {

    private val list = CheckBoxList<TodoComments.Found>().apply {
        for (todo in found) {
            val already = todo in imported
            val suffix = if (already) "  " + TasklaneBundle.message("import.todos.already") else ""
            addItem(todo, "${todo.path}:${todo.line + 1}  —  ${todo.text}$suffix", !already)
        }
    }

    private val tagKeyword = JBCheckBox(TasklaneBundle.message("import.todos.tag"), true)

    init {
        title = TasklaneBundle.message("import.todos.title")
        setOKButtonText(TasklaneBundle.message("import.todos.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(GAP))).apply {
        val fresh = found.size - imported.size
        add(
            JBLabel(TasklaneBundle.message("import.todos.summary", found.size, fresh)).apply {
                foreground = UIUtil.getContextHelpForeground()
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(WIDTH, HEIGHT) }, BorderLayout.CENTER)
        add(tagKeyword, BorderLayout.SOUTH)
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    val selected: List<TodoComments.Found> get() = found.filter(list::isItemSelected)

    val tagWithKeyword: Boolean get() = tagKeyword.isSelected

    private companion object {
        const val GAP = 8
        const val WIDTH = 640
        const val HEIGHT = 360
    }
}
