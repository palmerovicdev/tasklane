package com.tasklane.domain

import com.tasklane.domain.model.AnchorMove
import com.tasklane.domain.model.CodeAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adónde va un ancla cuando su fichero o su directorio cambia de sitio (2.13.0), y cómo
 * se escribe la ruta de un evento del sistema de ficheros para compararla con las que
 * se guardan.
 */
class AnchorMoveTest {

    @Test
    fun `renombrar un fichero lleva su ruta a la nueva`() {
        val move = AnchorMove("src/Auth.kt", "src/Login.kt")
        assertEquals("src/Login.kt", move.apply("src/Auth.kt"))
        assertNull("otro fichero no se toca", move.apply("src/Main.kt"))
    }

    @Test
    fun `mover un directorio arrastra todo lo que tiene dentro`() {
        val move = AnchorMove("src/auth", "src/security")
        assertEquals("src/security/Login.kt", move.apply("src/auth/Login.kt"))
        assertEquals("src/security/jwt/Token.kt", move.apply("src/auth/jwt/Token.kt"))
    }

    /** Por componentes y no por texto: `src/auth` no es el padre de `src/authz`. */
    @Test
    fun `un hermano con el mismo principio no se mueve`() {
        val move = AnchorMove("src/auth", "src/security")
        assertNull(move.apply("src/authz/Api.kt"))
        assertNull(move.apply("src/auth.kt"))
        assertNull("el padre tampoco", move.apply("src"))
    }

    /** Una refactorización mueve varias cosas de una vez, y se encadenan en su orden. */
    @Test
    fun `los movimientos se aplican en orden`() {
        val moves = listOf(AnchorMove("a/X.kt", "a/Y.kt"), AnchorMove("a", "b"))
        assertEquals("b/Y.kt", AnchorMove.rewrite("a/X.kt", moves))
        assertEquals("b/Z.kt", AnchorMove.rewrite("a/Z.kt", moves))
        assertEquals("c/X.kt", AnchorMove.rewrite("c/X.kt", moves))
    }

    @Test
    fun `la ruta de un evento se guarda como la de un ancla`() {
        assertEquals("src/Auth.kt", CodeAnchor.pathOf("/work/app", "/work/app/src/Auth.kt"))
        assertEquals("src/Auth.kt", CodeAnchor.pathOf("/work/app/", "/work/app/src/Auth.kt"))
        // Fuera del proyecto se guarda absoluta, como al capturarla.
        assertEquals("/work/lib/Util.kt", CodeAnchor.pathOf("/work/app", "/work/lib/Util.kt"))
        assertEquals("/work/application/A.kt", CodeAnchor.pathOf("/work/app", "/work/application/A.kt"))
        assertEquals("/work/app/A.kt", CodeAnchor.pathOf(null, "/work/app/A.kt"))
    }
}
