package com.tasklane.domain

import com.tasklane.domain.model.AnchorReference
import com.tasklane.domain.model.AnchorResolver
import com.tasklane.domain.model.AnchorSnippet
import com.tasklane.domain.model.CodeAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anclas que abarcan un bloque de líneas (2.15.0): cómo se nombran, cómo se escriben y
 * dónde caen cuando el fichero cambia.
 */
class AnchorRangeTest {

    private val file = listOf(
        "package auth",
        "",
        "class Login {",
        "    fun login() {",
        "        check(user)",
        "        save(user)",
        "    }",
        "}",
    )

    private fun block() = CodeAnchor.of("src/auth/Login.kt", 3, 4, "fun login() {", span = 3)

    @Test
    fun `un bloque se nombra con su primera y su ultima linea`() {
        val anchor = block()

        assertTrue(anchor.isRange)
        assertEquals(6, anchor.endLine)
        assertEquals("Login.kt:4-7", anchor.label)
        assertEquals("src/auth/Login.kt:4-7", anchor.reference)
    }

    @Test
    fun `una linea sola se nombra como siempre`() {
        val anchor = CodeAnchor.of("src/auth/Login.kt", 3)

        assertFalse(anchor.isRange)
        assertEquals("Login.kt:4", anchor.label)
        assertEquals("src/auth/Login.kt:4", anchor.reference)
    }

    @Test
    fun `un largo negativo es una linea`() {
        assertEquals(0, CodeAnchor.of("a.kt", 3, span = -2).span)
    }

    // ---------------------------------------------------------------- escrito

    @Test
    fun `ruta, linea y linea final`() {
        val ref = AnchorReference.parse("src/auth/Login.kt:4-7")!!

        assertEquals("src/auth/Login.kt", ref.path)
        assertEquals(3, ref.line)
        assertEquals(3, ref.span)
        assertEquals(6, ref.endLine)
    }

    @Test
    fun `un rango escrito al reves es el mismo bloque`() {
        assertEquals(AnchorReference.parse("a.kt:4-7"), AnchorReference.parse("a.kt:7-4"))
    }

    @Test
    fun `el rango de un enlace de GitHub ya no pierde el final`() {
        val ref = AnchorReference.parse("src/Main.kt#L12-L20")!!
        assertEquals(11, ref.line)
        assertEquals(8, ref.span)

        val withColumns = AnchorReference.parse("src/Main.kt#L12C5-L20C1")!!
        assertEquals(11, withColumns.line)
        assertEquals(4, withColumns.column)
        assertEquals(8, withColumns.span)
    }

    @Test
    fun `lo que ya se escribia sigue leyendose igual`() {
        assertEquals(0, AnchorReference.parse("src/Main.kt:12:5")!!.span)
        assertEquals(4, AnchorReference.parse("src/Main.kt:12:5")!!.column)
        assertEquals(0, AnchorReference.parse("src/Main.kt(12)")!!.span)
        assertEquals(0, AnchorReference.parse("src/Main.kt")!!.span)
    }

    @Test
    fun `lo que se ensena se puede volver a escribir`() {
        val anchor = block()
        val ref = AnchorReference.parse(anchor.reference)!!

        assertEquals(anchor.path, ref.path)
        assertEquals(anchor.line, ref.line)
        assertEquals(anchor.span, ref.span)
    }

    @Test
    fun `una ruta con guiones y numeros no se confunde con un rango`() {
        val ref = AnchorReference.parse("docs/v1-2/notes.md:3")!!
        assertEquals("docs/v1-2/notes.md", ref.path)
        assertEquals(2, ref.line)
        assertEquals(0, ref.span)
    }

    // ------------------------------------------------------------ dónde cae

    @Test
    fun `sin tocar el fichero el bloque esta donde se anclo`() {
        assertEquals(3..6, AnchorResolver.range(block(), file))
    }

    @Test
    fun `si crece el codigo de encima el bloque baja entero`() {
        val grown = listOf("import a", "import b") + file

        assertEquals(5..8, AnchorResolver.range(block(), grown))
    }

    @Test
    fun `el final se acota al final del fichero`() {
        val cut = file.take(5)

        assertEquals(3..4, AnchorResolver.range(block(), cut))
    }

    @Test
    fun `una linea sola es un bloque de una linea`() {
        val anchor = CodeAnchor.of("a.kt", 4, text = "check(user)")

        assertEquals(4..4, AnchorResolver.range(anchor, file))
    }

    // ------------------------------------------------------------- el código

    @Test
    fun `el fragmento es el codigo de hoy sin la sangria comun`() {
        val snippet = AnchorSnippet.of(block(), file)!!

        assertEquals(listOf("fun login() {", "    check(user)", "    save(user)", "}"), snippet.lines)
        assertEquals(4, snippet.total)
        assertEquals(0, snippet.hidden)
    }

    @Test
    fun `un bloque largo se lee hasta el tope y dice cuanto queda`() {
        val long = (1..100).map { "linea $it" }
        val anchor = CodeAnchor.of("a.kt", 0, text = "linea 1", span = 99)

        val snippet = AnchorSnippet.of(anchor, long, max = 10)!!

        assertEquals(10, snippet.lines.size)
        assertEquals(100, snippet.total)
        assertEquals(90, snippet.hidden)
    }

    @Test
    fun `los tabuladores se hacen espacios`() {
        val tabs = listOf("\tfun a() {", "\t\tb()", "\t}")
        val anchor = CodeAnchor.of("a.kt", 0, text = "fun a() {", span = 2)

        assertEquals(listOf("fun a() {", "    b()", "}"), AnchorSnippet.of(anchor, tabs)!!.lines)
    }

    @Test
    fun `un fichero vacio no tiene fragmento`() {
        assertNull(AnchorSnippet.of(block(), emptyList()))
    }
}
