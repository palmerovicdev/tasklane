package com.tasklane.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cronómetro de la ventana de una hora.
 *
 * Lo que hay que fijar aquí no es la precisión —los percentiles son techos de cubo a
 * propósito, ver el KDoc de [TasklaneMetrics]— sino que el histograma **coloca cada
 * medida donde dice**. Un cubo mal calculado convierte el informe en un generador de
 * pistas falsas, que es peor que no tener informe.
 */
class TasklaneMetricsTest {

    @Test
    fun `cada medida cae en su cubo`() {
        // El cubo 0 es «menos de 1 ms», y ahí caen también los microsegundos sueltos.
        assertEquals(0, TasklaneMetrics.bucketOf(0))
        assertEquals(0, TasklaneMetrics.bucketOf(999))
        // A partir de 1 ms, potencias de dos: 1→<2, 2→<4, 3→<4, 4→<8.
        assertEquals(1, TasklaneMetrics.bucketOf(1_000))
        assertEquals(2, TasklaneMetrics.bucketOf(2_000))
        assertEquals(2, TasklaneMetrics.bucketOf(3_999))
        assertEquals(3, TasklaneMetrics.bucketOf(4_000))
        // 16 ms —el presupuesto del EDT— tiene que caer justo encima del cubo de 16.
        assertEquals(5, TasklaneMetrics.bucketOf(16_000))
        assertEquals(32.0, TasklaneMetrics.bucketCeilingMillis(5), 0.0)
        assertEquals(16.0, TasklaneMetrics.bucketCeilingMillis(4), 0.0)
    }

    /** Una medida disparatada no puede desbordar el array ni perder la cuenta. */
    @Test
    fun `una medida enorme cae en el ultimo cubo`() {
        val hour = 3_600L * 1_000_000
        assertEquals(19, TasklaneMetrics.bucketOf(hour))
        assertTrue(TasklaneMetrics.bucketCeilingMillis(19) > 500_000)
    }

    @Test
    fun `sin medidas no hay filas`() {
        assertTrue(TasklaneMetrics().snapshot().isEmpty())
    }

    @Test
    fun `cuenta, media y maximo son exactos aunque los percentiles sean techos`() {
        val metrics = TasklaneMetrics()
        // Nueve rápidas y una lenta: es la forma que tiene una regresión de latencia,
        // y la razón de que el informe enseñe p99 y no la media.
        repeat(9) { metrics.record(TasklaneMetrics.Op.RENDER, 2_000) }
        metrics.record(TasklaneMetrics.Op.RENDER, 300_000)

        val sample = metrics.snapshot().single()
        assertEquals(TasklaneMetrics.Op.RENDER, sample.op)
        assertEquals(10L, sample.count)
        assertEquals("la media y el maximo son exactos", 300.0, sample.maxMillis, 0.1)
        assertEquals(31.8, sample.meanMillis, 0.5)
        // El p50 se queda en el cubo de las rapidas y el p99 se va a la lenta: eso es
        // justo lo que la media de 31,8 ms escondia.
        assertEquals(4.0, sample.p50Millis, 0.0)
        assertTrue("el p99 tiene que delatar la lenta", sample.p99Millis >= 256.0)
    }

    @Test
    fun `cada operacion lleva su propia cuenta`() {
        val metrics = TasklaneMetrics()
        repeat(3) { metrics.record(TasklaneMetrics.Op.COMMAND, 500) }
        metrics.record(TasklaneMetrics.Op.SAVE, 50_000)

        val byOp = metrics.snapshot().associateBy { it.op }
        assertEquals(2, byOp.size)
        assertEquals(3L, byOp.getValue(TasklaneMetrics.Op.COMMAND).count)
        assertEquals(1L, byOp.getValue(TasklaneMetrics.Op.SAVE).count)
    }

    @Test
    fun `time cronometra y devuelve lo de dentro`() {
        val metrics = TasklaneMetrics()
        assertEquals(42, metrics.time(TasklaneMetrics.Op.COMMAND) { 42 })
        assertEquals(1L, metrics.snapshot().single().count)
    }

    /** Lo que falla dentro sale fuera, y la medida se anota igual. */
    @Test
    fun `time anota tambien lo que falla`() {
        val metrics = TasklaneMetrics()
        runCatching { metrics.time(TasklaneMetrics.Op.SAVE) { error("disco lleno") } }
        assertEquals(1L, metrics.snapshot().single().count)
    }

    /**
     * El aviso que el informe da cuando el repintado —que corre en el EDT— se pasa de
     * los 16 ms. Es el único número del informe con un techo duro, y es exactamente lo
     * que el banco midió a 10.000 tareas antes de la Fase 2.
     */
    @Test
    fun `el informe avisa cuando el render se pasa del presupuesto del EDT`() {
        val metrics = TasklaneMetrics()
        repeat(100) { metrics.record(TasklaneMetrics.Op.RENDER, 2_125_000) }

        val text = DiagnosticsReport.render(
            TasklaneDiagnostics.collect(null, emptyMap(), { 0 }, metrics.snapshot()),
        )
        assertTrue(text, text.contains("WARNING: render runs on the EDT"))
    }

    @Test
    fun `sin pasarse no avisa`() {
        val metrics = TasklaneMetrics()
        repeat(100) { metrics.record(TasklaneMetrics.Op.RENDER, 3_000) }

        val text = DiagnosticsReport.render(
            TasklaneDiagnostics.collect(null, emptyMap(), { 0 }, metrics.snapshot()),
        )
        assertTrue(text, !text.contains("WARNING"))
    }
}
