package com.tasklane.data.store

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import org.jdom.Element

/**
 * Mapeo entre el catálogo de repositorios y `layout.xml`, a mano sobre JDOM como
 * [TasksCodec].
 *
 * Este fichero **no es la verdad**: la verdad la producen los proveedores en cada
 * arranque. Es lo último que se supo, y existe por dos motivos concretos:
 *
 *  - Al abrir el proyecto, `GitRepositoryManager` puede devolver una lista vacía
 *    durante los primeros milisegundos. Pintar el selector desde aquí evita el
 *    parpadeo; el primer evento de VCS lo corrige.
 *  - Un repositorio que desaparece del disco no deja rastro en ningún proveedor.
 *    Sin este registro no habría forma de saber que aquellas tareas eran suyas.
 *
 * Por eso `available` y `branch` **no se escriben**: son estado vivo, y leerlos de
 * un fichero de la sesión anterior sería mentir.
 */
object RepoLayoutCodec {

    const val CURRENT_VERSION = 1

    private const val ROOT = "layout"
    private const val REPO = "repo"

    fun encode(refs: List<RepositoryRef>): Element {
        val root = Element(ROOT)
        root.setAttribute("version", CURRENT_VERSION.toString())
        for (ref in refs) {
            root.addContent(
                Element(REPO).apply {
                    setAttribute("key", ref.key.value)
                    setAttribute("name", ref.displayName)
                    setAttribute("path", ref.rootPath)
                    setAttribute("kind", ref.kind.name)
                    setAttribute("depth", ref.depth.toString())
                },
            )
        }
        return root
    }

    /** @throws DecodeException si el XML no es interpretable. */
    fun decode(root: Element): List<RepositoryRef> {
        if (root.name != ROOT) throw DecodeException("raíz <${root.name}>, se esperaba <$ROOT>")
        return root.getChildren(REPO).mapNotNull(::decodeRepo)
    }

    /** Una entrada rota se salta; el catálogo se reconstruye igual desde los proveedores. */
    private fun decodeRepo(el: Element): RepositoryRef? {
        val key = el.getAttributeValue("key")?.takeIf { it.isNotBlank() } ?: return null
        val path = el.getAttributeValue("path")?.takeIf { it.isNotBlank() } ?: return null
        val kind = RepositoryRef.Kind.entries
            .firstOrNull { it.name == el.getAttributeValue("kind") }
            ?: RepositoryRef.Kind.CONTENT_ROOT
        return RepositoryRef(
            key = RepoKey(key),
            displayName = el.getAttributeValue("name")?.takeIf { it.isNotBlank() } ?: key,
            rootPath = path,
            kind = kind,
            depth = el.getAttributeValue("depth")?.toIntOrNull() ?: 0,
            // Se recalculan en cada arranque; ver el KDoc de la clase.
            available = false,
            branch = null,
        )
    }

    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
