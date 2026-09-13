package com.tasklane.repo

import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Traduce los eventos de VCS a un `refresh()` del registro. Vive en el descriptor
 * opcional de Git, así que ni se carga sin `Git4Idea`.
 *
 * El evento que importa es `VCS_REPOSITORY_MAPPING_UPDATED`, no `GIT_REPO_CHANGE`:
 * el primero se dispara cuando cambian los *mappings* —añadir o quitar un
 * repositorio— y con las instancias ya creadas. `GIT_REPO_CHANGE` se suscribe sólo
 * para refrescar la rama que se enseña en el tooltip; el registro descarta la
 * recomputación si nada cambió, así que no cuesta nada.
 */
internal class VcsMappingBridge(private val project: Project) :
    VcsRepositoryMappingListener,
    GitRepositoryChangeListener {

    override fun mappingChanged() = refresh()

    override fun repositoryChanged(repository: GitRepository) = refresh()

    private fun refresh() {
        if (project.isDisposed) return
        RepositoryRegistry.getInstance(project).refresh()
    }
}
