package com.tasklane.diagnostics

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Las latencias de la última hora, para que «va lento» se pueda decir con datos.
 *
 * Es la mitad viva de la acción de diagnóstico del `docs/plan-escala.md` §0.4: el
 * banco mide en un portátil con un corpus sintético, y esto mide en el proyecto del
 * usuario con lo que el usuario tenga. Las dos cosas hacen falta —el banco detecta la
 * regresión antes de publicarla, esto explica la que se coló—.
 *
 * **Tres decisiones que la hacen barata de tener encendida siempre:**
 *
 * - **Histograma, no lista de muestras.** Guardar cada medida sería un `ArrayList` que
 *   crece con la sesión, y precisamente esto existe para hablar de memoria. Los cubos
 *   son potencias de dos en milisegundos, que es la resolución con la que se razona
 *   sobre una interfaz: si algo cae en el cubo de «32-64 ms» ya se sabe todo lo que
 *   hay que saber.
 * - **Ventana de una hora, por cubos de minuto.** Un anillo de sesenta ranuras que se
 *   reciclan; no hay nada que purgar ni ningún temporizador.
 * - **Sin bloqueos.** [record] se llama desde el EDT, desde `Dispatchers.IO` y desde
 *   corrutinas de fondo. Un `synchronized` aquí sería un candado en el camino que se
 *   está midiendo, que es la peor forma posible de falsear una medida.
 *
 * Una cuenta que se pierde por una carrera entre dos hilos no cambia una conclusión
 * sobre latencia, y a cambio medir nunca cuesta más que un `incrementAndGet`.
 */
@Service(Service.Level.PROJECT)
class TasklaneMetrics {

    /** Lo que se cronometra. Si no está en esta lista, no se mide. */
    enum class Op {
        /** Leer un `tasks.xml` de disco y meterlo en el modelo. */
        LOAD,

        /** Un `TaskCommand` completo, desde `apply` hasta el snapshot nuevo. */
        COMMAND,

        /** Volcar un repositorio a disco. */
        SAVE,

        /** Una consulta, desde el texto hasta los resultados. */
        SEARCH,

        /** Filtrar, ordenar y agrupar una pestaña. Fuera del EDT. */
        SECTIONS,

        /** Reconstruir el árbol. **En el EDT**: es el que no puede pasar de 16 ms. */
        RENDER,
    }

    private class Histogram {
        /** Un cubo por potencia de dos de milisegundos: <1, <2, <4 … <2^19 ≈ 8,7 min. */
        val buckets = Array(BUCKETS) { AtomicLong() }
        val count = AtomicLong()
        val totalMicros = AtomicLong()
        val maxMicros = AtomicLong()

        fun add(micros: Long) {
            count.incrementAndGet()
            totalMicros.addAndGet(micros)
            maxMicros.accumulateAndGet(micros, ::maxOf)
            buckets[bucketOf(micros)].incrementAndGet()
        }
    }

    /**
     * `(minuto de la época) % 60` → histogramas por operación. El anillo es lo que
     * acota la memoria sin barrer nada: la ranura de hace sesenta y un minutos es la
     * misma que la de hace un minuto, y al entrar se limpia.
     */
    private val ring = Array(WINDOW_MINUTES) { ConcurrentHashMap<Op, Histogram>() }
    private val stamps = Array(WINDOW_MINUTES) { AtomicLong(Long.MIN_VALUE) }

    /**
     * Anota que [op] tardó [micros] microsegundos. Microsegundos y no milisegundos
     * porque a esta escala hay operaciones de medio milisegundo, y redondearlas a
     * cero las haría invisibles justo en el momento en que todo va bien.
     */
    fun record(op: Op, micros: Long) {
        val minute = System.currentTimeMillis() / 60_000
        val slot = (minute % WINDOW_MINUTES).toInt()
        // Si la ranura es de otra vuelta del anillo, se recicla. Dos hilos pueden
        // entrar a la vez; el CAS decide quién limpia y el otro sigue como si nada.
        val stamp = stamps[slot]
        val seen = stamp.get()
        if (seen != minute && stamp.compareAndSet(seen, minute)) ring[slot].clear()
        ring[slot].computeIfAbsent(op) { Histogram() }.add(micros)
    }

    /** Cronometra [block] y lo anota. Devuelve lo que devuelva. */
    inline fun <T> time(op: Op, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(op, (System.nanoTime() - start) / 1_000)
        }
    }

    /**
     * Lo medido de cada operación en la última hora. `p50` y `p99` salen del
     * histograma, así que son el **techo del cubo** en el que cae el percentil: «p99
     * < 64 ms» y no «p99 = 61,4 ms». Es deliberado —ver el KDoc de la clase— y es la
     * precisión con la que se decide si algo va lento.
     */
    fun snapshot(): List<Sample> {
        val minute = System.currentTimeMillis() / 60_000
        val merged = LinkedHashMap<Op, Histogram>()
        for (slot in 0 until WINDOW_MINUTES) {
            // Sólo las ranuras que son de esta vuelta: el resto es historia de hace
            // más de una hora que todavía no ha tocado reciclar.
            if (minute - stamps[slot].get() >= WINDOW_MINUTES) continue
            for ((op, histogram) in ring[slot]) {
                val into = merged.getOrPut(op) { Histogram() }
                into.count.addAndGet(histogram.count.get())
                into.totalMicros.addAndGet(histogram.totalMicros.get())
                into.maxMicros.accumulateAndGet(histogram.maxMicros.get(), ::maxOf)
                for (i in 0 until BUCKETS) into.buckets[i].addAndGet(histogram.buckets[i].get())
            }
        }

        return Op.entries.mapNotNull { op ->
            val histogram = merged[op] ?: return@mapNotNull null
            val count = histogram.count.get()
            if (count == 0L) return@mapNotNull null
            Sample(
                op = op,
                count = count,
                meanMillis = histogram.totalMicros.get() / count / 1000.0,
                p50Millis = percentileCeilingMillis(histogram, count, 0.50),
                p99Millis = percentileCeilingMillis(histogram, count, 0.99),
                maxMillis = histogram.maxMicros.get() / 1000.0,
            )
        }
    }

    data class Sample(
        val op: Op,
        val count: Long,
        val meanMillis: Double,
        /** Techo del cubo donde cae el percentil. Ver [snapshot]. */
        val p50Millis: Double,
        val p99Millis: Double,
        val maxMillis: Double,
    )

    private fun percentileCeilingMillis(histogram: Histogram, count: Long, q: Double): Double {
        val target = Math.ceil(q * count).toLong().coerceAtLeast(1)
        var seen = 0L
        for (i in 0 until BUCKETS) {
            seen += histogram.buckets[i].get()
            if (seen >= target) return bucketCeilingMillis(i)
        }
        return bucketCeilingMillis(BUCKETS - 1)
    }

    companion object {
        private const val WINDOW_MINUTES = 60
        private const val BUCKETS = 20

        /** El cubo de [micros]: `<1 ms`, `<2 ms`, `<4 ms`… */
        fun bucketOf(micros: Long): Int {
            val millis = micros / 1000
            if (millis <= 0) return 0
            return (64 - java.lang.Long.numberOfLeadingZeros(millis)).coerceIn(0, BUCKETS - 1)
        }

        /** El techo del cubo [index], en milisegundos. */
        fun bucketCeilingMillis(index: Int): Double = if (index == 0) 1.0 else (1L shl index).toDouble()

        fun getInstance(project: Project): TasklaneMetrics = project.service()
    }
}
