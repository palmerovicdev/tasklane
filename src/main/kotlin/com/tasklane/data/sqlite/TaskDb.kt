package com.tasklane.data.sqlite

import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
 *
 * ## El mantenimiento de la Fase 5
 *
 * Tres cosas que sustituyen al `.bak` que `TaskFileStore` copiaba en **cada** volcado, y
 * que sólo tienen sentido sobre el fichero entero:
 *
 * - [backupTo]: una copia compacta y consistente con `VACUUM INTO`, como mucho una vez
 *   al día.
 * - [checkIntegrity]: `PRAGMA integrity_check`, **sólo tras un cierre sucio** —[dirty]—,
 *   porque sobre una base de varios gigas no es barato.
 * - `PRAGMA optimize` al cerrar. Ver [close].
 */
internal class TaskDb private constructor(
    val file: Path,
    val writer: Sql,
    val reader: Sql,
    val version: Int,
    val readOnly: Boolean,
    /**
     * Si la sesión anterior **no cerró la base**: se cayó el IDE, se mató el proceso, se
     * fue la luz.
     *
     * No hace falta un fichero marcador para saberlo, y es a propósito: SQLite ya deja
     * uno. Con `journal_mode = WAL`, cerrar la última conexión vuelca el diario a la base
     * y **borra** `tasklane.db-wal`; si al abrir sigue ahí y tiene algo dentro, la última
     * sesión no llegó a cerrar. Un diario vacío tampoco cuenta: una sesión que no escribió
     * nada no pudo dejar nada a medias.
     *
     * Lo que pasó queda sano en el caso normal —WAL es justo lo que hace que una caída no
     * corrompa—, y por eso esto no dispara una alarma sino una comprobación en segundo
     * plano. Lo que la comprobación busca es lo que WAL no protege: un disco que mintió
     * sobre un `fsync`, un volumen de red, un `.idea` restaurado a medias.
     */
    val dirty: Boolean,
) : AutoCloseable {

    /**
     * Cierra, pero antes le deja a SQLite apuntar lo que haya aprendido de las consultas.
     *
     * `PRAGMA optimize` es lo que la documentación de SQLite pide antes de cerrar una
     * conexión de larga vida: analiza **sólo** lo que las consultas de esta sesión hayan
     * demostrado que lo necesita, y con [ANALYSIS_LIMIT] ese análisis es un muestreo y no
     * un recorrido del índice entero. Hoy, con el SQLite 3.42 de la plataforma y sin
     * estadísticas previas, no hace nada; se deja porque es lo que cuesta no tenerlo el
     * día que la plataforma lo actualice, y `TaskDbTest` fija que un `ANALYZE` completo
     * no le cambia el plan a ninguna consulta de la lista.
     *
     * Sólo en la de escritura: una conexión de solo lectura no puede escribir las
     * estadísticas.
     */
    override fun close() {
        if (!readOnly) {
            runCatching {
                writer.execute("PRAGMA analysis_limit = $ANALYSIS_LIMIT")
                writer.execute("PRAGMA optimize")
            }.onFailure { thisLogger().warn("Tasklane: PRAGMA optimize falló al cerrar la base", it) }
        }
        runCatching { reader.close() }
        runCatching { writer.close() }
    }

    /**
     * Una conexión de lectura **nueva**, con las mismas pragmas que la de siempre.
     *
     * Para lo que tiene que ver la base quieta mientras dura —exportar, copiar,
     * comprobar— sin congelar lo que ve la ventana. Quien la abre la cierra.
     */
    fun openReader(): Sql = Sql(file, readOnly = true).also { sql ->
        for (pragma in TaskSchema.READ_PRAGMAS) sql.execute(pragma)
    }

    /**
     * Copia la base entera a [target] con `VACUUM INTO`, y devuelve cuánto ocupa.
     *
     * **Por qué `VACUUM INTO` y no copiar el fichero.** Copiar `tasklane.db` con la base
     * abierta copia páginas a medio escribir y deja fuera lo que siga en el diario: una
     * copia así puede no abrir. `VACUUM INTO` lee dentro de una transacción —ve la base
     * en un instante, igual que la exportación— y escribe una base nueva, compacta y sin
     * diario. Desde una conexión de solo lectura, así que ni la ventana ni el escritor
     * esperan.
     *
     * **Primero a un temporal y después se mueve.** Si el IDE se cierra a mitad, lo que
     * queda es un `.tmp` que la siguiente copia borra, y no una copia de seguridad a medias
     * encima de la buena. Y **no se puede cancelar a mitad**: la plataforma no expone
     * `sqlite3_interrupt`, así que es una sentencia que dura lo que dura.
     */
    fun backupTo(target: Path): Long {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.deleteIfExists(tmp)
        val sql = openReader()
        try {
            sql.execute("VACUUM INTO '${tmp.toString().replace("'", "''")}'")
        } finally {
            sql.close()
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return Files.size(target)
    }

    /**
     * `PRAGMA integrity_check`, en una conexión propia. Devuelve los problemas; vacío si
     * la base está sana.
     *
     * [limit] acota **los mensajes**, no el trabajo: el recorrido es entero siempre, y es
     * lo que lo hace caro. Por eso sólo se lanza tras un cierre sucio.
     *
     * **Que la comprobación reviente también es un resultado.** Con según qué páginas
     * dañadas, SQLite no llega a listar problemas: la propia sentencia falla con
     * `SQLITE_CORRUPT`. Tratar eso como un error del mantenimiento sería callar justo el
     * caso más grave, así que el fallo se devuelve como el problema que es.
     */
    fun checkIntegrity(limit: Int = INTEGRITY_MESSAGES): List<String> = try {
        val sql = openReader()
        try {
            sql.rows("PRAGMA integrity_check($limit)") { it.getString(0).orEmpty() }
                .filterNot { it.equals("ok", ignoreCase = true) }
        } finally {
            sql.close()
        }
    } catch (e: Exception) {
        listOf(e.message ?: e.javaClass.simpleName)
    }

    companion object {

        const val FILE_NAME = com.tasklane.data.store.StorageLayout.DB_FILE

        /**
         * Filas que muestrea `ANALYZE` por índice cuando lo lanza `PRAGMA optimize`. El
         * valor que la documentación de SQLite recomienda para aplicaciones: bastante
         * para que el planificador acierte, y un muestreo en vez de un recorrido.
         */
        const val ANALYSIS_LIMIT = 400

        /** Cuántos problemas se traen de una comprobación. Para avisar basta con los primeros. */
        const val INTEGRITY_MESSAGES = 20

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

        /** El diario de WAL de una base. Ver [dirty]. */
        fun walOf(file: Path): Path = file.resolveSibling("${file.fileName}-wal")

        private fun create(file: Path): TaskDb {
            // ANTES de abrir la primera conexión: abrirla es lo que recupera el diario.
            val wal = walOf(file)
            val dirty = runCatching { Files.exists(wal) && Files.size(wal) > 0 }.getOrDefault(false)

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

            return TaskDb(
                file,
                writer,
                reader,
                version = maxOf(version, TaskSchema.VERSION),
                readOnly = future,
                dirty = dirty,
            )
        }
    }
}
