package com.tasklane.data.sqlite

import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * El fichero `tasklane.db` abierto: el esquema puesto, la versión comprobada y las dos
 * conexiones que el resto de la fase usa.
 *
 * **Dos conexiones, y por eso WAL.** La de escritura la usa [TaskStore] y puede tener
 * una transacción abierta durante segundos —la migración de un `tasks.xml` de 2,9 GB—;
 * la de lectura la usan el paginador, la búsqueda y las marcas del editor. En WAL un
 * lector no espera a un escritor, así que la ventana sigue respondiendo mientras se
 * importa. Con una sola conexión, importar congelaría la lista.
 *
 * **Solo lectura por versión futura.** Es la promesa de `TasksCodec` trasladada a
 * `PRAGMA user_version`: si el fichero lo escribió una versión posterior del plugin,
 * [readOnly] queda a `true`, nadie escribe y `TaskService` avisa — exactamente como
 * hacía con `tasks.xml`. Degradar el esquema escribiéndolo con el códec viejo sería
 * perder datos sin decirlo.
 *
 * **Un único fichero por proyecto**, con columna `repo`. Es el §2.3: buscar «en todos
 * los repositorios» pasa a ser una consulta en vez de treinta, y «Exportar y quitar»
 * pasa a ser un `DELETE … WHERE repo = ?`.
 */
internal class TaskDb private constructor(
    val file: Path,
    val writer: Sql,
    val reader: Sql,
    val version: Int,
    val readOnly: Boolean,
) : AutoCloseable {

    override fun close() {
        runCatching { reader.close() }
        runCatching { writer.close() }
    }

    companion object {

        const val FILE_NAME = com.tasklane.data.store.StorageLayout.DB_FILE

        /**
         * Abre —o crea— la base del proyecto.
         *
         * Devuelve `null` si el fichero no se puede abrir. No es un caso hipotético:
         * un `.idea` en un volumen de red, un disco lleno o un `tasklane.db` corrupto
         * de verdad. El servicio lo trata como «este proyecto no tiene almacén» y sigue
         * funcionando en memoria, que es lo mismo que ya hacía con un proyecto sin
         * `basePath`.
         */
        fun open(dir: Path): TaskDb? = try {
            Files.createDirectories(dir)
            create(dir.resolve(FILE_NAME))
        } catch (e: Exception) {
            thisLogger().warn("Tasklane: no se pudo abrir la base de tareas en $dir", e)
            null
        }

        private fun create(file: Path): TaskDb {
            val writer = Sql(file)
            val version = writer.count("PRAGMA user_version")
            val future = version > TaskSchema.VERSION

            if (!future) {
                for (pragma in TaskSchema.PRAGMAS) writer.execute(pragma)
                writer.transaction {
                    for (ddl in TaskSchema.DDL) writer.execute(ddl.trimIndent())
                    // Después del DDL y no antes: si la creación falla a medias, la
                    // base se queda en la versión 0 y el siguiente arranque la vuelve a
                    // intentar en vez de darla por buena a medio hacer.
                    if (version != TaskSchema.VERSION) {
                        writer.execute("PRAGMA user_version = ${TaskSchema.VERSION}")
                    }
                }
            }

            val reader = Sql(file, readOnly = true)
            for (pragma in TaskSchema.READ_PRAGMAS) reader.execute(pragma)

            return TaskDb(file, writer, reader, version = maxOf(version, TaskSchema.VERSION), readOnly = future)
        }
    }
}
