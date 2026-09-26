package com.tasklane.search

import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.query.DateField
import com.tasklane.domain.query.TaskQuery
import com.tasklane.domain.text.TextNormalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Escaneo lineal sobre documentos normalizados y cacheados. La implementación que
 * respalda la decisión de §8: sin índice invertido, porque no hace falta.
 *
 * Dos cosas hacen que O(n) sea suficiente: el texto de cada tarea se normaliza una
 * vez y se guarda ([SearchDocument]), y la búsqueda ocurre fuera del EDT detrás del
 * debounce. Lo que queda por consulta son `contains` sobre cadenas ya preparadas.
 *
 * Sólo usa `NameUtil` de la plataforma —y sólo para puntuar—, así que se testea en
 * un JUnit normal sin arrancar un IDE.
 */
class LinearScanIndex : TaskSearchIndex {

    private class Entry(
        val body: String,
        val tags: List<String>,
        val anchors: List<CodeAnchor>,
        val document: SearchDocument,
    )

    /**
     * `Concurrent` y `@Volatile` no porque haya paralelismo real —`flatMapLatest`
     * serializa las búsquedas— sino porque el productor del corpus (el colector del
     * snapshot) y el consumidor (la búsqueda) son corrutinas distintas, y aquí una
     * lectura a medias se traduciría en resultados que no casan con lo que se pinta.
     */
    private val documents = ConcurrentHashMap<TaskId, Entry>()

    @Volatile
    private var corpus: SearchCorpus = SearchCorpus(TasklaneConfig.DEFAULT)

    override fun setCorpus(corpus: SearchCorpus) {
        this.corpus = corpus
        // Una tarea borrada se lleva su documento. Sin esto el mapa crecería con la
        // sesión: el usuario que borra 500 tareas seguiría pagando su memoria.
        //
        // La poda sólo puede hacer falta cuando hay MÁS documentos cacheados que
        // tareas, así que primero se cuenta —que es sumar el tamaño de un puñado de
        // listas— y sólo entonces se construye el conjunto de ids vivos. Antes se
        // construía siempre: un `HashSet` con todos los ids del proyecto, en **cada**
        // snapshot, o sea en cada pulsación de tecla y detrás de cada comando. A
        // 100.000 tareas eran 8 ms por vuelta para, casi siempre, no borrar nada.
        val live = corpus.tasks.size
        if (documents.size <= live) return
        documents.keys.retainAll(corpus.tasks.mapTo(HashSet()) { it.id })
    }

    override fun search(query: TaskQuery, scope: SearchScope): List<ScoredTask> {
        val snapshot = corpus
        val tasks = when (scope) {
            is SearchScope.All -> snapshot.tasks
            is SearchScope.Repo -> snapshot.tasks.filter { it.repo == scope.key }
        }
        // Sin consulta no hay nada que filtrar ni que ordenar: se devuelve el corpus
        // tal cual y quien llama decide. Evita que el caso «campo vacío» tenga que
        // tratarse aparte en la UI.
        if (query.isEmpty) return tasks.map { ScoredTask(it, 0) }

        val config = snapshot.config
        // Los nombres se normalizan una vez por consulta, no una vez por tarea: son
        // un puñado y la lista de tareas puede ser de miles.
        val stateNames = config.states.associate { it.id to TextNormalizer.normalize(it.name) }
        val priorityNames = config.priorities.associate { it.id to TextNormalizer.normalize(it.name) }
        val repoNames = snapshot.repositories.associate { it.key to TextNormalizer.normalize(it.displayName) }

        // El mismo motor que Search Everywhere, sobre el texto ya normalizado. El `*`
        // es lo que permite que coincida en medio de la frase y no sólo al principio.
        val matcher = query.text
            .takeIf(String::isNotEmpty)
            ?.let { NameUtil.buildMatcher("*$it").build() }

        val matches = ArrayList<ScoredTask>()
        for (task in tasks) {
            val document = documentOf(task)
            if (!matches(task, document, query, config, stateNames, priorityNames, repoNames, snapshot.brokenAnchors)) continue
            matches += ScoredTask(task, score(document, query, matcher))
        }
        matches.sortByDescending { it.score }
        return matches
    }

    /**
     * Reutiliza el documento si el texto de la tarea no ha cambiado. La comparación
     * es por contenido y no por `updatedAt`: cambiar de estado toca la fecha pero no
     * el texto, y revalidar ahí sería normalizar el cuerpo entero para nada.
     */
    private fun documentOf(task: Task): SearchDocument {
        val cached = documents[task.id]
        if (cached != null &&
            cached.body == task.body &&
            cached.tags == task.tags &&
            cached.anchors == task.anchors
        ) {
            return cached.document
        }
        val document = SearchDocument.of(task)
        documents[task.id] = Entry(task.body, task.tags, task.anchors, document)
        return document
    }

