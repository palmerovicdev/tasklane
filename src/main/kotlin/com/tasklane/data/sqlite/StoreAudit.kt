package com.tasklane.data.sqlite

/**
 * Las invariantes de `tasklane.db` que **no** comprueba `PRAGMA integrity_check`, y la
 * forma de rehacerlas.
 *
 * `integrity_check` mira que los árboles de SQLite estén sanos: que cada índice diga lo
 * mismo que su tabla, que no haya páginas perdidas. No sabe nada de lo que este plugin
 * mantiene **a mano** y en la misma transacción que cada escritura: los contadores de las
 * pestañas, el índice de texto de contenido externo —que no cascadea—, la tupla de orden
 * copiada en `tag` y los indicadores `has_*` de la tarea. Una base puede pasar
 * `integrity_check` y tener las pestañas contando mal para siempre.
 *
 * Existe por la Fase 6, para dos cosas:
 *
 * 1. **Las pruebas de caída** (§6.1): después de matar el proceso a mitad de una
 *    transacción, la base tiene que estar sana en los dos sentidos. Que SQLite deshaga la
 *    transacción a medias es lo esperable; que **nuestra** contabilidad se deshaga con
 *    ella es lo que hay que comprobar.
 * 2. **La recuperación** (§6.2): una base reconstruida a partir de lo que se pudo leer se
 *    da por buena sólo si esto no encuentra nada, y lo derivado se rehace con [rebuild]
 *    en vez de copiarse de un fichero dañado.
 *
 * Todo son consultas agregadas de una pasada: O(tamaño de la base), así que **no** va en
 * ningún camino que dispare el usuario.
 */
internal object StoreAudit {

    /**
     * Lo que está mal, en frases que se pueden poner en un informe. Vacío si todo cuadra.
     *
     * @param integrity si se lanza también `PRAGMA integrity_check`, que es lo caro.
     */
    fun check(sql: Sql, integrity: Boolean = true): List<String> {
        val problems = ArrayList<String>()
        if (integrity) {
            problems += sql.rows("PRAGMA integrity_check($MESSAGES)") { it.getString(0).orEmpty() }
                .filterNot { it.equals("ok", ignoreCase = true) }
        }

        val tasks = sql.count("SELECT count(*) FROM task")

        // El índice de texto no cascadea: una fila por tarea en `docsize`, y ninguna de más.
        val indexed = sql.count("SELECT count(*) FROM task_fts_docsize")
        if (indexed != tasks) problems += "el índice de texto tiene $indexed documentos para $tasks tareas"
        val stray = sql.count("SELECT count(*) FROM task_fts_docsize d WHERE NOT EXISTS (SELECT 1 FROM task t WHERE t.seq = d.id)")
        if (stray > 0) problems += "$stray documentos del índice de texto no tienen tarea"

        // Los contadores de las pestañas, contra la tabla.
        val counted = sql.count(
            """
            SELECT count(*) FROM (
              SELECT repo, state, count(*) AS n,
                     sum(CASE WHEN completed_at = ${TaskSchema.NO_DATE} THEN 1 ELSE 0 END) AS n_open,
                     sum(bookmarked) AS n_marked
                FROM task GROUP BY repo, state
              EXCEPT
              SELECT repo, state, n, n_open, n_marked FROM counter WHERE n > 0
            )
            """.trimIndent(),
        ) + sql.count(
            """
            SELECT count(*) FROM (
              SELECT repo, state, n, n_open, n_marked FROM counter WHERE n > 0
              EXCEPT
              SELECT repo, state, count(*),
                     sum(CASE WHEN completed_at = ${TaskSchema.NO_DATE} THEN 1 ELSE 0 END),
                     sum(bookmarked)
                FROM task GROUP BY repo, state
            )
            """.trimIndent(),
        )
        if (counted > 0) problems += "$counted contadores de estado no cuadran con las tareas"

        val prioritized = sql.count(
            """
            SELECT count(*) FROM (
              SELECT repo, priority, count(*) FROM task GROUP BY repo, priority
              EXCEPT SELECT repo, priority, n FROM priority_counter WHERE n > 0
            )
            """.trimIndent(),
        ) + sql.count(
            """
            SELECT count(*) FROM (
              SELECT repo, priority, n FROM priority_counter WHERE n > 0
              EXCEPT SELECT repo, priority, count(*) FROM task GROUP BY repo, priority
            )
            """.trimIndent(),
        )
        if (prioritized > 0) problems += "$prioritized contadores de prioridad no cuadran con las tareas"

        for (side in SIDE_TABLES) {
            val orphans = sql.count("SELECT count(*) FROM $side s WHERE NOT EXISTS (SELECT 1 FROM task t WHERE t.id = s.task_id)")
            if (orphans > 0) problems += "$orphans filas de $side apuntan a tareas que no existen"
        }

        // La tupla de orden que `tag` copia de la tarea (nota 4 del esquema).
        val drifted = sql.count(
            """
            SELECT count(*) FROM tag g JOIN task t ON t.id = g.task_id
             WHERE g.repo <> t.repo OR g.state <> t.state OR g.bookmarked <> t.bookmarked
                OR g.priority_rank <> t.priority_rank OR g.sort_date <> t.sort_date
                OR g.undated <> t.undated OR g.completed_at <> t.completed_at OR g.due_date <> t.due_date
            """.trimIndent(),
        )
        if (drifted > 0) problems += "$drifted etiquetas llevan una tupla de orden distinta de la de su tarea"

        val flags = sql.count(
            """
            SELECT count(*) FROM task t
             WHERE t.has_tag <> EXISTS (SELECT 1 FROM tag g WHERE g.task_id = t.id)
                OR t.has_anchor <> EXISTS (SELECT 1 FROM anchor a WHERE a.task_id = t.id)
            """.trimIndent(),
        )
        if (flags > 0) problems += "$flags tareas dicen tener etiquetas o anclas que no tienen, o al revés"

        return problems
    }

