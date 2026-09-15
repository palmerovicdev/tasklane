package com.tasklane.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La caché de imágenes, acotada por peso.
 *
 * Esto no es una utilidad genérica a la que le venga bien un test: es el arreglo de un
 * consumo de hasta 164 MB de heap que existe **hoy, con veinte tareas** (ver
 * `docs/plan-escala.md` §1.6). Lo que hay que fijar es que el tope se respeta de
 * verdad y que respetarlo no vacía la caché justo cuando hace falta.
 */
class ByteBoundedCacheTest {

    /** Una imagen de [bytes] bytes. Lo único que la caché mira es cuánto pesa. */
    private class Blob(val bytes: Long)

    private fun cache(maxBytes: Long) = ByteBoundedCache<String, Blob>(maxBytes) { it.bytes }

    @Test
    fun `mientras cabe no echa a nadie`() {
        val cache = cache(100)
        cache.put("a", Blob(30))
        cache.put("b", Blob(30))
        cache.put("c", Blob(30))

        assertEquals(3, cache.size())
        assertEquals(90L, cache.weight())
        assertNotNull(cache.get("a"))
    }

    /**
     * **El caso que motiva la clase.** Con un tope por entradas, tres imágenes de
     * 10 MB caben en una caché «de 16». Con un tope por bytes, no.
     */
    @Test
    fun `al pasarse echa lo mas viejo hasta volver a caber`() {
        val cache = cache(100)
        cache.put("a", Blob(60))
        cache.put("b", Blob(60))

        assertEquals(1, cache.size())
        assertEquals(60L, cache.weight())
        assertNull("la vieja se fue", cache.get("a"))
        assertNotNull("la nueva se queda", cache.get("b"))
    }

    /** Lo que se echa es lo menos usado recientemente, no lo que entró antes. */
    @Test
    fun `usar una entrada la salva`() {
        val cache = cache(100)
        cache.put("a", Blob(40))
        cache.put("b", Blob(40))
        cache.get("a") // «a» pasa a ser la reciente
        cache.put("c", Blob(40))

        assertNotNull("se ha usado hace nada", cache.get("a"))
        assertNull("«b» es la que llevaba más tiempo sin tocarse", cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    /**
     * Una entrada más grande que el tope entero **se guarda igual**. Rechazarla haría
     * que la imagen que más cuesta descodificar fuera justo la única que se
     * descodifica en cada repintado del editor.
     */
    @Test
    fun `una entrada mas grande que el tope se guarda igual`() {
        val cache = cache(100)
        cache.put("pequena", Blob(10))
        cache.put("enorme", Blob(5_000))

        assertEquals(1, cache.size())
        assertNotNull(cache.get("enorme"))
        assertEquals(5_000L, cache.weight())
    }

    /** Reemplazar una clave descuenta lo viejo: si no, el peso se iría acumulando solo. */
    @Test
    fun `reemplazar una clave no suma dos veces`() {
        val cache = cache(1_000)
        cache.put("a", Blob(100))
        cache.put("a", Blob(300))

        assertEquals(1, cache.size())
        assertEquals(300L, cache.weight())
    }

    @Test
    fun `quitar descuenta el peso`() {
        val cache = cache(1_000)
        cache.put("a", Blob(100))
        cache.put("b", Blob(200))
        cache.remove("a")

        assertEquals(200L, cache.weight())
        assertNull(cache.get("a"))

        // Quitar lo que no está no descuenta nada.
        cache.remove("no-existe")
        assertEquals(200L, cache.weight())
    }

    /**
     * Una entrada de peso cero —el envoltorio del «la imagen no está» que cachea
     * [AttachmentService]— no puede desaparecer al primer empujón ni desajustar la
     * cuenta.
     */
    @Test
    fun `las entradas de peso cero caben y no descuadran la cuenta`() {
        val cache = cache(100)
        repeat(50) { cache.put("falta-$it", Blob(0)) }

        assertEquals(50, cache.size())
        assertEquals(0L, cache.weight())
    }

    /**
     * El escenario real del §1.6: capturas de 1600 px descodificadas a `INT_ARGB`
     * (10,2 MB cada una) contra el tope de 64 MB. Antes cabían dieciséis —164 MB—
     * porque lo que se contaba eran entradas.
     */
    @Test
    fun `seis capturas de 1600px llenan los 64 MB`() {
        val megabyte = 1024L * 1024
        val captura = 1600L * 1600 * 4 // 10,2 MB
        val cache = cache(64 * megabyte)

        repeat(16) { cache.put("captura-$it", Blob(captura)) }

        assertEquals("64 MB / 10,2 MB = seis", 6, cache.size())
        assertEquals(6 * captura, cache.weight())
    }
}
