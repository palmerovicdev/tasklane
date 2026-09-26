package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.CONFIG
import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.data.sqlite.StoreFixture.task
import com.tasklane.data.sqlite.StoreFixture.withStore
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.query.QueryParser
import com.tasklane.search.LinearScanIndex
import com.tasklane.search.SearchCorpus
import com.tasklane.search.SearchScope
import com.tasklane.search.TaskSearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * **La búsqueda nueva contra la vieja, sobre el mismo corpus.**
 *
 * `LinearScanIndex` se queda como implementación de referencia justamente para esto: es
 * el comportamiento que llevaba tres fases funcionando y con el que el usuario está
 * acostumbrado. Lo que este test hace es fijar las **dos** mitades:
 *
 * - lo que tiene que seguir dando exactamente lo mismo —que es casi todo—, y
 * - lo que cambia a propósito, que es el riesgo nº3 del §4 del plan y no se puede dejar
 *   sin escribir en ningún sitio.
 *
 * Lo que cambia, en una frase: el escaneo hacía `contains` sobre el cuerpo entero, así
 * que `ogin` encontraba `login`; FTS5 casa palabras y prefijos de palabra, así que
 * `ogin` deja de encontrarlo y `log` lo sigue encontrando.
 */
class Fts5IndexTest {

    private val other = RepoKey("web-1234abcd")

    private val repositories = listOf(
        RepositoryRef(REPO, "proyecto", "/p", RepositoryRef.Kind.PROJECT_ROOT),
        RepositoryRef(other, "web", "/p/web", RepositoryRef.Kind.GIT, depth = 1),
    )

    private val corpus: List<Task> = listOf(
        task("a", body = "Arreglar el login\nEl token caduca antes de tiempo", tags = listOf("api", "urgente")),
        task("b", body = "Revisión del despliegue\nMirar la configuración de nginx", tags = listOf("infra")),
        task(
            "c",
            body = "Nota suelta sobre el login y su token",
            state = TasklaneConfig.DONE,
            completedAt = Instant.parse("2026-02-01T00:00:00Z"),
            anchors = listOf(CodeAnchor.of("src/auth/AuthService.kt", 41, 0, "fun login()")),
        ),
        task("d", body = "Pulir la tarjeta\nSin nada que ver", priority = TasklaneConfig.HIGH),
        task("e", body = "Cosa del otro repo con token", repo = other),
    )

    private fun reference(): TaskSearchIndex = LinearScanIndex().apply {
        setCorpus(SearchCorpus(CONFIG, repositories, corpus))
    }

    private fun TaskSearchIndex.find(query: String, scope: SearchScope = SearchScope.Repo(REPO)) =
        search(QueryParser.parse(query), scope).map { it.task.id.value }

    private fun <T> withIndex(block: (TaskSearchIndex) -> T): T = withStore { store, db ->
        store.importBatch(corpus, CONFIG)
        block(Fts5Index(db.reader).apply { setCorpus(SearchCorpus(CONFIG, repositories)) })
    }

    /** Lo que tiene que dar lo mismo, consulta por consulta. */
    @Test
    fun `las dos implementaciones encuentran lo mismo`() = withIndex { fts ->
        val old = reference()
        val queries = listOf(
            "login",
            "token",
            "tarjeta",
            "revision",
            "revisión",
            "inexistente",
            "#api",
            "#api #urgente",
            "#infra",
            "is:done",
            "is:open token",
            "state:done",
            "p:high",
            "has:code",
            "file:authservice",
            "login token",
        )
        for (query in queries) {
            assertEquals(
                "la consulta «$query» tiene que encontrar lo mismo",
                old.find(query).toSet(),
                fts.find(query).toSet(),
            )
        }
    }

    /**
     * `has:broken-anchor` (2.13.0): qué rutas están rotas lo dice el disco, y llega con el
     * corpus. Las dos implementaciones tienen que leerlo igual, y una ruta con comillas o
     * barras invertidas no puede romper la consulta.
     */
    @Test
    fun `las anclas rotas se buscan con la lista del corpus`() = withStore { store, db ->
        store.importBatch(corpus, CONFIG)
        val broken = setOf("src/auth/AuthService.kt", "raro/\"com\\illas\".kt")
        val fts = Fts5Index(db.reader).apply { setCorpus(SearchCorpus(CONFIG, repositories, brokenAnchors = broken)) }
        val linear = LinearScanIndex().apply { setCorpus(SearchCorpus(CONFIG, repositories, corpus, broken)) }

        for (index in listOf(fts, linear)) {
            assertEquals(listOf("c"), index.find("has:broken-anchor"))
            assertEquals(listOf("c"), index.find("login has:broken"))
            assertEquals(emptyList<String>(), index.find("has:broken is:open"))
        }
        val sano = Fts5Index(db.reader).apply { setCorpus(SearchCorpus(CONFIG, repositories)) }
        assertEquals("sin rutas rotas no hay nada que encontrar", emptyList<String>(), sano.find("has:broken-anchor"))
    }

