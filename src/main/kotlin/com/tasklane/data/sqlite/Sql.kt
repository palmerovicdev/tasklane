package com.tasklane.data.sqlite

import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock

/**
 * Lo mínimo que hay que poner encima de JDBC para poder escribir consultas sin repetir el
 * mismo `try/finally` treinta veces.
 *
 * **No es un mini-JDBC y no debe crecer hasta serlo.** Son cuatro operaciones —ejecutar,
 * leer filas, contar y transacción— y existen para que [TaskStore] hable de tareas y no
 * de sentencias.
 *
 * ## Desde la Fase 6, `sqlite-jdbc` y no el SQLite de la plataforma
 *
 * La Fase 0 eligió `org.jetbrains.sqlite` —el SQLite que el IDE ya trae— por no empaquetar
 * nada. El Marketplace lo rechazó: el paquete entero lleva `@ApiStatus.Internal` en su
 * `package-info`, así que no es API para plugins aunque su módulo se declare público. Se
 * empaqueta `org.xerial:sqlite-jdbc`, que es lo que el plan del §2.3 proponía desde el
 * principio y lo que el módulo de la plataforma deriva —se nota en los nombres—.
 *
 * **Lo que no cambió**: el fichero, el esquema, las pragmas, FTS5 con
 * `unicode61 remove_diacritics 2` y `bm25()`. Una base escrita por la 2.3 se abre sin
 * migrar nada. La costura de esta clase es lo que hizo que el cambio fueran dos ficheros:
 * nadie fuera de aquí toca JDBC.
 *
 * **Las columnas se numeran desde cero**, como en el módulo de la plataforma, y no desde
 * uno como en JDBC: [Row] traduce. Así ninguna de las decenas de lectores de fila tuvo que
 * cambiar, y un `+1` olvidado no desplaza en silencio una columna de nadie.
 *
 * **Ningún parámetro es nulo, nunca.** El esquema no tiene una sola columna anulable —ver
 * [TaskSchema]— y una fecha ausente escrita como [TaskSchema.NO_DATE] hace además que la
 * comparación de tuplas del *keyset* funcione sin `COALESCE`.
 *
 * **Las sentencias se preparan y se cierran en cada llamada**, y no se cachean. La razón es
 * [first]: para en cuanto tiene una fila, así que deja su resultado a medio consumir.
 * Reutilizar una sentencia en ese estado es una fuente de fallos raros a cambio de
 * microsegundos que las medidas dicen que no hacen falta. Donde sí importa es en la
 * migración, y para eso está [batch].
 *
 * **Bloqueante.** Todo lo de aquí va fuera del EDT salvo las páginas de la lista, que son
 * un salto de índice y cincuenta filas — ver `SqlitePager`.
 */
internal class Sql(file: Path, readOnly: Boolean = false) : AutoCloseable {

    val connection: Connection = SQLiteConfig()
        .apply { setReadOnly(readOnly) }
        // `createConnection` y no `DriverManager`: el gestor de JDBC busca el driver con el
        // classloader de quien llama, y en un plugin ése no es el que tiene el driver.
        .createConnection("jdbc:sqlite:${file.toAbsolutePath()}")

    /**
     * Una fila del resultado, **con las columnas desde cero**. Ver el KDoc de la clase. Es
     * una vista sobre el cursor: sólo vale dentro del lector que la recibe.
     */
    class Row internal constructor(private val rows: ResultSet) {
        fun getString(column: Int): String? = rows.getString(column + 1)

        /** Un `NULL` es cero, como en el módulo de la plataforma. */
        fun getLong(column: Int): Long = rows.getLong(column + 1)

        fun getInt(column: Int): Int = rows.getInt(column + 1)
    }

    /**
     * Cierra la transacción entera, no la sentencia.
     *
     * `BEGIN`/`COMMIT` son estado de la conexión: sin esto, dos escrituras a la vez se
     * meterían una dentro de la transacción de la otra y un `rollback` se llevaría por
     * delante trabajo ajeno. Y serializa las sentencias sueltas con las de dentro de una
     * transacción, que es lo que el módulo de la plataforma hacía con su propio cerrojo.
     */
    private val lock = ReentrantLock()

    fun execute(sql: String) = locked {
        val statement = connection.createStatement()
        try {
            statement.execute(sql)
        } finally {
            statement.close()
        }
        Unit
    }

