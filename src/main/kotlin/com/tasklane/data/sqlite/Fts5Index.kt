package com.tasklane.data.sqlite

import com.tasklane.domain.model.Task
import com.tasklane.domain.query.TaskQuery
import com.tasklane.domain.text.TextNormalizer
import com.tasklane.search.ScoredTask
import com.tasklane.search.SearchCorpus
import com.tasklane.search.SearchScope
import com.tasklane.search.TaskSearchIndex

/**
 * La búsqueda sobre FTS5: el §3.5 del `docs/plan-escala.md`.
 *
 * Sustituye a `LinearScanIndex`, que normalizaba el texto de **todas** las tareas y
 * recorría el corpus por consulta —91 ms a 100.000 tareas, ~0,9 s a un millón, más los
 * 3 KB por tarea que costaban los documentos cacheados—. Aquí el índice invertido ya
 * está en disco y lo mantiene el propio almacén dentro de la transacción de escritura.
 *
 * ## El reparto entre `MATCH` y `WHERE`
 *
 * Lo que es texto —el libre, las etiquetas y las rutas ancladas— va al `MATCH`; lo que
 * es una propiedad de la fila —estado, prioridad, repositorio, `is:done`, `has:`— va al
 * `WHERE` de SQL. `QueryParser` **no se toca**: sigue produciendo el mismo [TaskQuery]
 * que producía, y lo que cambia es quién lo evalúa.
 *
 * ## Qué cambia de comportamiento, y por qué se acepta
 *
 * `LinearScanIndex` hacía `contains` sobre el cuerpo entero normalizado: escribir
 * `ogin` encontraba `login`. FTS5 casa **palabras y prefijos de palabra**, así que
 * `ogin` deja de encontrarlo y `log` lo sigue encontrando. Es la regresión que el §4 del
 * plan anota como riesgo nº3, y se acepta con los ojos abiertos por dos razones: buscar
 * por el medio de una palabra es un accidente y no un gesto —nadie teclea desde la
 * tercera letra—, y el `contains` era justamente la operación que obligaba a recorrer
 * el corpus. `Fts5IndexTest` fija las dos mitades: lo que sigue igual y lo que no.
 *
 * El orden lo pone `bm25()` con peso 10 en el título —[TaskSchema.BM25]—, que es la
 * misma jerarquía que conseguía apilar escalones de `TITLE_WEIGHT = 1_000_000`, sin el
 * tope contra el desbordamiento de un `Int`.
 */
internal class Fts5Index(private val sql: Sql) : TaskSearchIndex {

    @Volatile
    private var corpus: SearchCorpus = SearchCorpus(com.tasklane.domain.model.TasklaneConfig.DEFAULT)

    /**
     * Aquí sólo entran los nombres que hay que traducir a ids. El corpus no: vive en
     * disco, y ésa es la diferencia entera con `LinearScanIndex`.
     */
    override fun setCorpus(corpus: SearchCorpus) {
        this.corpus = corpus
    }

    override fun search(query: TaskQuery, scope: SearchScope): List<ScoredTask> {
        val context = corpus
        val config = context.config
        val where = StringBuilder("1 = 1")
        val params = ArrayList<Any>()

        if (scope is SearchScope.Repo) {
            where.append(" AND t.repo = ?")
            params += scope.key.value
        }

        // Los operadores de nombre: `state:`, `p:` y `repo:` se escriben con el nombre
        // visible y el modelo guarda ids, así que se resuelven aquí y entran al `WHERE`
        // como una lista cerrada. Ninguna coincidencia significa ningún resultado, no
        // «ese filtro no cuenta»: el usuario escribió algo.
        var selective = false
        if (query.states.isNotEmpty()) {
            val ids = config.states.filter { state -> matchesName(state.name, query.states) }.map { it.id.value }
            if (ids.isEmpty()) return emptyList()
            where.append(" AND t.state IN (${Sql.placeholders(ids.size)})")
            params.addAll(ids)
            selective = true
        }
        if (query.priorities.isNotEmpty()) {
            val ids = config.priorities.filter { matchesName(it.name, query.priorities) }.map { it.id.value }
            if (ids.isEmpty()) return emptyList()
            where.append(" AND t.priority IN (${Sql.placeholders(ids.size)})")
            params.addAll(ids)
            selective = true
        }
        if (query.repos.isNotEmpty()) {
            val keys = context.repositories
                .filter { matchesName(it.displayName, query.repos) }
                .map { it.key.value }
            if (keys.isEmpty()) return emptyList()
            where.append(" AND t.repo IN (${Sql.placeholders(keys.size)})")
            params.addAll(keys)
            selective = true
        }
        query.done?.let {
            where.append(" AND t.state_terminal = ?")
            params += if (it) 1 else 0
            selective = true
        }
        for (facet in query.has) {
            when (facet) {
                TaskQuery.Facet.LINK -> where.append(" AND t.has_link = 1")
                TaskQuery.Facet.IMAGE -> where.append(" AND t.has_image = 1")
                TaskQuery.Facet.CODE -> where.append(" AND t.has_anchor = 1")
                // Las rotas (2.13.0) no son una columna de la fila: las dice el disco y
                // llegan con el corpus. Como los nombres de estado, una lista cerrada, y
                // ninguna rota es ningún resultado. En un solo parámetro JSON y no en un
                // `IN (?, ?…)` porque pueden ser más que las variables que admite una
                // sentencia: basta con borrar un directorio con anclas dentro.
                TaskQuery.Facet.BROKEN_ANCHOR -> {
                    if (context.brokenAnchors.isEmpty()) return emptyList()
                    where.append(
                        " AND t.has_anchor = 1 AND t.id IN " +
                            "(SELECT task_id FROM anchor WHERE path IN (SELECT value FROM json_each(?)))",
                    )
                    params += jsonArray(context.brokenAnchors)
                }
            }
            selective = true
        }

        val match = matchOf(query)
            // Sin texto que puntuar no hay `bm25` que valga: se cae al orden de siempre,
            // lo más reciente arriba, que es lo que el usuario espera de `is:done` a secas.
            ?: return scored(
                sql.rows(
                    "SELECT ${TaskRows.columns("t")} FROM task t WHERE $where ORDER BY t.updated_at DESC LIMIT ?",
                    *(params + LIMIT).toTypedArray(),
                    read = TaskRows::read,
                ),
            )

        // **Primero se ordena, después se mira la tabla.** Unir `task_fts` con `task` y
        // ordenar al final obliga a saltar a la tabla por **cada** acierto antes de
        // poder descartarlo: medido sobre 50.000 tareas y un término presente en casi
        // todas, 44 ms contra 12 haciéndolo en dos pasos. El índice de texto sabe
        // puntuar sin salir de sí mismo; que traiga sus mejores y sólo ésos se busquen.
        val ranked = sql.rows(
            "SELECT rowid FROM task_fts WHERE task_fts MATCH ? ORDER BY ${TaskSchema.BM25} LIMIT ?",
            match,
            if (selective) CANDIDATES else LIMIT,
        ) { it.getLong(0) }
        if (ranked.isEmpty()) return emptyList()

        val position = HashMap<Long, Int>(ranked.size)
        ranked.forEachIndexed { index, seq -> position[seq] = index }

        val found = ArrayList<Pair<Int, Task>>(ranked.size)
        for (chunk in ranked.chunked(Sql.MAX_VARIABLES)) {
            val holes = Sql.placeholders(chunk.size)
            val args = (params + chunk).toTypedArray()
            sql.rows(
                "SELECT ${TaskRows.columns("t")}, t.seq FROM task t WHERE $where AND t.seq IN ($holes)",
                *args,
            ) { rows ->
                found += (position[rows.getLong(13)] ?: Int.MAX_VALUE) to TaskRows.read(rows)
            }
        }

        found.sortBy { it.first }
        return scored(found.take(LIMIT).map { it.second })
    }

