package com.tasklane.service

/**
 * LRU acotada por **peso**, no por número de entradas.
 *
 * Existe por un error de bulto que no hacía falta llegar a un millón de tareas para
 * pagar: [AttachmentService] cacheaba dieciséis imágenes descodificadas contando
 * *entradas*, y una captura de 1600×1600 en `INT_ARGB` ocupa 10,2 MB. Dieciséis son
 * **164 MB de heap** con veinte tareas en la lista. Contar entradas es contar cosas
 * cuyo tamaño varía por un factor de mil. Ver `docs/plan-escala.md` §1.6.
 *
 * **Una entrada más grande que el tope entero se guarda igual** y echa a todo lo
 * demás. La alternativa —rechazarla— haría que la imagen que más cuesta descodificar
 * fuese justo la única que se descodifica en cada repintado, que es exactamente lo
 * contrario de para lo que existe una caché.
 *
 * Sincronizada: quien la usa pinta en el EDT y carga en hilos de fondo.
 */
internal class ByteBoundedCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val weigh: (V) -> Long,
) {
    /** `accessOrder = true`: el primero del iterador es el menos usado recientemente. */
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map.put(key, value)?.let { bytes -= weigh(it) }
        bytes += weigh(value)
        val iterator = map.entries.iterator()
        // Se para en una entrada: vaciar del todo tiraría la que se acaba de pedir,
        // que es la que se va a usar ahora mismo.
        while (bytes > maxBytes && map.size > 1 && iterator.hasNext()) {
            bytes -= weigh(iterator.next().value)
            iterator.remove()
        }
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)?.let { bytes -= weigh(it) }
    }

    /** Lo que ocupa ahora mismo lo cacheado. */
    /** Quita todo lo que cumpla [predicate]. Para olvidar un repositorio entero de una vez. */
    @Synchronized
    fun removeIf(predicate: (K) -> Boolean) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!predicate(entry.key)) continue
            bytes -= weigh(entry.value)
            iterator.remove()
        }
    }

    @Synchronized
    fun weight(): Long = bytes

    @Synchronized
    fun size(): Int = map.size
}
