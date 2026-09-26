package com.tasklane.search

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.query.TaskQuery

/** Sobre qué repositorios se busca. */
sealed interface SearchScope {

    /** Sólo el repositorio activo. Es el valor por defecto. */
    data class Repo(val key: RepoKey) : SearchScope

    /** Todos los repositorios de la ventana, tras el toggle de la barra. */
    data object All : SearchScope
}

/**
 * Una tarea que casa, con su puntuación. Mayor es mejor; el valor absoluto no
 * significa nada fuera de la lista en la que viene.
 */
data class ScoredTask(val task: Task, val score: Int)

/**
 * Lo que una búsqueda necesita saber además de la consulta.
 *
 * [config] y [repositories] están porque los operadores `state:`, `p:` y `repo:` se
 * escriben con **nombres** y el modelo guarda ids: alguien tiene que traducir, y ese
 * alguien no puede ser la UI. [tasks] sólo lo mira [com.tasklane.search.LinearScanIndex];
 * el índice de producción vive en disco desde la Fase 3 y no necesita que le pasen nada.
 */
data class SearchCorpus(
    val config: TasklaneConfig,
    val repositories: List<RepositoryRef> = emptyList(),
    /** El corpus en memoria. Vacío con el índice de SQLite, que lee de su propia tabla. */
    val tasks: List<Task> = emptyList(),
    /**
     * Las rutas ancladas que ya no llevan a ningún fichero, para `has:broken-anchor`
     * (2.13.0). Ver `TasklaneSnapshot.brokenAnchors`.
     */
    val brokenAnchors: Set<String> = emptySet(),
)

/**
 * Quien sabe responder a una [TaskQuery].
 *
 * La interfaz existe por una única razón, y conviene dejarla escrita: la decisión de
 * **no** construir un índice invertido (ver `docs/architecture.html` §8) tenía que poder
 * revertirse sin tocar la UI. Se revirtió en la Fase 3, y no hubo que tocarla: entró
 * `com.tasklane.data.sqlite.Fts5Index` y el buscador ni se enteró.
 *
 * [com.tasklane.search.LinearScanIndex] **no se borró**. Se queda como implementación de
 * referencia en los tests, porque dos implementaciones de la misma interfaz son la mejor
 * prueba de que la nueva no cambió la semántica — la misma jugada que con los dos
 * paginadores.
 */
interface TaskSearchIndex {

    /** Fija el contexto de la búsqueda. Se llama con cada cambio del modelo. */
    fun setCorpus(corpus: SearchCorpus)

    fun search(query: TaskQuery, scope: SearchScope): List<ScoredTask>
}