    @Test
    fun `el alcance de todos los repositorios llega al otro`() = withIndex { fts ->
        assertEquals(setOf("a", "c"), fts.find("token").toSet())
        assertEquals(setOf("a", "c", "e"), fts.find("token", SearchScope.All).toSet())
        assertEquals(setOf("e"), fts.find("repo:web token", SearchScope.All).toSet())
    }

    /**
     * El orden: un acierto en el título pesa más que uno enterrado en el cuerpo. Es lo
     * que `LinearScanIndex` conseguía apilando escalones de `TITLE_WEIGHT = 1_000_000`
     * y aquí hace `bm25()` con peso 10 en la columna del título.
     */
    @Test
    fun `el titulo manda sobre el cuerpo`() = withIndex { fts ->
        assertEquals("«Arreglar el login» antes que «…sobre el login…»", listOf("a", "c"), fts.find("login"))
    }

    /** Buscar `revision` tiene que encontrar `revisión`: es `TextNormalizer`, dentro del motor. */
    @Test
    fun `los diacriticos no cuentan`() = withIndex { fts ->
        assertEquals(listOf("b"), fts.find("revision"))
        assertEquals(listOf("b"), fts.find("REVISIÓN"))
    }

    @Test
    fun `un prefijo de palabra sigue encontrando`() = withIndex { fts ->
        assertEquals(setOf("a", "c"), fts.find("logi").toSet())
        assertEquals(setOf("a", "c"), fts.find("tok").toSet())
    }

    /**
     * **La regresión documentada.** Buscar por el medio de una palabra deja de
     * encontrar. Se acepta porque buscar desde la tercera letra es un accidente y no un
     * gesto, y porque el `contains` era justamente la operación que obligaba a recorrer
     * el corpus entero — 91 ms a 100.000 tareas.
     */
    @Test
    fun `buscar por el medio de una palabra deja de encontrar`() = withIndex { fts ->
        assertEquals("el escaneo sí lo encontraba", setOf("a", "c"), reference().find("ogin").toSet())
        assertTrue("FTS5 casa palabras y prefijos, no trozos", fts.find("ogin").isEmpty())
    }

    /** Nadie mira el resultado 201, y traerlos sería traer el corpus. */
    @Test
    fun `una busqueda nunca trae mas de doscientos`() = withStore { store, db ->
        store.importBatch((0 until 500).map { task("t$it", body = "Tarea con token $it") }, CONFIG)
        val fts = Fts5Index(db.reader).apply { setCorpus(SearchCorpus(CONFIG, repositories)) }

        assertEquals(200, fts.search(QueryParser.parse("token"), SearchScope.Repo(REPO)).size)
    }

    /** Un operador que no casa con nada no significa «ese filtro no cuenta». */
    @Test
    fun `un nombre de estado inexistente no devuelve nada`() = withIndex { fts ->
        assertTrue(fts.find("state:inexistente").isEmpty())
        assertTrue(fts.find("p:inexistente token").isEmpty())
    }

    /** Lo que el usuario escribe es texto, no una expresión de búsqueda. */
    @Test
    fun `los operadores de FTS5 no se cuelan desde el buscador`() = withIndex { fts ->
        for (raw in listOf("\"", "*", "AND", "NOT", "(", "^token")) {
            fts.find(raw) // lo que no puede hacer es lanzar
        }
    }

    // ------------------------------------------------ la consulta completa (2.21.0)

    /** Sábado 26 de septiembre de 2026, 10:00 en Madrid; la semana va del lunes 21 al domingo 27. */
    private val zone = ZoneId.of("Europe/Madrid")
    private val clock = Clock.fixed(Instant.parse("2026-09-26T08:00:00Z"), zone)

    /** Vence al acabar el día, como las deja `DueDates`. */
    private fun dueOn(date: String) = java.time.LocalDate.parse(date).atTime(java.time.LocalTime.MAX).atZone(zone).toInstant()

