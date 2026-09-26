package com.tasklane.domain

import com.tasklane.domain.text.PastedLines
import org.junit.Assert.assertEquals
import org.junit.Test

/** Qué tareas salen de un texto pegado en la lista: una por línea, sin sus marcas de lista. */
class PastedLinesTest {

    @Test
    fun `una tarea por linea, en su orden`() {
        assertEquals(listOf("uno", "dos", "tres"), PastedLines.split("uno\ndos\r\ntres"))
    }

    @Test
    fun `quita vinetas, numeracion y casillas`() {
        val pasted = """
            - revisar el login
            * subir la versión
            + avisar a soporte
            • cerrar la incidencia
            1. primero
            2) segundo
            - [ ] pendiente
            - [x] hecha
            [ ] sin viñeta
            3. [ ] numerada con casilla
        """.trimIndent()
        assertEquals(
            listOf(
                "revisar el login",
                "subir la versión",
                "avisar a soporte",
                "cerrar la incidencia",
                "primero",
                "segundo",
                "pendiente",
                "hecha",
                "sin viñeta",
                "numerada con casilla",
            ),
            PastedLines.split(pasted),
        )
    }

    @Test
    fun `la sangria de una lista anidada tampoco se queda`() {
        assertEquals(listOf("padre", "hija"), PastedLines.split("- padre\n    - hija"))
    }

    @Test
    fun `se salta las lineas vacias y las que no dicen nada`() {
        assertEquals(listOf("título", "algo"), PastedLines.split("título\n=====\n\n   \n---\n- \n- [ ]\n|---|---|\nalgo"))
    }

    /** Sin espacio detrás no es una marca de lista: es el principio de lo que dice la línea. */
    @Test
    fun `lo que solo lo parece se queda como esta`() {
        assertEquals("-5 grados", PastedLines.clean("-5 grados"))
        assertEquals("*negrita* al principio", PastedLines.clean("*negrita* al principio"))
        assertEquals("2026. Un año raro", PastedLines.clean("2026. Un año raro"))
        assertEquals("[link](https://example.com)", PastedLines.clean("[link](https://example.com)"))
        assertEquals("#api arreglar", PastedLines.clean("#api arreglar"))
    }

    @Test
    fun `una URL es una tarea con su enlace`() {
        assertEquals(listOf("https://github.com/org/repo/issues/12"), PastedLines.split("https://github.com/org/repo/issues/12\n"))
    }

    @Test
    fun `nada que crear`() {
        assertEquals(emptyList<String>(), PastedLines.split(""))
        assertEquals(emptyList<String>(), PastedLines.split("\n \n- \n"))
    }
}
