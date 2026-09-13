package com.tasklane.repo

import com.intellij.openapi.project.Project
import com.tasklane.domain.model.RepositoryRef
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path

/**
 * Los repositorios Git que el IDE ya conoce. **Sólo se registra si `Git4Idea` está
 * presente** (`tasklane-git.xml`), que es lo que mantiene el core libre de
 * `git4idea`.
 *
 * Un submódulo registrado aparece como un repositorio independiente, igual que lo
 * trata el propio IDE.
 */
internal class GitRepositoryProvider : RepositoryProvider {

    override val order = RepositoryProvider.ORDER_GIT

    override fun detect(project: Project): List<DetectedRepo> =
        GitRepositoryManager.getInstance(project).repositories.mapNotNull { repository ->
            val path = runCatching { Path.of(repository.root.path).toAbsolutePath().normalize() }.getOrNull()
                ?: return@mapNotNull null
            DetectedRepo(path, RepositoryRef.Kind.GIT, repository.currentBranchName)
        }
}