    /**
     * Rehace **desde la tabla `task`** todo lo que se deriva de ella: el índice de texto,
     * los contadores y la tupla de orden de `tag`. Dentro de una transacción de quien llama.
     *
     * Es lo que la recuperación hace sobre la base que acaba de reconstruir: lo derivado no
     * se copia de un fichero dañado, se vuelve a calcular.
     */
    fun rebuild(sql: Sql) {
        sql.execute("INSERT INTO task_fts(task_fts) VALUES('rebuild')")

        sql.execute("DELETE FROM counter")
        sql.execute(
            """
            INSERT INTO counter (repo, state, n, n_open, n_marked)
            SELECT repo, state, count(*),
                   sum(CASE WHEN completed_at = ${TaskSchema.NO_DATE} THEN 1 ELSE 0 END),
                   sum(bookmarked)
              FROM task GROUP BY repo, state
            """.trimIndent(),
        )
        sql.execute("DELETE FROM priority_counter")
        sql.execute("INSERT INTO priority_counter (repo, priority, n) SELECT repo, priority, count(*) FROM task GROUP BY repo, priority")

        for (side in SIDE_TABLES) {
            sql.execute("DELETE FROM $side WHERE NOT EXISTS (SELECT 1 FROM task t WHERE t.id = $side.task_id)")
        }
        sql.execute(
            """
            UPDATE tag SET
              repo = (SELECT t.repo FROM task t WHERE t.id = tag.task_id),
              state = (SELECT t.state FROM task t WHERE t.id = tag.task_id),
              bookmarked = (SELECT t.bookmarked FROM task t WHERE t.id = tag.task_id),
              priority_rank = (SELECT t.priority_rank FROM task t WHERE t.id = tag.task_id),
              sort_date = (SELECT t.sort_date FROM task t WHERE t.id = tag.task_id),
              undated = (SELECT t.undated FROM task t WHERE t.id = tag.task_id),
              completed_at = (SELECT t.completed_at FROM task t WHERE t.id = tag.task_id),
              due_date = (SELECT t.due_date FROM task t WHERE t.id = tag.task_id)
            """.trimIndent(),
        )
        sql.execute("UPDATE task SET has_tag = EXISTS (SELECT 1 FROM tag g WHERE g.task_id = task.id)")
        sql.execute("UPDATE task SET has_anchor = EXISTS (SELECT 1 FROM anchor a WHERE a.task_id = task.id)")
    }

    /** Las tablas que cuelgan de `task` por `task_id`. */
    val SIDE_TABLES = listOf("tag", "anchor", "blob_ref")

    private const val MESSAGES = 20
}
