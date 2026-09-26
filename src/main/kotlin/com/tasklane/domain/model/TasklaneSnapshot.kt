package com.tasklane.domain.model

/**
 * Estado de la **vista**, inmutable, que consume la UI.
 *
 * ## Lo que ya no lleva
 *
 * Hasta la Fase 2 esto era el modelo entero: un `Map<RepoKey, List<Task>>` con todas
 * las tareas del proyecto. Era correcto mientras cupiera —y con 5.000 tareas cabía— y
 * dejó de serlo: medido en la Fase 0, una tarea viva cuesta 12,9 KB entre el objeto, sus
 * siete `lazy` y su documento de búsqueda, así que un millón son 12,9 GB. El §0-bis.4 lo
 * dijo con todas las letras: *el snapshot tiene que dejar de contener el corpus, o el
 * parser nuevo sólo mueve el problema unos miles de tareas*.
 *
 * Ahora lleva lo que **describe la ventana** y cabe siempre: la configuración, qué
 * repositorios hay, cuál está activo y cuáles siguen importándose. Las tareas se piden
 * al almacén por páginas, y quien las pide es `TaskPager`.
 *
 * ## Por qué hay un número de revisión
 *
 * La UI repinta cuando el snapshot cambia. Si el snapshot no lleva tareas, completar
 * una no cambiaría ni un campo y la lista se quedaría como estaba. [revision] es lo que
 * lo hace visible: sube en cada escritura del almacén, y un snapshot nuevo con la misma
 * configuración y una revisión distinta significa exactamente «vuelve a preguntar».
 *
 * Es además el motivo de que siga siendo un `data class`: la igualdad estructural —que
 * es lo que `StateFlow` usa para no reemitir— sigue diciendo la verdad.
 */
data class TasklaneSnapshot(
    val config: TasklaneConfig,
    val activeRepo: RepoKey,
    /**
     * Los repositorios de la ventana, ya filtrados y ordenados por `RepoCatalog`.
     * Están aquí y no en un servicio aparte porque la UI los pinta junto a las tareas:
     * dos fuentes distintas darían un selector desincronizado del árbol durante un
     * repintado.
     */
    val repositories: List<RepositoryRef> = emptyList(),
    /**
     * Repos cuyo `tasks.xml` se está importando. Evita repintar como si estuvieran
     * vacíos mientras la migración va por la mitad.
     */
    val loading: Set<RepoKey> = emptySet(),
    /** Sube con cada escritura. Ver el KDoc de la clase. */
    val revision: Long = 0,
    /**
     * Las rutas ancladas que ya no llevan a ningún fichero (2.13.0), como las guarda
     * [CodeAnchor]. Las mantiene `AnchorFiles` y son pocas por definición: lo que se
     * borró o se movió fuera del IDE.
     *
     * Van en la vista y no en el almacén porque no son un dato de la tarea sino del
     * disco, y el disco cambia con el IDE cerrado. Van aquí y no en un servicio aparte
     * porque las usan la tarjeta —que pinta el ancla rota— y el buscador —que la filtra
     * con `has:broken-anchor`—, y que cambien tiene que repintar una y repetir el otro.
     */
    val brokenAnchors: Set<String> = emptySet(),
) {
    val activeRepository: RepositoryRef? get() = repositories.firstOrNull { it.key == activeRepo }

    /** Otro snapshot que dice «el almacén cambió». */
    fun touched(): TasklaneSnapshot = copy(revision = revision + 1)

    companion object {
        val EMPTY = TasklaneSnapshot(config = TasklaneConfig.DEFAULT, activeRepo = RepoKey.ROOT)
    }
}
