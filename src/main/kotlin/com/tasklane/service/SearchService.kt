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
import com.tasklane.search.LinearScanIndex
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
        val NONE = SearchResults("", TaskQuery.EMPTY, null, null)
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

    private val index: TaskSearchIndex = LinearScanIndex()
    private val workspace = TasklaneWorkspaceService.getInstance(project)
    private val tasks = TaskService.getInstance(project)
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
                // El corpus se refresca aquí dentro, en el mismo hilo que va a
                // buscar. Hacerlo en una corrutina aparte abriría una ventana en la
                // que los resultados hablan de un snapshot y el árbol pinta otro.
                // Los dos dentro del mismo cronometro: `setCorpus` recorre el corpus
                // entero en CADA snapshot (§1.5), asi que separarlos escondería la
                // mitad del coste de una tecla justo en el informe que existe para
                // enseñarlo.
                metrics.time(TasklaneMetrics.Op.SEARCH) {
                    index.setCorpus(input.snapshot)
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

    private fun compute(input: Input): SearchResults {
        val query = QueryParser.parse(input.raw)
        if (query.isEmpty) return SearchResults.NONE

        val searchScope =
            if (input.allRepos) SearchScope.All else SearchScope.Repo(input.snapshot.activeRepo)

        return SearchResults(
            raw = input.raw,
            query = query,
            matches = index.search(query, searchScope).associate { it.task.id to it.score },
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
