package com.tasklane.data.sqlite

import org.jetbrains.sqlite.EmptyBinder
import org.jetbrains.sqlite.ObjectBinder
import org.jetbrains.sqlite.SqliteConnection
import org.jetbrains.sqlite.SqlitePreparedStatement
import org.jetbrains.sqlite.SqliteResultSet
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock

/**
 * Lo mínimo que hay que poner encima de `org.jetbrains.sqlite` para poder escribir
 * consultas sin repetir el mismo `try/finally` treinta veces.
 *
 * **No es un mini-JDBC y no debe crecer hasta serlo.** Son cuatro operaciones —ejecutar,
 * leer filas, contar y transacción— y existen para que [TaskStore] hable de tareas y no
 * de punteros de sentencia.
 *
 * **Las dos trampas del módulo de la plataforma**, ya anotadas en `SqliteSpikeTest` y
 * que aquí se pisan en cada línea:
 *
 * 1. **Nada de `ObjectBinderFactory.createN()`**: son `inline` con parámetros
 *    reificados y vienen compiladas a JVM 25, que no cabe en nuestro bytecode 21. Se
 *    usa el constructor de [ObjectBinder], que no es `inline`.
 * 2. **Nada de `AutoCloseable.use`**, por lo mismo. `SqliteStatement` sólo es
 *    `AutoCloseable`, así que se cierra con `try/finally` a mano.
 *
 * **Ningún parámetro es nulo, nunca.** El esquema no tiene una sola columna anulable
 * —ver [TaskSchema]— y eso no es una manía: enlazar `null` es el único punto de esta
 * API cuyo comportamiento no fija ningún test de la plataforma, y una fecha ausente
 * escrita como [TaskSchema.NO_DATE] además hace que la comparación de tuplas del
 * *keyset* funcione sin `COALESCE`.
 *
 * **Las sentencias se preparan y se cierran en cada llamada**, y no se cachean. Es lo
 * contrario de lo que pedía el §3.1 del plan, y la razón es [first]: para en cuanto tiene
 * una fila, así que deja su resultado a medio consumir. Reutilizar una sentencia en ese
 * estado es una fuente de fallos raros a cambio de microsegundos que las medidas dicen
 * que no hacen falta — un comando completo son 0,42 ms sobre 100.000 tareas—. Donde sí
 * importa es en la migración, y para eso está [batch]. Si algún día molesta, la salida es
 * el `SqlStatementPool` que la propia plataforma trae.
 *
 * **Bloqueante.** Todo lo de aquí va fuera del EDT salvo las páginas de la lista, que
 * son un salto de índice y cincuenta filas — ver `SqlitePager`.
 */
internal class Sql(file: Path, readOnly: Boolean = false) : AutoCloseable {

    val connection: SqliteConnection = SqliteConnection(file, readOnly)

    /**
     * Cierra la transacción entera, no la sentencia.
     *
     * [SqliteConnection] ya serializa cada sentencia con un cerrojo propio, pero
     * `BEGIN`/`COMMIT` son estado de la conexión: sin esto, dos escrituras a la vez se
     * meterían una dentro de la transacción de la otra y un `rollback` se llevaría por
     * delante trabajo ajeno.
     */
    private val lock = ReentrantLock()

    fun execute(sql: String) {
        connection.execute(sql)
    }

    /** Lee todas las filas de una consulta. El lector recibe la fila ya posicionada. */
    fun <T> rows(sql: String, vararg params: Any, read: (SqliteResultSet) -> T): List<T> {
        val statement = prepare(sql, params)
        try {
            val out = ArrayList<T>()
            val rows = statement.executeQuery()
            while (rows.next()) out += read(rows)
            return out
        } finally {
            statement.close()
        }
    }

    /** La primera fila, o `null` si no hubo ninguna. */
    fun <T : Any> first(sql: String, vararg params: Any, read: (SqliteResultSet) -> T): T? {
        val statement = prepare(sql, params)
        try {
            val rows = statement.executeQuery()
            return if (rows.next()) read(rows) else null
        } finally {
            statement.close()
        }
    }

    /** `SELECT count(*)` y parientes: una fila, una columna, un entero. */
    fun count(sql: String, vararg params: Any): Int =
        first(sql, *params) { it.getInt(0) } ?: 0

    fun update(sql: String, vararg params: Any) {
        val statement = prepare(sql, params)
        try {
            statement.executeUpdate()
        } finally {
            statement.close()
        }
    }

    /**
     * Una transacción. Si [block] lanza, se deshace entera.
     *
     * Reentrante: una transacción dentro de otra se pliega en la de fuera, que es lo
     * que permite que [TaskStore] componga operaciones sin que cada una tenga que
     * preguntar si ya hay uno abierto.
     */
    fun <T> transaction(block: () -> T): T {
        lock.lock()
        try {
            if (lock.holdCount > 1) return block()
            connection.beginTransaction()
            val result = try {
                block()
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                throw e
            }
            connection.commit()
            return result
        } finally {
            lock.unlock()
        }
    }

    /**
     * Inserta o actualiza muchas filas con **una** sentencia preparada.
     *
     * Es el único sitio donde reutilizar la sentencia importa de verdad: la migración
     * del `tasks.xml` escribe un millón de filas y preparar una sentencia por fila
     * multiplicaría por cien lo que cuesta la más cara de las operaciones de esta fase.
     */
    fun batch(sql: String, paramCount: Int, rows: Sequence<Array<Any>>) {
        val binder = ObjectBinder(paramCount = paramCount)
        val statement = connection.prepareStatement(sql, binder)
        try {
            var pending = false
            for (row in rows) {
                binder.bindMultiple(*row)
                binder.addBatch()
                pending = true
            }
            if (pending) statement.executeBatch()
        } finally {
            statement.close()
        }
    }

    private fun prepare(sql: String, params: Array<out Any>): SqlitePreparedStatement<*> {
        if (params.isEmpty()) return connection.prepareStatement(sql, EmptyBinder)
        val binder = ObjectBinder(paramCount = params.size)
        val statement = connection.prepareStatement(sql, binder)
        binder.bindMultiple(*params)
        return statement
    }

    override fun close() {
        connection.close()
    }

    companion object {
        /**
         * `?, ?, ?` para un `IN`.
         *
         * SQLite no sabe enlazar una lista, así que la aridad va en el texto de la
         * consulta. Quien llame tiene que trocear por [MAX_VARIABLES]: pasarse del
         * límite de variables de SQLite es un error en tiempo de ejecución, no una
         * consulta lenta.
         */
        fun placeholders(n: Int): String = (0 until n).joinToString(",") { "?" }

        /**
         * Tope de variables por sentencia. SQLite admite 32.766 desde la 3.32, pero el
         * troceo se hace bastante por debajo: lo que se pasa por `IN` aquí son ids de
         * una página —cincuenta— o de una búsqueda —doscientas—, y un tope pequeño
         * mantiene las sentencias en un puñado de formas distintas, que es lo que el
         * caché de planes de SQLite sabe aprovechar.
         */
        const val MAX_VARIABLES = 500
    }
}
