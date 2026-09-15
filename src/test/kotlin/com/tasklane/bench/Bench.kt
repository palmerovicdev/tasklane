package com.tasklane.bench

import java.lang.management.ManagementFactory
import javax.swing.SwingUtilities

/**
 * El instrumento de la Fase 0 (`docs/plan-escala.md` §0.3). Nada se optimiza sin
 * medirlo, y las puertas de las seis fases siguientes se cierran con esto.
 *
 * Mide las tres cosas que el plan pide y **sólo** esas tres:
 *
 * - **Tiempo de pared**, en p50 y p99. La media no sirve: lo que congela una ventana
 *   es la cola, no el promedio. Por eso la Fase 6 pide aserciones de techo.
 * - **Tiempo de EDT**, pasando el trabajo por el hilo de Swing de verdad
 *   ([edt]). Un cálculo que tarda 400 ms no es lo mismo si ocurre en
 *   `Dispatchers.Default` que si ocurre en el EDT: lo segundo es la ventana parada.
 * - **Heap tras un GC forzado**. Sin el GC, lo que se mide es la basura acumulada y
 *   no lo que el modelo realmente retiene, que es la cifra del §2.6.
 */
object Bench {

    /** Ni una medida cuenta hasta que el JIT ha compilado el camino caliente. */
    const val DEFAULT_WARMUP = 3
    const val DEFAULT_RUNS = 10

    data class Result(
        val name: String,
        val runs: Int,
        val p50Millis: Double,
        val p99Millis: Double,
        val maxMillis: Double,
        val heapMegabytes: Double,
    ) {
        override fun toString(): String = "%-46s p50 %8.2f ms   p99 %8.2f ms   max %8.2f ms   heap %8.1f MB"
            .format(name, p50Millis, p99Millis, maxMillis, heapMegabytes)
    }

    /**
     * Ejecuta [block] [runs] veces tras [warmup] de calentamiento y devuelve sus
     * percentiles junto al heap retenido al terminar.
     */
    fun measure(
        name: String,
        runs: Int = DEFAULT_RUNS,
        warmup: Int = DEFAULT_WARMUP,
        block: () -> Unit,
    ): Result {
        repeat(warmup) { block() }

        val samples = DoubleArray(runs)
        for (i in 0 until runs) {
            val start = System.nanoTime()
            block()
            samples[i] = (System.nanoTime() - start) / 1_000_000.0
        }
        samples.sort()

        return Result(
            name = name,
            runs = runs,
            p50Millis = percentile(samples, 0.50),
            p99Millis = percentile(samples, 0.99),
            maxMillis = samples.last(),
            heapMegabytes = heapMegabytes(),
        )
    }

    /**
     * Lo mismo, pero el trabajo ocurre **en el EDT**. Es lo que mide el §1.4: el
     * `render()` de hoy crea un `TaskNode` por tarea y despliega los grupos en el hilo
     * de UI, y ahí un segundo de cálculo es un segundo de ventana congelada.
     *
     * `invokeAndWait` desde el hilo del test, así que la excepción de dentro sale por
     * aquí en vez de quedarse en el log de AWT.
     */
    fun measureOnEdt(
        name: String,
        runs: Int = DEFAULT_RUNS,
        warmup: Int = DEFAULT_WARMUP,
        block: () -> Unit,
    ): Result = measure(name, runs, warmup) { edt(block) }

    /** Corre [block] en el EDT y espera. Propaga lo que falle dentro. */
    fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result0<T>? = null
        SwingUtilities.invokeAndWait {
            result = runCatching(block).let(::Result0)
        }
        return result!!.value.getOrThrow()
    }

    private class Result0<T>(val value: kotlin.Result<T>)

    /**
     * El heap **retenido**, no el ocupado. Varias pasadas porque una sola no garantiza
     * que se recojan objetos con finalizador ni que se vacíen las referencias débiles,
     * y lo que interesa es el suelo, que es la cifra del §2.6.
     */
    fun heapMegabytes(): Double {
        val bean = ManagementFactory.getMemoryMXBean()
        repeat(4) {
            System.gc()
            Thread.sleep(50)
        }
        return bean.heapMemoryUsage.used / (1024.0 * 1024.0)
    }

    private fun percentile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = Math.ceil(q * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    /**
     * Una tabla con los resultados, para pegarla en el plan. Se imprime y se devuelve:
     * un banco que sólo escribe en stdout no se puede afirmar en un test.
     */
    fun report(title: String, results: List<Result>): String {
        val text = buildString {
            append("\n┌─ ").append(title).append('\n')
            for (result in results) append("│  ").append(result).append('\n')
            append("└─\n")
        }
        print(text)
        return text
    }
}
