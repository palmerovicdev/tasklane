package com.tasklane.data.store

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.JDOMUtil
import com.tasklane.domain.model.RepositoryRef
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Lee y escribe `.idea/tasklane/layout.xml`.
 *
 * IO bloqueante: invocar desde `Dispatchers.IO`. A diferencia de `tasks.xml` no hay
 * escritura atómica ni `.bak`, y es deliberado: aquí no hay contenido de autoría.
 * Si el fichero se pierde o se corrompe, el catálogo se reconstruye entero desde los
 * proveedores en el siguiente arranque.
 */
class RepoLayoutStore(private val layout: StorageLayout) {

    fun read(): List<RepositoryRef> {
        val file = layout.layoutFile()
        if (!Files.exists(file)) return emptyList()
        return try {
            RepoLayoutCodec.decode(JDOMUtil.load(file))
        } catch (e: Exception) {
            thisLogger().warn("Tasklane: no se pudo leer $file", e)
            emptyList()
        }
    }

    fun write(refs: List<RepositoryRef>) {
        val file = layout.layoutFile()
        Files.createDirectories(file.parent)
        layout.ensureIgnored()
        val xml = JDOMUtil.writeElement(RepoLayoutCodec.encode(refs))
        Files.writeString(file, xml, StandardCharsets.UTF_8)
    }
}