    private fun matches(
        task: Task,
        document: SearchDocument,
        query: TaskQuery,
        config: TasklaneConfig,
        stateNames: Map<StateId, String>,
        priorityNames: Map<PriorityId, String>,
        repoNames: Map<RepoKey, String>,
        broken: Set<String>,
    ): Boolean {
        for (term in query.terms) {
            if (!document.haystack.contains(term)) return false
        }
        // AND entre etiquetas, a diferencia del resto de operadores: `#api #urgente`
        // busca lo que lleva las dos, que es para lo que se etiqueta.
        for (tag in query.tags) {
            if (document.tags.none { it.startsWith(tag) }) return false
        }

        val state = config.stateOrDefault(task.stateId)
        if (query.states.isNotEmpty()) {
            val name = stateNames[state.id].orEmpty()
            if (query.states.none { name.startsWith(it) }) return false
        }
        if (query.priorities.isNotEmpty()) {
            val name = priorityNames[config.priorityOrDefault(task.priorityId).id].orEmpty()
            if (query.priorities.none { name.startsWith(it) }) return false
        }
        if (query.repos.isNotEmpty()) {
            val name = repoNames[task.repo].orEmpty()
            if (query.repos.none { name.startsWith(it) }) return false
        }
        // Contiene y no empieza por: se teclea el nombre del fichero y la ruta guardada
        // lleva sus directorios delante.
        for (file in query.files) {
            if (document.files.none { it.contains(file) }) return false
        }
        if (query.done != null && state.terminal != query.done) return false

        for (facet in query.has) {
            val present = when (facet) {
                TaskQuery.Facet.LINK -> task.links.isNotEmpty()
                TaskQuery.Facet.IMAGE -> task.attachments.isNotEmpty()
                TaskQuery.Facet.CODE -> task.anchors.isNotEmpty()
                TaskQuery.Facet.BROKEN_ANCHOR -> task.anchors.any { it.path in broken }
                TaskQuery.Facet.DUE -> task.dueDate != null
                TaskQuery.Facet.CHECKLIST -> task.checklist.second > 0
                TaskQuery.Facet.TAG -> task.tags.isNotEmpty()
                TaskQuery.Facet.BOOKMARKED -> task.bookmarked
            }
            if (!present) return false
        }
        if (query.overdue != null && !task.isOverdue(query.overdue)) return false
        for (range in query.dates) {
            val date = when (range.field) {
                DateField.DUE -> task.dueDate
                DateField.CLOSED -> task.completedAt
                DateField.CREATED -> task.createdAt
                DateField.UPDATED -> task.updatedAt
            }
            if (!range.accepts(date)) return false
        }
        // Lo negado (2.21.0) es el mismo examen al revés: cada exclusión es una consulta de
        // una sola condición, y la tarea no puede pasarlo con ninguna.
        return query.excluded.none { matches(task, document, it, config, stateNames, priorityNames, repoNames, broken) }
    }

    /**
     * Un acierto en el título pesa más que cualquier afinidad del matcher, y por eso
     * los dos componentes se apilan en vez de sumarse: el número de términos que
     * caen en el título manda, y `matchingDegree` sólo desempata dentro de ese
     * escalón. Sin esa jerarquía una coincidencia enterrada en el cuerpo podría
     * adelantar a la que el usuario ve escrita en la fila.
     */
    private fun score(document: SearchDocument, query: TaskQuery, matcher: MinusculeMatcher?): Int {
        val titleHits = query.terms.count { document.title.contains(it) }.coerceAtMost(MAX_TITLE_HITS)
        // `matchingDegree` devuelve Integer.MIN_VALUE si no casa, así que se pregunta
        // antes: sumarlo a ciegas hundiría la fila en vez de dejarla donde estaba.
        val degree = matcher
            ?.takeIf { it.matches(document.title) }
            ?.matchingDegree(document.title)
            ?.coerceIn(0, TITLE_WEIGHT - 1)
            ?: 0
        return titleHits * TITLE_WEIGHT + degree
    }

    private companion object {
        const val TITLE_WEIGHT = 1_000_000

        /** Tope para que el escalón del título no pueda desbordar un `Int`. */
        const val MAX_TITLE_HITS = 100
    }
}
