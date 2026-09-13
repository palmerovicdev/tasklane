package com.tasklane.repo

import com.tasklane.domain.model.RepoKey
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Clave estable de un repositorio dentro del proyecto abierto.
 *
 *     repoKey = "${slug(rutaRelativaAlProyecto)}-${sha256(esa misma ruta).take(8)}"
 *     // backend-api-a3f91d0e   ·   root
 *
 * El slug hace legibles los directorios en disco; el hash separa dos rutas cuyo
 * slug coincidiría tras normalizar («my api» y «my-api», o dos rutas largas que se
 * truncan igual).
 *
 * **Corrección respecto a `docs/architecture.html` §7.** El documento hashea la ruta
 * *canónica absoluta*. Se hashea la **relativa al proyecto** por dos motivos:
 *
 *  - Mover o renombrar la carpeta del proyecto no cambia ninguna clave. Con la ruta
 *    absoluta, mover el proyecto huerfanaría de golpe todas las tareas — y los datos
 *    viven dentro de `.idea/`, así que se mueven con él: no hay nada que justifique
 *    perderlos.
 *  - Lo que el hash tenía que evitar —«dos carpetas `api` en ramas distintas del
 *    árbol»— lo evita igual, porque esas dos rutas relativas ya son distintas.
 *
 * La raíz del proyecto conserva la clave desnuda [RepoKey.ROOT]. Su ruta relativa es
 * vacía, así que no puede colisionar con nada y el hash no aportaría un solo bit; de
 * paso, los datos escritos por la Fase 1 —que ya usaban `root`— siguen donde estaban
 * sin necesidad de migración.
 */
object RepoKeyFactory {

    private const val MAX_SLUG = 40

    fun keyFor(projectRoot: Path, repoPath: Path): RepoKey {
        val segments = relativeSegments(projectRoot, repoPath)
        if (segments != null && segments.isEmpty()) return RepoKey.ROOT
        val id = segments?.joinToString("/") ?: normalize(repoPath).toString()
        return RepoKey("${slug(id)}-${hash(id)}")
    }

    /**
     * Profundidad para el filtro del selector. Un repositorio fuera de la raíz del
     * proyecto —un content root adjuntado desde otro sitio— cuenta como 1: es
     * excepcional, pero esconderlo por no saber medirlo sería peor.
     */
    fun depthOf(projectRoot: Path, repoPath: Path): Int =
        relativeSegments(projectRoot, repoPath)?.size ?: 1

    /** Ruta relativa con `/` siempre, para que la clave no dependa del sistema. */
    fun relativePath(projectRoot: Path, repoPath: Path): String? =
        relativeSegments(projectRoot, repoPath)?.joinToString("/")

    /** @return los segmentos, vacío si es la propia raíz, o `null` si cuelga fuera. */
    private fun relativeSegments(projectRoot: Path, repoPath: Path): List<String>? {
        val root = normalize(projectRoot)
        val path = normalize(repoPath)
        if (path == root) return emptyList()
        if (!path.startsWith(root)) return null
        return root.relativize(path).map { it.toString() }.filter { it.isNotEmpty() }
    }

    private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

    private fun slug(raw: String): String {
        val slug = buildString {
            for (ch in raw.lowercase()) {
                val ok = ch in 'a'..'z' || ch in '0'..'9'
                if (ok) append(ch) else if (lastOrNull() != '-') append('-')
            }
        }.trim('-')
        return slug.take(MAX_SLUG).trim('-').ifEmpty { "repo" }
    }

    private fun hash(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { "%02x".format(it) }
}
