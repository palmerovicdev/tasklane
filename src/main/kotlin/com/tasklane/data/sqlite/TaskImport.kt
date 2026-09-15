package com.tasklane.data.sqlite

import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.TasklaneConfig
import java.nio.file.Path

/**
 * El bucle de la migración de un `tasks.xml` a la base (§3.3), **sin IDE**.
 *
 * Vivía dentro de `TaskService.importRepo`, que es un servicio de proyecto. Sale aquí en la
 * Fase 6 por la misma razón que el recolector salió a `BlobSweeper`: la prueba de caída
 * del §6.1 mata un proceso **a mitad de migrar** y comprueba que la apertura siguiente
 * continúa donde iba, y eso sólo vale si el proceso que se mata ejecuta el bucle de
 * producción y no una copia.
 *
 * **Lo que lo hace reanudable** está en una sola línea y conviene no perderla de vista:
 * cada tanda se escribe **en la misma transacción** que la marca de por dónde va
 * (`imported.tasks`). Si el proceso muere, o las dos cosas están en disco o no lo está
 * ninguna, así que saltarse las `tasks` primeras del fichero al volver es exactamente
 * continuar.
 */
internal object TaskImport {

    data class Outcome(val result: TasksXmlReader.Result, val written: Int)

    /**
     * Importa lo que falte de [file], a tandas, y deja apuntado hasta dónde llegó.
     *
     * No marca el repositorio como importado del todo: eso, y archivar el fichero, lo
     * decide quien llama según [Outcome.result].
     *
     * @param checkpointPages cada cuántas páginas vuelca SQLite el diario mientras dura.
     *   Ver `TaskStore.bulk` y el experimento del §6-bis.
     * @param onChunk recibe cuántas van escritas en total después de cada tanda confirmada.
     */
    fun run(
        store: TaskStore,
        file: Path,
        repo: RepoKey,
        config: TasklaneConfig,
        now: () -> Long = System::currentTimeMillis,
        checkpointPages: Int = CHECKPOINT_PAGES,
        cancelled: () -> Boolean = { false },
        onChunk: (Int) -> Unit = {},
    ): Outcome {
        var written = store.importedCount(repo)
        val result = store.bulk(checkpointPages) {
            TasksXmlReader.read(file, repo, skip = written, cancelled = cancelled) { chunk ->
                store.write {
                    store.importBatch(chunk, config)
                    store.markImported(repo, TasksCodec.CURRENT_VERSION, written + chunk.size, false, now())
                }
                written += chunk.size
                onChunk(written)
            }
        }
        return Outcome(result, written)
    }

    /**
     * El volcado del diario mientras se importa. Cero es el de SQLite —cada mil páginas—;
     * ver el §6-bis para lo que se midió.
     */
    const val CHECKPOINT_PAGES = 0
}
