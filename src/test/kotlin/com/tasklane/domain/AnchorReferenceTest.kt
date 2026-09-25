package com.tasklane.domain

import com.tasklane.domain.model.AnchorReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Que un sitio del código se pueda escribir como se escribe en cualquier otra parte. */
class AnchorReferenceTest {

    private fun ref(path: String, line: Int, column: Int = 0, hasLine: Boolean = true) =
        AnchorReference(path, line, column, hasLine)

    @Test
    fun `ruta y linea, que es el caso de siempre`() {
        assertEquals(
            ref("plans/website_deployment_live_activity_implementation.md", 27),
            AnchorReference.parse("plans/website_deployment_live_activity_implementation.md:28"),
        )
    }

    @Test
    fun `con columna`() {
        assertEquals(ref("src/Main.kt", 11, 4), AnchorReference.parse("src/Main.kt:12:5"))
    }

    @Test
    fun `sin linea apunta al fichero por su principio`() {
        assertEquals(ref("README.md", 0, hasLine = false), AnchorReference.parse("README.md"))
    }

    @Test
    fun `quita espacios, comillas y el punto-barra de delante`() {
        assertEquals(ref("src/Main.kt", 2), AnchorReference.parse("  `./src/Main.kt:3`  "))
        assertEquals(ref("src/Main.kt", 2), AnchorReference.parse("\"src/Main.kt:3\""))
    }

    @Test
    fun `la linea cero es la primera`() {
        assertEquals(ref("a.kt", 0), AnchorReference.parse("a.kt:0"))
    }

    @Test
    fun `ruta de windows con su linea`() {
        assertEquals(ref("C:/src/Main.kt", 11), AnchorReference.parse("C:\\src\\Main.kt:12"))
    }

    @Test
    fun `el formato de los enlaces de GitHub`() {
        assertEquals(ref("src/Main.kt", 27), AnchorReference.parse("src/Main.kt#L28"))
        assertEquals(ref("src/Main.kt", 27), AnchorReference.parse("src/Main.kt#L28-L40"))
        assertEquals(ref("src/Main.kt", 27, 4), AnchorReference.parse("src/Main.kt#L28C5"))
    }

    @Test
    fun `el formato entre parentesis de algunas trazas`() {
        assertEquals(ref("Main.kt", 27), AnchorReference.parse("Main.kt(28)"))
        assertEquals(ref("Main.kt", 27, 2), AnchorReference.parse("Main.kt(28, 3)"))
    }

    @Test
    fun `los dos puntos del final se ignoran`() {
        // Así sale en muchas trazas: `src/Main.kt:12: error ...` copiado hasta el `:`.
        assertEquals(ref("src/Main.kt", 11), AnchorReference.parse("src/Main.kt:12:"))
    }

    @Test
    fun `nada escrito no es una referencia`() {
        assertNull(AnchorReference.parse(""))
        assertNull(AnchorReference.parse("   "))
        assertNull(AnchorReference.parse("``"))
    }
}
