package com.tasklane.domain

import com.tasklane.domain.model.AnchorResolver
import com.tasklane.domain.model.CodeAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Que un ancla sobreviva a que le editen el fichero por encima, que es lo que decide
 * si merece confianza a la semana de haberla puesto.
 */
class AnchorResolverTest {

    private val file = listOf(
        "package com.ejemplo",
        "",
        "fun main() {",
        "    println(\"hola\")",
        "}",
    )

    private fun anchor(line: Int, text: String) = CodeAnchor.of("src/Main.kt", line, text = text)

    @Test
    fun `sin tocar nada se va a su linea`() {
        assertEquals(3, AnchorResolver.resolve(anchor(3, "println(\"hola\")"), file))
    }

    @Test
    fun `si le meten lineas por encima la sigue encontrando`() {
        val crecido = listOf("import java.time.Instant", "import java.util.Locale") + file

        assertEquals(5, AnchorResolver.resolve(anchor(3, "println(\"hola\")"), crecido))
    }

    @Test
    fun `y tambien si le quitan lineas por encima`() {
        val menguado = file.drop(2)

        assertEquals(1, AnchorResolver.resolve(anchor(3, "println(\"hola\")"), menguado))
    }

    /** La sangría cambia con cualquier refactor; el texto se compara recortado. */
    @Test
    fun `resangrar la linea no la pierde`() {
        val sangrado = file.map { if (it.contains("println")) "        $it" else it }

        assertEquals(3, AnchorResolver.resolve(anchor(3, "println(\"hola\")"), sangrado))
    }

    /** Dos líneas iguales: gana la de al lado, no la primera del fichero. */
    @Test
    fun `entre dos iguales gana la mas cercana`() {
        val repetido = listOf("cierra()", "a()", "b()", "c()", "d()", "cierra()")

        assertEquals(5, AnchorResolver.resolve(anchor(4, "cierra()"), repetido))
    }

    @Test
    fun `si la linea ya no existe se abre por donde estaba`() {
        val reescrito = listOf("package com.ejemplo", "", "fun main() = Unit")

        assertEquals(2, AnchorResolver.resolve(anchor(3, "println(\"hola\")"), reescrito))
    }

    /** Un ancla de fichero entero —capturada sin editor— no busca nada. */
    @Test
    fun `sin texto se queda en su linea`() {
        assertEquals(0, AnchorResolver.resolve(anchor(0, ""), file))
    }

    @Test
    fun `una linea mas alla del final se acota`() {
        assertEquals(file.lastIndex, AnchorResolver.resolve(anchor(99, ""), file))
    }

    @Test
    fun `un fichero vacio no revienta`() {
        assertEquals(0, AnchorResolver.resolve(anchor(7, "lo que sea"), emptyList()))
    }

    @Test
    fun `la etiqueta cuenta las lineas como las cuenta la gente`() {
        assertEquals("Main.kt:4", anchor(3, "").label)
    }

    @Test
    fun `la ruta se normaliza a barras y el texto se recorta`() {
        val windows = CodeAnchor.of("src\\main\\Main.kt", -3, text = "   hola   ")

        assertEquals("src/main/Main.kt", windows.path)
        assertEquals("Main.kt", windows.fileName)
        assertEquals(0, windows.line)
        assertEquals("hola", windows.text)
    }

    @Test
    fun `una linea larguisima no se guarda entera`() {
        val larga = CodeAnchor.of("a.kt", 0, text = "x".repeat(1000))

        assertEquals(CodeAnchor.MAX_TEXT, larga.text.length)
    }
}