    /** Lee todas las filas de una consulta. El lector recibe la fila ya posicionada. */
    fun <T> rows(sql: String, vararg params: Any, read: (Row) -> T): List<T> = locked {
        val statement = prepare(sql, params)
        try {
            val out = ArrayList<T>()
            val cursor = statement.executeQuery()
            val row = Row(cursor)
            while (cursor.next()) out += read(row)
            out
        } finally {
            statement.close()
        }
    }

    /** La primera fila, o `null` si no hubo ninguna. */
    fun <T : Any> first(sql: String, vararg params: Any, read: (Row) -> T): T? = locked {
        val statement = prepare(sql, params)
        try {
            val cursor = statement.executeQuery()
            if (cursor.next()) read(Row(cursor)) else null
        } finally {
            statement.close()
        }
    }

    /** `SELECT count(*)` y parientes: una fila, una columna, un entero. */
    fun count(sql: String, vararg params: Any): Int =
        first(sql, *params) { it.getInt(0) } ?: 0

    fun update(sql: String, vararg params: Any) = locked {
        val statement = prepare(sql, params)
        try {
            statement.executeUpdate()
        } finally {
            statement.close()
        }
        Unit
    }

    /**
     * Una transacción. Si [block] lanza, se deshace entera.
     *
     * Reentrante: una transacción dentro de otra se pliega en la de fuera, que es lo que
     * permite que [TaskStore] componga operaciones sin que cada una tenga que preguntar si
     * ya hay una abierta.
     *
     * Con `BEGIN` y `COMMIT` escritos y no con `setAutoCommit(false)`: el driver, en ese
     * modo, abre otra transacción nada más confirmar, y una conexión que siempre tiene una
     * abierta es una conexión que sujeta el diario.
     */
    fun <T> transaction(block: () -> T): T {
        lock.lock()
        try {
            if (lock.holdCount > 1) return block()
            execute("BEGIN")
            val result = try {
                block()
            } catch (e: Throwable) {
                runCatching { execute("ROLLBACK") }
                throw e
            }
            execute("COMMIT")
            return result
        } finally {
            lock.unlock()
        }
    }

    /**
     * Inserta o actualiza muchas filas con **una** sentencia preparada.
     *
     * Es el único sitio donde reutilizar la sentencia importa de verdad: la migración del
     * `tasks.xml` escribe un millón de filas y preparar una sentencia por fila multiplicaría
     * por cien lo que cuesta la más cara de las operaciones.
     */
    fun batch(sql: String, paramCount: Int, rows: Sequence<Array<Any>>) = locked {
        val statement = connection.prepareStatement(sql)
        try {
            var pending = false
            for (row in rows) {
                for (i in 0 until paramCount) statement.setObject(i + 1, row[i])
                statement.addBatch()
                pending = true
            }
            if (pending) statement.executeBatch()
        } finally {
            statement.close()
        }
        Unit
    }

    /**
     * Corta la sentencia que esté en marcha en esta conexión, desde otro hilo. Es
     * `sqlite3_interrupt`, que el módulo de la plataforma no exponía: con él una copia
     * `VACUUM INTO` o una comprobación de integridad se pueden cancelar a mitad.
     */
    fun interrupt() {
        runCatching { (connection as org.sqlite.SQLiteConnection).database.interrupt() }
    }

    private fun prepare(sql: String, params: Array<out Any>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        params.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
        return statement
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        connection.close()
    }

    companion object {
        /**
         * `?, ?, ?` para un `IN`.
         *
         * SQLite no sabe enlazar una lista, así que la aridad va en el texto de la consulta.
         * Quien llame tiene que trocear por [MAX_VARIABLES]: pasarse del límite de variables
         * de SQLite es un error en tiempo de ejecución, no una consulta lenta.
         */
        fun placeholders(n: Int): String = (0 until n).joinToString(",") { "?" }

        /**
         * Tope de variables por sentencia. SQLite admite 32.766 desde la 3.32, pero el
         * troceo se hace bastante por debajo: lo que se pasa por `IN` aquí son ids de una
         * página —cincuenta— o de una búsqueda —doscientas—, y un tope pequeño mantiene las
         * sentencias en un puñado de formas distintas, que es lo que el caché de planes de
         * SQLite sabe aprovechar.
         */
        const val MAX_VARIABLES = 500
    }
}