    private val dated: List<Task> = listOf(
        task("v1", body = "Pagar el dominio", dueDate = dueOn("2026-09-20"), tags = listOf("admin")),
        task(
            "v2",
            body = "Revisar informe\n- [ ] leer\n- [x] firmar",
            dueDate = dueOn("2026-09-26"),
            bookmarked = true,
            priority = TasklaneConfig.HIGH,
        ),
        task(
            "v3",
            body = "Cerrar sprint",
            state = TasklaneConfig.DONE,
            completedAt = Instant.parse("2026-09-24T10:00:00Z"),
            dueDate = dueOn("2026-09-22"),
            tags = listOf("wip"),
        ),
        // Los dos siguientes pasan el colador de SQL de `has:checklist` y no son listas:
        // un `[x]` en medio de la frase y una casilla dentro de un bloque de código.
        task("v4", body = "Nota con [x] en medio del texto", createdAt = Instant.parse("2026-09-25T09:00:00Z")),
        task("v5", body = "Ejemplo de código\n```\n- [ ] dentro de la valla\n```"),
        task("v6", body = "Plan largo\n1. [ ] numerado", dueDate = dueOn("2026-10-10")),
    )

    /**
     * Lo nuevo de la consulta, en las dos implementaciones y contra lo esperado. La consulta
     * se interpreta **una vez** con el reloj fijo: `is:overdue` y las fechas dependen de él.
     */
    @Test
    fun `los operadores nuevos y la negacion encuentran lo mismo en las dos`() = withStore { store, db ->
        store.importBatch(dated, CONFIG)
        val fts = Fts5Index(db.reader).apply { setCorpus(SearchCorpus(CONFIG, repositories)) }
        val linear = LinearScanIndex().apply { setCorpus(SearchCorpus(CONFIG, repositories, dated)) }
        val all = dated.map { it.id.value }.toSet()

        val expected = mapOf(
            "is:overdue" to setOf("v1"),
            "is:bookmarked" to setOf("v2"),
            "has:due" to setOf("v1", "v2", "v3", "v6"),
            "has:checklist" to setOf("v2", "v6"),
            "-has:checklist" to setOf("v1", "v3", "v4", "v5"),
            "has:tag" to setOf("v1", "v3"),
            "-has:tag" to setOf("v2", "v4", "v5", "v6"),
            "-#wip" to all - "v3",
            "-is:done" to all - "v3",
            "-p:high" to all - "v2",
            "-dominio" to all - "v1",
            "-state:inexistente" to all,
            "due:today" to setOf("v2"),
            "due:<7d" to setOf("v1", "v2", "v3"),
            "due:week" to setOf("v2", "v3"),
            "-due:week" to all - "v2" - "v3",
            "due:>2026-09-30" to setOf("v6"),
            "closed:week" to setOf("v3"),
            "closed:yesterday" to emptySet(),
            "created:>2026-09-01" to setOf("v4"),
            "created:<7d" to setOf("v4"),
            "revisar -leer" to emptySet(),
            "revisar -pagar" to setOf("v2"),
            "cerrar -#wip" to emptySet(),
            "cerrar -#admin" to setOf("v3"),
            "is:overdue -#admin" to emptySet(),
            "has:checklist is:bookmarked" to setOf("v2"),
            "has:checklist -has:checklist" to emptySet(),
            "-is:overdue -is:done -has:checklist" to setOf("v4", "v5"),
        )
        for ((raw, want) in expected) {
            val query = QueryParser.parse(raw, clock, DayOfWeek.MONDAY)
            for ((name, index) in listOf("FTS5" to fts, "escaneo" to linear)) {
                assertEquals(
                    "«$raw» en $name",
                    want,
                    index.search(query, SearchScope.Repo(REPO)).map { it.task.id.value }.toSet(),
                )
            }
        }
    }

    /**
     * Lo que se remata en Kotlin no puede quedarse en la primera página. Doscientas tareas
     * recientes con lista y, detrás, las cinco que no la tienen: `-has:checklist` tiene que
     * llegar a ellas.
     */
    @Test
    fun `lo que se remata en Kotlin recorre mas alla de la primera pagina`() = withStore { store, db ->
        val base = Instant.parse("2026-09-01T00:00:00Z")
        val lists = (0 until 1_200).map {
            task("l$it", body = "Lista $it\n- [ ] paso", updatedAt = base.plusSeconds(10_000L + it))
        }
        val plain = (0 until 5).map { task("p$it", body = "Sin lista $it", updatedAt = base.plusSeconds(it.toLong())) }
        store.importBatch(lists + plain, CONFIG)
        val fts = Fts5Index(db.reader).apply { setCorpus(SearchCorpus(CONFIG, repositories)) }

        assertEquals(plain.map { it.id.value }.toSet(), fts.find("-has:checklist").toSet())
        assertEquals(200, fts.find("has:checklist").size)
    }
}