    /**
     * La puntuación es la **posición**, no una magnitud.
     *
     * `bm25` devuelve reales negativos y quien consume esto sólo la usa para ordenar
     * —ver `MemoryPager`—, así que convertirla a «mayor es mejor» aquí evita que el
     * signo viaje por la UI.
     */
    private fun scored(tasks: List<Task>): List<ScoredTask> =
        TaskStore.hydrate(sql, tasks).mapIndexed { index, task -> ScoredTask(task, LIMIT - index) }

    /**
     * La expresión de `MATCH`, o `null` si la consulta no tiene nada que buscar por
     * texto. Todo va en `AND`, que es lo que hacía el escaneo: los términos porque
     * tienen que aparecer todos, y las etiquetas porque `#api #urgente` pide las dos.
     */
    private fun matchOf(query: TaskQuery): String? {
        val parts = ArrayList<String>(query.terms.size + query.tags.size + query.files.size)
        for (term in query.terms) parts += "${quote(term)}*"
        for (tag in query.tags) parts += "tags_text : ${quote(tag)}*"
        for (file in query.files) parts += "files_text : ${quote(file)}*"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }

    /**
     * Un término, a salvo de la sintaxis de FTS5.
     *
     * Entre comillas dobles todo deja de ser operador —`AND`, `*`, `-`, `:` y los
     * paréntesis—, que es exactamente lo que hace falta: lo que el usuario escribe en el
     * buscador es texto, no una expresión de búsqueda. El `*` del prefijo se pega
     * **fuera** de las comillas, que es donde FTS5 lo lee como operador.
     */
    private fun quote(term: String): String = "\"" + term.replace("\"", "\"\"") + "\""

    /**
     * Una lista de cadenas en JSON, para `json_each`. Las rutas son casi siempre letras y
     * `/`, pero un nombre de fichero puede llevar comillas, y una ruta mal escapada sería
     * una consulta que falla en vez de una que no encuentra.
     */
    private fun jsonArray(values: Collection<String>): String = values.joinToString(",", "[", "]") { value ->
        buildString(value.length + 2) {
            append('"')
            for (ch in value) {
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch < ' ' -> append("\\u%04x".format(ch.code))
                    else -> append(ch)
                }
            }
            append('"')
        }
    }

    /** Los operadores de nombre casan por prefijo, igual que en `LinearScanIndex`. */
    private fun matchesName(name: String, prefixes: Set<String>): Boolean {
        val normalized = TextNormalizer.normalize(name)
        return prefixes.any { normalized.startsWith(it) }
    }

    private companion object {
        /** Nadie mira el resultado 201 de una búsqueda. Es el §3.5 del plan. */
        const val LIMIT = 200

        /**
         * Cuántos aciertos se piden al índice cuando la consulta además **filtra por
         * fila** —`state:`, `p:`, `is:done`, `has:`—.
         *
         * Con un filtro de por medio no basta con los doscientos mejores: puede que
         * ninguno de ellos lo pase. Se piden mil y se filtran, que es lo que hace
         * cualquier buscador —generar candidatos y después descartar— y lo que mantiene
         * el coste acotado en vez de proporcional al corpus.
         *
         * Lo que esto cuesta en exactitud, dicho claro: con un filtro **muy** selectivo
         * —`has:image` en un proyecto sin apenas capturas— puede faltar algo que casaba
         * peor por texto y sí pasaba el filtro. Lo que se gana es que buscar no se
         * convierta en recorrer la tabla, que es lo que esta fase vino a quitar.
         */
        const val CANDIDATES = 1_000
    }
}
