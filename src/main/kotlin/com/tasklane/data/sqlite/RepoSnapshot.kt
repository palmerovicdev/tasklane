package com.tasklane.data.sqlite

import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.paging.PageQuery
import com.tasklane.paging.TaskPager
import java.time.Instant

/**
 * Un repositorio entero **congelado**, para sacarlo de la base: exportarlo a XML o
 * exportarlo antes de quitarlo.
 *
 * Son las dos operaciones de la Fase 5 que leen **todo** un repositorio, y las dos
 * necesitan lo mismo: que lo que salga sea exactamente lo que había en un instante. En
 * «Exportar y quitar» no es un detalle: lo que se exporta es lo que después se borra, y
 * una tarea escrita a mitad de camino sería una tarea que se borra sin haber salido.
 *
 * Por eso vive sobre una conexión de lectura **propia y con una transacción abierta**
 * —ver [open]—, que en WAL es una foto de la base que los escritores no mueven.
 */
internal class RepoSnapshot private constructor(
    private val sql: Sql,
    val repo: RepoKey,
    private val config: TasklaneConfig,
) {

    /** Cuántas tareas tiene, de la tabla `counter`. Es contra lo que se comprueba lo exportado. */
    val total: Int = sql.count("SELECT coalesce(sum(n), 0) FROM counter WHERE repo = ?", repo.value)

    /**
     * Los estados con alguna tarea, **en el orden de la configuración** y, detrás, los que
     * la configuración ya no conoce.
     *
     * Salen de `counter` y no de la configuración, y ésa es la mitad de la corrección:
     * iterar `config.states` se dejaría fuera las tareas de un estado que ya no existe.
     * La reconciliación de la Fase 3 las aparca en cuanto cambia la configuración, así que
     * no debería haber ninguna; pero «no debería» no es lo que se quiere oír justo antes
     * de un `DELETE`.
     */
    fun states(): List<StateId> {
        val used = sql.rows("SELECT state FROM counter WHERE repo = ? AND n > 0", repo.value) {
            StateId(it.getString(0).orEmpty())
        }.toSet()
        val known = config.states.map { it.id }.filter { it in used }
        return known + (used - known.toSet())
    }

    /**
     * Las tareas de un estado, a tandas, **en el orden de la pestaña** —marcadas, prioridad,
     * fecha—. Es el mismo camino que la lista, [SqlitePager], sin agrupar y sin filtro.
     */
    fun eachInState(state: StateId, chunk: Int = TaskPager.EXPORT_CHUNK, block: (List<Task>) -> Unit) =
        SqlitePager(sql, repo, config, TaskFilter.ALL, Instant.now()).each(PageQuery(state), chunk, block)

    /** Todas, a tandas, en el orden manual del `tasks.xml`. Ver [TaskStore.eachInOrder]. */
    fun eachInOrder(chunk: Int = TaskPager.EXPORT_CHUNK, block: (List<Task>) -> Unit) =
        TaskStore.eachInOrder(sql, repo, chunk, block)

    companion object {
        /** Abre la foto, se la da a [block] y la cierra. */
        fun <T> open(db: TaskDb, repo: RepoKey, config: TasklaneConfig, block: (RepoSnapshot) -> T): T {
            val sql = db.openReader()
            try {
                return sql.transaction { block(RepoSnapshot(sql, repo, config)) }
            } finally {
                sql.close()
            }
        }
    }
}
