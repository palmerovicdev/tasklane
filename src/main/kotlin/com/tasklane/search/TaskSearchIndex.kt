package com.tasklane.search

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneSnapshot
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
 * Quien sabe responder a una [TaskQuery].
 *
 * La interfaz existe por una única razón, y conviene dejarla escrita: la decisión de
 * **no** construir un índice invertido (ver `docs/architecture.html` §8) tiene que
 * poder revertirse sin tocar la UI. Con 5.000 tareas de ~200 caracteres un escaneo
 * sobre cadenas ya normalizadas cuesta un par de milisegundos fuera del EDT; si el
 * profiling alguna vez dijera otra cosa, se cambia la implementación y ya.
 */
interface TaskSearchIndex {

    /**
     * Fija el corpus sobre el que se busca. Se llama con cada snapshot nuevo: la
     * implementación decide qué documentos cacheados siguen valiendo.
     */
    fun setCorpus(snapshot: TasklaneSnapshot)

    fun search(query: TaskQuery, scope: SearchScope): List<ScoredTask>

    /** Tira los documentos cacheados de [ids]. */
    fun invalidate(ids: Collection<TaskId>)
}
