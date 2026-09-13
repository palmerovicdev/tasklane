package com.tasklane.repo

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.tasklane.domain.model.RepositoryRef
import java.nio.file.Path

/**
 * Respaldo para cuando no hay Git —o el plugin está desactivado—: la raíz del
 * proyecto como pseudo-repositorio, más los content roots del modelo de proyecto.
 *
 * Sólo se usa si el proveedor de Git no devuelve nada, así que en un proyecto normal
 * con repositorios registrados esto no llega a ejecutarse.
 */
internal class ContentRootProvider : RepositoryProvider {

    override val order = RepositoryProvider.ORDER_CONTENT_ROOT

    override fun detect(project: Project): List<DetectedRepo> {
        val base = project.basePath?.let(::pathOf) ?: return emptyList()
        val roots = ProjectRootManager.getInstance(project).contentRoots.mapNotNull { pathOf(it.path) }

        // La raíz siempre primero: es la que se queda con RepoKey.ROOT y la que hace
        // de pseudo-repositorio «Project» cuando el proyecto no tiene VCS.
        return (listOf(base) + roots).distinct().map { path ->
            DetectedRepo(
                path = path,
                kind = if (path == base) RepositoryRef.Kind.PROJECT_ROOT else RepositoryRef.Kind.CONTENT_ROOT,
            )
        }
    }

    private fun pathOf(raw: String): Path? = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull()
}
