package com.tasklane.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.repo.RepoCatalog
import com.tasklane.service.TaskService
import javax.swing.Icon
import javax.swing.JComponent

/**
 * El selector de repositorio de la cabecera de la Tool Window.
 *
 * Va en `setTitleActions` y no en la barra de cada pestaña por una razón simple: el
 * repositorio es de la ventana entera, no de un estado. Puesto en la barra habría
 * que pintar —y sincronizar— uno por pestaña diciendo todos lo mismo.
 *
 * Con un solo repositorio se esconde: no hay nada que elegir, y el nombre ya sale en
 * el título. Ver `docs/architecture.html` §7.
 */
internal class RepoSelectorAction(private val project: Project) : ComboBoxAction(), DumbAware {

    init {
        isSmallVariant = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val snapshot = TaskService.getInstance(project).snapshot.value
        val active = snapshot.activeRepository
        e.presentation.isVisible = snapshot.repositories.size > 1
        e.presentation.text = active?.displayName.orEmpty()
        e.presentation.icon = active?.let(::iconOf)
        e.presentation.description = active?.let(::tooltipOf)
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val snapshot = TaskService.getInstance(project).snapshot.value
        val maxDepth = snapshot.config.repoDepth
        val group = DefaultActionGroup()

        var separatorPending = true
        for (ref in snapshot.repositories) {
            // Lo que sobrevive al filtro de profundidad sólo porque tiene tareas se
            // agrupa aparte: sigue siendo alcanzable, pero no compite con los repos
            // que el usuario sí espera ver.
            if (separatorPending && group.childrenCount > 0 && RepoCatalog.beyondDepth(ref, maxDepth)) {
                group.add(Separator(TasklaneBundle.message("toolwindow.repo.other")))
                separatorPending = false
            }
            group.add(SelectRepoAction(ref))
        }
        return group
    }

    private inner class SelectRepoAction(private val ref: RepositoryRef) : DumbAwareAction(
        label(ref),
        tooltipOf(ref),
        iconOf(ref),
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            TaskService.getInstance(project).selectRepo(ref.key)
        }
    }

    private fun label(ref: RepositoryRef): String =
        if (ref.available) ref.displayName
        else TasklaneBundle.message("toolwindow.repo.missing", ref.displayName)

    private fun iconOf(ref: RepositoryRef): Icon = when {
        !ref.available -> AllIcons.General.Warning
        ref.kind == RepositoryRef.Kind.GIT -> AllIcons.Vcs.Branch
        else -> AllIcons.Nodes.Folder
    }

    private fun tooltipOf(ref: RepositoryRef): String = buildString {
        append(ref.rootPath)
        ref.branch?.let { append(" · ").append(it) }
        if (!ref.available) append(" · ").append(TasklaneBundle.message("toolwindow.repo.missing.tooltip"))
    }
}
