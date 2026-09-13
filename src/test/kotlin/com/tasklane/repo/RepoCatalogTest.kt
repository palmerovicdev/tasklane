package com.tasklane.repo

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class RepoCatalogTest {

    private val root: Path = Path.of("/home/vic/proyecto")

    private fun path(vararg segments: String): Path = segments.fold(root) { acc, s -> acc.resolve(s) }

    private fun git(vararg segments: String, branch: String? = null) =
        DetectedRepo(path(*segments), RepositoryRef.Kind.GIT, branch)

    private fun build(
        detected: List<DetectedRepo> = emptyList(),
        known: List<RepositoryRef> = emptyList(),
        maxDepth: Int = 1,
        withTasks: Set<RepoKey> = emptySet(),
        onDisk: Set<String> = emptySet(),
    ) = RepoCatalog.build(
        projectRoot = root,
        detected = detected,
        known = known,
        maxDepth = maxDepth,
        hasTasks = { it in withTasks },
        exists = { it in onDisk },
    )

    private fun keyOf(vararg segments: String) = RepoKeyFactory.keyFor(root, path(*segments))

    // ------------------------------------------------------------ deteccion

    @Test
    fun `cada repositorio detectado entra con su clave y su nombre`() {
        val refs = build(listOf(git(), git("api"), git("web")))

        assertEquals(listOf("proyecto", "api", "web"), refs.map { it.displayName })
        assertEquals(RepoKey.ROOT, refs.first().key)
        assertTrue(refs.all { it.available })
    }

    @Test
    fun `la raiz va primero y el resto por nombre`() {
        val refs = build(listOf(git("web"), git("api"), git()))
        assertEquals(listOf("proyecto", "api", "web"), refs.map { it.displayName })
    }

    @Test
    fun `la misma carpeta vista por dos proveedores es un solo repositorio`() {
        val refs = build(
            listOf(
                git("api"),
                DetectedRepo(path("api"), RepositoryRef.Kind.CONTENT_ROOT),
            ),
        )
        assertEquals(1, refs.size)
        assertEquals("gana el proveedor de mayor preferencia", RepositoryRef.Kind.GIT, refs.single().kind)
    }

    @Test
    fun `la rama viaja hasta el catalogo`() {
        assertEquals("main", build(listOf(git("api", branch = "main"))).single().branch)
    }

    // --------------------------------------------------------- profundidad

    @Test
    fun `por defecto solo entran los hijos directos`() {
        val refs = build(listOf(git(), git("api"), git("libs", "interno")))
        assertEquals(listOf("proyecto", "api"), refs.map { it.displayName })
    }

    @Test
    fun `un repositorio profundo con tareas nunca se oculta`() {
        val profundo = keyOf("libs", "interno")
        val refs = build(
            detected = listOf(git(), git("libs", "interno")),
            withTasks = setOf(profundo),
        )

        assertEquals(listOf(RepoKey.ROOT, profundo), refs.map { it.key })
        assertTrue(
            "y se marca para que el selector lo mande a «Other»",
            RepoCatalog.beyondDepth(refs.last(), maxDepth = 1),
        )
        assertFalse(RepoCatalog.beyondDepth(refs.first(), maxDepth = 1))
    }

    @Test
    fun `subir la profundidad deja de filtrar`() {
        val refs = build(listOf(git(), git("libs", "interno")), maxDepth = 2)
        assertEquals(2, refs.size)
    }

    // ------------------------------------------------------- desaparecidos

    @Test
    fun `un repositorio que se va del disco se conserva deshabilitado si tenia tareas`() {
        val api = RepositoryRef(keyOf("api"), "api", path("api").toString(), RepositoryRef.Kind.GIT, depth = 1)
        val refs = build(
            detected = listOf(git()),
            known = listOf(api),
            withTasks = setOf(api.key),
        )

        val huerfano = refs.single { it.key == api.key }
        assertFalse("la carpeta ya no esta", huerfano.available)
        assertEquals("pero sus tareas siguen alcanzables", "api", huerfano.displayName)
    }

    @Test
    fun `un repositorio que se va sin tareas dentro desaparece del selector`() {
        val api = RepositoryRef(keyOf("api"), "api", path("api").toString(), RepositoryRef.Kind.GIT, depth = 1)
        val refs = build(detected = listOf(git()), known = listOf(api))
        assertEquals(listOf(RepoKey.ROOT), refs.map { it.key })
    }

    @Test
    fun `desregistrar del VCS una carpeta que sigue en disco la deja disponible`() {
        val api = RepositoryRef(keyOf("api"), "api", path("api").toString(), RepositoryRef.Kind.GIT, depth = 1)
        val refs = build(
            detected = listOf(git()),
            known = listOf(api),
            withTasks = setOf(api.key),
            onDisk = setOf(api.rootPath),
        )
        assertTrue(refs.single { it.key == api.key }.available)
    }

    // -------------------------------------------------- compatibilidad v0.1

    @Test
    fun `los datos de la Fase 1 aparecen aunque la raiz ya no sea un repositorio`() {
        val refs = build(
            detected = listOf(git("api")),
            withTasks = setOf(RepoKey.ROOT),
            onDisk = setOf(root.toString()),
        )

        val raiz = refs.single { it.key == RepoKey.ROOT }
        assertEquals(RepositoryRef.Kind.PROJECT_ROOT, raiz.kind)
        assertTrue(raiz.available)
    }

    @Test
    fun `si la raiz ya esta detectada no se duplica`() {
        val refs = build(detected = listOf(git()), withTasks = setOf(RepoKey.ROOT))
        assertEquals(1, refs.count { it.key == RepoKey.ROOT })
    }

    // ---------------------------------------------------------- ambiguedad

    @Test
    fun `dos carpetas con el mismo nombre se muestran por su ruta`() {
        val refs = build(listOf(git("backend", "api"), git("frontend", "api")), maxDepth = 2)
        assertEquals(listOf("backend/api", "frontend/api"), refs.map { it.displayName })
    }

    @Test
    fun `un nombre que no colisiona se queda corto`() {
        val refs = build(listOf(git("backend", "api"), git("frontend", "web")), maxDepth = 2)
        assertEquals(listOf("api", "web"), refs.map { it.displayName })
    }
}
