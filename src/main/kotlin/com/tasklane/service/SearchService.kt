package com.tasklane.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.diagnostics.TasklaneMetrics
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.domain.query.QueryParser
import com.tasklane.domain.query.TaskQuery
import com.tasklane.search.SearchCorpus
import com.tasklane.search.SearchScope
import com.tasklane.search.TaskSearchIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.milliseconds

/**
 * Resultado de una búsqueda, listo para pintar.
 *
 * [matches] nulo significa **sin consulta activa**, que no es lo mismo que «cero
 * resultados»: en el primer caso el árbol enseña todo y en el segundo enseña nada.
 * Colapsar los dos en una lista vacía es el bug clásico de este componente.
 */
data class SearchResults(
    val raw: String,
    val query: TaskQuery,
    /** `TaskId` → puntuación, o `null` si no hay consulta. */
    val matches: Map<TaskId, Int>?,
    /**
     * Las tareas que casaron, como mucho doscientas.
     *
     * Están aquí desde la Fase 3 y es un cambio de reparto, no un añadido: buscando, el
     * universo de la ventana **es** el resultado de la búsqueda, y quien lo tiene ya
     * leído es el índice. Antes la lista se sacaba del corpus en memoria filtrando por
     * [matches]; sin corpus, traerlas dos veces sería pedirle a SQLite las mismas
     * doscientas filas que acaba de devolver.
     */
    val tasks: List<com.tasklane.domain.model.Task> = emptyList(),
    /**
     * Matcher para resaltar, construido una vez por consulta y no una por fila. Va
     * aquí y no en el renderer porque construirlo cuesta, y el renderer se invoca
     * una vez por fila visible en cada repintado.
     */
    val highlighter: MinusculeMatcher?,
) {
    val active: Boolean get() = matches != null

    fun scoreOf(id: TaskId): Int = matches?.get(id) ?: 0

    fun accepts(id: TaskId): Boolean = matches?.containsKey(id) ?: true

    companion object {
        val NONE = SearchResults("", TaskQuery.EMPTY, null, emptyList(), null)
    }
}

/**
 * La búsqueda de la tool window: mantiene la consulta, la ejecuta y publica el
 * resultado. La UI escribe en [setQuery] y observa [results]; no conoce el índice.
 *
 * El pipeline es el que dicta §8: `debounce` para no buscar en cada tecla, y
 * `mapLatest` para que la consulta anterior se **cancele** en vez de encolarse —con
 * lo que teclear rápido no acumula trabajo—. Todo fuera del EDT.
 *
 * El `debounce` cuelga sólo del texto. Un snapshot nuevo —alguien completó una
 * tarea— atraviesa el pipeline sin retardo: los resultados no pueden quedarse
 * describiendo un modelo que ya cambió.
 */
@Service(Service.Level.PROJECT)
class SearchService(
    project: Project,
    scope: CoroutineScope,
) {

    private val workspace = TasklaneWorkspaceService.getInstance(project)
    private val tasks = TaskService.getInstance(project)
    private val index: TaskSearchIndex = tasks.searchIndex
    private val metrics = TasklaneMetrics.getInstance(project)

    private val _rawQuery = MutableStateFlow("")
    val rawQuery: StateFlow<String> = _rawQuery.asStateFlow()

    private val _allRepos = MutableStateFlow(workspace.searchAllRepos)
    val allRepos: StateFlow<Boolean> = _allRepos.asStateFlow()

    private data class Input(val raw: String, val allRepos: Boolean, val snapshot: TasklaneSnapshot)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchResults> =
        combine(_rawQuery.debounce(DEBOUNCE), _allRepos, tasks.snapshot, ::Input)
            .mapLatest { input ->
                // El contexto se refresca aquí dentro, en el mismo hilo que va a
                // buscar. Hacerlo en una corrutina aparte abriría una ventana en la
                // que los resultados hablan de una configuración y el árbol pinta otra.
                //
                // Desde la Fase 3 esto ya no es el corpus: son los nombres que hay que
                // traducir a ids —`state:`, `p:`, `repo:`—. El índice invertido vive en
                // disco, así que lo que antes costaba 8 ms por tecla a 100.000 tareas
                // ahora es copiar dos referencias.
                metrics.time(TasklaneMetrics.Op.SEARCH) {
                    index.setCorpus(corpusOf(input.snapshot))
                    compute(input)
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, SearchResults.NONE)

    fun setQuery(text: String) {
        _rawQuery.value = text
    }

    fun clear() = setQuery("")

    fun setAllRepos(value: Boolean) {
        _allRepos.value = value
        workspace.searchAllRepos = value
    }

    /**
     * Una búsqueda suelta, fuera del pipeline de la ventana: la de *Search Everywhere*
     * (2.10.0). Con la misma sintaxis y el mismo alcance que el buscador de la ventana
     * —el repositorio activo, o todos si está puesto *Search All Repositories*—, para que
     * `⇧⇧` y la ventana no respondan cosas distintas a la misma consulta.
     *
     * Bloqueante: llamar fuera del EDT, como hace la plataforma con sus contribuidores.
     */
    fun find(raw: String): List<com.tasklane.domain.model.Task> {
        val query = QueryParser.parse(raw)
        if (query.isEmpty) return emptyList()
        val snapshot = tasks.snapshot.value
        index.setCorpus(corpusOf(snapshot))
        val searchScope = if (_allRepos.value) SearchScope.All else SearchScope.Repo(snapshot.activeRepo)
        return index.search(query, searchScope).map { it.task }
    }

    /**
     * Como [find], pero con el alcance explícito y no el de la ventana: lo usan las
     * herramientas MCP (2.12.0), que dicen ellas mismas en qué repositorio buscan.
     */
    internal fun find(query: TaskQuery, scope: SearchScope): List<com.tasklane.domain.model.Task> {
        if (query.isEmpty) return emptyList()
        val snapshot = tasks.snapshot.value
        index.setCorpus(corpusOf(snapshot))
        return index.search(query, scope).map { it.task }
    }

    /**
     * Lo que el índice necesita saber además de la consulta: los nombres que traducir a
     * ids y, desde la 2.13.0, qué rutas ancladas están rotas —ver `has:broken-anchor`—.
     */
    private fun corpusOf(snapshot: TasklaneSnapshot) =
        SearchCorpus(snapshot.config, snapshot.repositories, brokenAnchors = snapshot.brokenAnchors)

    private fun compute(input: Input): SearchResults {
        val query = QueryParser.parse(input.raw)
        if (query.isEmpty) return SearchResults.NONE

        val searchScope =
            if (input.allRepos) SearchScope.All else SearchScope.Repo(input.snapshot.activeRepo)

        val found = index.search(query, searchScope)
        return SearchResults(
            raw = input.raw,
            query = query,
            matches = found.associate { it.task.id to it.score },
            // Buscando, el universo de la ventana ES el resultado: quien lo tiene ya
            // leído es el índice, así que se pasa en vez de volver a pedirlo.
            tasks = found.map { it.task },
            // Se resalta sólo el texto libre: subrayar las letras de `state:` dentro
            // del título sería ruido, porque ese operador no es lo que se buscaba.
            highlighter = query.text
                .takeIf(String::isNotEmpty)
                ?.let { NameUtil.buildMatcher("*$it").build() },
        )
    }

    companion object {
        private val DEBOUNCE = 120.milliseconds

        fun getInstance(project: Project): SearchService = project.service()
    }
}
