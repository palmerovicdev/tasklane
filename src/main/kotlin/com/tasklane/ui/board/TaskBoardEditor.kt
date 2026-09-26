package com.tasklane.ui.board

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.tasklane.TasklaneBundle
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * El tablero en una pestaña del editor (2.17.0). Ver [TaskBoard].
 *
 * [DumbAware] como la tool window: las tareas no dependen de los índices, y el tablero
 * tiene que abrirse —y reabrirse con el proyecto— mientras el IDE indexa.
 */
internal class TaskBoardEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is TaskBoardFile

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = TaskBoardEditor(project, file)

    override fun getEditorTypeId(): String = "tasklane-board"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

internal class TaskBoardEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    /** Con la pestaña se cierran las columnas, y con ellas lo que escuchaban. */
    private val board = TaskBoard(project).also { Disposer.register(this, it) }

    override fun getComponent(): JComponent = board

    override fun getPreferredFocusedComponent(): JComponent? = board.preferredFocus

    override fun getName(): String = TasklaneBundle.message("board.tab")

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() = Unit
}

/**
 * *Open Board*: abre el tablero en el editor, o va a él si ya está abierto. En la barra de
 * la ventana y en *Tools*; sin atajo, como las demás que se abren de tarde en tarde, y
 * asignable desde el *Keymap*.
 */
internal class OpenBoardAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        TaskBoardFiles.open(e.project ?: return)
    }
}
