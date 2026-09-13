package com.tasklane.repo

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Quién sabe qué repositorios hay en la ventana.
 *
 * Es un punto de extensión y no una llamada directa por una razón concreta: la
 * implementación Git vive detrás de `Git4Idea` y **no puede** referenciarse desde el
 * core. Cualquier `import git4idea.*` en una clase del core provoca
 * `NoClassDefFoundError` en un IDE con el plugin Git desactivado. Registrándola en
 * el descriptor opcional `tasklane-git.xml`, la clase ni se carga si no hay Git.
 *
 * No se escanea el disco buscando carpetas `.git`: el IDE ya mantiene ese modelo.
 */
interface RepositoryProvider {

    /** Menor = se consulta antes. El primero que devuelva algo gana. */
    val order: Int

    /**
     * Se invoca dentro de una read action y fuera del EDT. Debe ser barato: se
     * vuelve a llamar en cada evento de VCS.
     */
    fun detect(project: Project): List<DetectedRepo>

    companion object {
        val EP: ExtensionPointName<RepositoryProvider> =
            ExtensionPointName.create("com.tasklane.repositoryProvider")

        const val ORDER_GIT = 0
        const val ORDER_CONTENT_ROOT = 100
    }
}
