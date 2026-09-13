package com.tasklane.data

import com.tasklane.data.store.RepoLayoutCodec
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RepoLayoutCodecTest {

    private val api = RepositoryRef(
        key = RepoKey("api-a3f91d0e"),
        displayName = "api",
        rootPath = "/home/vic/proyecto/api",
        kind = RepositoryRef.Kind.GIT,
        depth = 1,
        available = true,
        branch = "main",
    )

    @Test
    fun `ida y vuelta conserva la identidad del repositorio`() {
        val decoded = RepoLayoutCodec.decode(RepoLayoutCodec.encode(listOf(api))).single()

        assertEquals(api.key, decoded.key)
        assertEquals(api.displayName, decoded.displayName)
        assertEquals(api.rootPath, decoded.rootPath)
        assertEquals(api.kind, decoded.kind)
        assertEquals(api.depth, decoded.depth)
    }

    @Test
    fun `lo que es estado vivo no se guarda`() {
        val decoded = RepoLayoutCodec.decode(RepoLayoutCodec.encode(listOf(api))).single()

        assertFalse("si esta en disco se comprueba al arrancar, no se cree del fichero", decoded.available)
        assertNull("la rama de la sesion anterior no significa nada", decoded.branch)
    }

    @Test
    fun `una entrada sin clave o sin ruta se salta sin tumbar el resto`() {
        val root = RepoLayoutCodec.encode(listOf(api))
        root.addContent(Element("repo").setAttribute("name", "roto"))

        assertEquals(listOf(api.key), RepoLayoutCodec.decode(root).map { it.key })
    }

    @Test
    fun `un kind desconocido cae en el valor seguro`() {
        val root = RepoLayoutCodec.encode(listOf(api))
        root.getChild("repo").setAttribute("kind", "QUANTUM")

        assertEquals(RepositoryRef.Kind.CONTENT_ROOT, RepoLayoutCodec.decode(root).single().kind)
    }

    @Test(expected = RepoLayoutCodec.DecodeException::class)
    fun `una raiz que no es la nuestra es un error, no una lista vacia`() {
        RepoLayoutCodec.decode(Element("otracosa"))
    }
}
