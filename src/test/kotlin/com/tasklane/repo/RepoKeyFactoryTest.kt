package com.tasklane.repo

import com.tasklane.domain.model.RepoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class RepoKeyFactoryTest {

    private val root = Path.of("/home/vic/proyecto")

    private fun key(vararg segments: String) =
        RepoKeyFactory.keyFor(root, segments.fold(root) { acc, s -> acc.resolve(s) })

    @Test
    fun `la raiz del proyecto conserva la clave de la Fase 1`() {
        assertEquals(
            "cambiarla huerfanaria los datos ya escritos, y no hay nada con lo que pueda colisionar",
            RepoKey.ROOT,
            key(),
        )
    }

    @Test
    fun `dos carpetas con el mismo nombre en ramas distintas no colisionan`() {
        assertNotEquals(key("backend", "api"), key("frontend", "api"))
    }

    @Test
    fun `la clave lleva la ruta legible por delante`() {
        assertTrue(key("backend", "api").value, key("backend", "api").value.startsWith("backend-api-"))
    }

    @Test
    fun `la clave es estable entre llamadas`() {
        assertEquals(key("api"), key("api"))
    }

    @Test
    fun `mover el proyecto entero no cambia ninguna clave`() {
        val movido = Path.of("/mnt/otro-disco/proyecto-renombrado")
        assertEquals(
            "los datos viven dentro de .idea y se mueven con el proyecto: perderlos no tendria sentido",
            RepoKeyFactory.keyFor(root, root.resolve("api")),
            RepoKeyFactory.keyFor(movido, movido.resolve("api")),
        )
    }

    @Test
    fun `dos nombres que se normalizan igual siguen siendo repos distintos`() {
        val conEspacio = RepoKeyFactory.keyFor(root, root.resolve("mi api"))
        val conGuion = RepoKeyFactory.keyFor(root, root.resolve("mi-api"))
        assertEquals("el slug si colisiona", "mi-api", conEspacio.value.substringBeforeLast('-'))
        assertNotEquals("para eso esta el hash", conEspacio, conGuion)
    }

    @Test
    fun `un repositorio fuera del proyecto tambien tiene clave`() {
        val fuera = RepoKeyFactory.keyFor(root, Path.of("/home/vic/otra-cosa"))
        assertNotEquals(RepoKey.ROOT, fuera)
        assertEquals("y se muestra, no se esconde", 1, RepoKeyFactory.depthOf(root, Path.of("/home/vic/otra-cosa")))
    }

    @Test
    fun `la profundidad cuenta segmentos desde la raiz`() {
        assertEquals(0, RepoKeyFactory.depthOf(root, root))
        assertEquals(1, RepoKeyFactory.depthOf(root, root.resolve("api")))
        assertEquals(3, RepoKeyFactory.depthOf(root, root.resolve("a/b/c")))
    }

    @Test
    fun `la ruta relativa usa siempre barras`() {
        assertEquals("backend/api", RepoKeyFactory.relativePath(root, root.resolve("backend").resolve("api")))
        assertEquals("", RepoKeyFactory.relativePath(root, root))
    }
}
