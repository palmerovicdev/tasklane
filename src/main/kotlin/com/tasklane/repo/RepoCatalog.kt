package com.tasklane.repo

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import java.nio.file.Path

/** Lo que un [RepositoryProvider] encuentra, antes de convertirse en catálogo. */
data class DetectedRepo(
    val path: Path,
    val kind: RepositoryRef.Kind,
    val branch: String? = null,
)

/**
 * Convierte lo detectado por los proveedores —más lo que ya se conocía de
 * `layout.xml`— en la lista que ve el selector. Kotlin puro: sin `Project`, sin VFS
 * y sin tocar disco; todo lo que necesita saber del sistema de ficheros entra por
 * los dos predicados [hasTasks] y [exists]. Por eso se testea entero sin IDE.
 *
 * Tres reglas, en este orden:
 *
 *  1. **Lo que tiene datos no se oculta jamás.** Ni por profundidad ni por haber
 *     desaparecido del disco. Filtrar tareas existentes fuera de la vista es
 *     indistinguible de perderlas.
 *  2. **La profundidad filtra, no borra.** Por defecto sólo hijos directos de la
 *     raíz; lo más profundo que sobrevive por la regla 1 se marca con [beyondDepth]
 *     y el selector lo agrupa bajo «Other».
 *  3. **Los nombres se desambiguan.** Dos carpetas `api` en ramas distintas se
 *     muestran por su ruta relativa, no como dos entradas idénticas.
 */
object RepoCatalog {

    fun build(
        projectRoot: Path,
        detected: List<DetectedRepo>,
        known: List<RepositoryRef>,
        maxDepth: Int,
        hasTasks: (RepoKey) -> Boolean,
        exists: (String) -> Boolean,
    ): List<RepositoryRef> {
        val fromProviders = detected
            .map { it to RepoKeyFactory.keyFor(projectRoot, it.path) }
            // Dos proveedores pueden ver la misma carpeta; gana el primero, que es
            // el de mayor preferencia.
            .distinctBy { (_, key) -> key }
            .map { (repo, key) ->
                RepositoryRef(
                    key = key,
                    displayName = nameOf(repo.path),
                    rootPath = repo.path.toAbsolutePath().normalize().toString(),
                    kind = repo.kind,
                    depth = RepoKeyFactory.depthOf(projectRoot, repo.path),
                    available = true,
                    branch = repo.branch,
                )
            }

        val live = fromProviders.map { it.key }.toSet()

        // Lo que estuvo y ya no lo detecta nadie: sólo se conserva si tiene tareas
        // dentro. Sin datos que proteger, una entrada muerta es ruido.
        val orphans = known
            .filter { it.key !in live && hasTasks(it.key) }
            .map { it.copy(available = exists(it.rootPath), branch = null) }

        // Compatibilidad con la Fase 1: sus datos viven bajo `root` y no hay
        // `layout.xml` que los declare. Si están ahí y nadie reclama esa clave, la
        // raíz entra en el catálogo aunque el proyecto ya no la detecte como repo.
        val rootClaimed = RepoKey.ROOT in live || orphans.any { it.key == RepoKey.ROOT }
        val legacyRoot = when {
            rootClaimed || !hasTasks(RepoKey.ROOT) -> emptyList()
            else -> {
                val path = projectRoot.toAbsolutePath().normalize().toString()
                listOf(
                    RepositoryRef(
                        key = RepoKey.ROOT,
                        displayName = nameOf(projectRoot),
                        rootPath = path,
                        kind = RepositoryRef.Kind.PROJECT_ROOT,
                        depth = 0,
                        available = exists(path),
                    ),
                )
            }
        }

        val all = (fromProviders + orphans + legacyRoot)
            .filter { it.depth <= maxDepth || hasTasks(it.key) }

        return disambiguate(projectRoot, all).sortedWith(
            compareBy({ it.depth }, { it.displayName.lowercase() }),
        )
    }

    /** ¿Esta entrada sólo está aquí porque tiene datos, por debajo del filtro? */
    fun beyondDepth(ref: RepositoryRef, maxDepth: Int): Boolean = ref.depth > maxDepth

    /** Etiqueta larga de un nombre en conflicto: la ruta relativa, o la absoluta si cuelga fuera. */
    private fun relativeLabel(projectRoot: Path, ref: RepositoryRef): String =
        RepoKeyFactory.relativePath(projectRoot, Path.of(ref.rootPath))?.takeIf { it.isNotEmpty() }
            ?: ref.rootPath

    private fun nameOf(path: Path): String {
        val normalized = path.toAbsolutePath().normalize()
        return normalized.fileName?.toString()?.takeIf { it.isNotBlank() }
            ?: normalized.toString().takeIf { it.isNotBlank() }
            ?: "Project"
    }

    /**
     * Dos repositorios con el mismo nombre visible pasan a mostrarse por su ruta
     * relativa. Se hace sobre el conjunto final —y no al construir cada entrada—
     * porque una colisión sólo existe en relación con los demás.
     */
    private fun disambiguate(projectRoot: Path, refs: List<RepositoryRef>): List<RepositoryRef> {
        val clashing = refs
            .groupBy { it.displayName.lowercase() }
            .filterValues { it.size > 1 }
            .keys
        if (clashing.isEmpty()) return refs
        return refs.map { ref ->
            if (ref.displayName.lowercase() !in clashing) ref
            else ref.copy(displayName = relativeLabel(projectRoot, ref))
        }
    }
}
