package com.tasklane.domain.model

/**
 * Un repositorio de la ventana: lo que el selector muestra y lo que aísla las tareas.
 *
 * Es un **valor del dominio**, no un objeto de la plataforma: aquí no hay ni
 * `VirtualFile` ni `GitRepository`. Esa frontera es lo que permite construir el
 * catálogo entero —profundidad, desambiguación de nombres, entradas huérfanas— con
 * Kotlin puro y testearlo sin arrancar un IDE. Ver `RepoCatalog`.
 */
data class RepositoryRef(
    val key: RepoKey,
    /** Nombre visible. Es el último segmento, o la ruta relativa si dos colisionan. */
    val displayName: String,
    /** Ruta absoluta de la raíz del repositorio. */
    val rootPath: String,
    val kind: Kind,
    /** Segmentos respecto a la raíz del proyecto. 0 = la propia raíz. */
    val depth: Int = 0,
    /** `false` = tiene tareas guardadas pero su carpeta ya no está en disco. */
    val available: Boolean = true,
    /**
     * Rama actual. Sólo informativa —va al tooltip—, nunca a la identidad: cambiar
     * de rama no puede cambiar dónde viven las tareas.
     */
    val branch: String? = null,
) {
    enum class Kind {
        /** Repositorio Git registrado en el VCS del proyecto. */
        GIT,

        /** La raíz del proyecto cuando no hay VCS: el pseudo-repositorio. */
        PROJECT_ROOT,

        /** Content root del modelo de proyecto. Sólo se usa si no hay Git. */
        CONTENT_ROOT,
    }
}
