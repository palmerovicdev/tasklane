package com.tasklane.domain

import com.tasklane.domain.query.QueryParser
import com.tasklane.domain.query.TermHits
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dónde casa una búsqueda en un texto, con las reglas del índice: lo que la tarjeta resalta
 * y lo que decide la línea que enseña plegada.
 */
class TermHitsTest {

    private fun hits(text: String, query: String): List<String> =
        TermHits.find(text, QueryParser.parse(query).terms).map { text.substring(it) }

    @Test
    fun `casa por prefijo de palabra y no por el medio`() {
        assertEquals(listOf("log"), hits("Fallo al hacer login", "log"))
        assertEquals(emptyList<String>(), hits("Revisar el catalog", "log"))
    }

    /** Lo que la 2.17 no resaltaba: la búsqueda ya ignoraba los acentos, el resaltado no. */
    @Test
    fun `ignora acentos y mayusculas en los dos sentidos`() {
        assertEquals(listOf("autenticación"), hits("La autenticación falla", "autenticacion"))
        assertEquals(listOf("Autenticacion"), hits("Autenticacion caida", "AUTENTICACIÓN"))
    }

    /** Una letra con el acento aparte sigue siendo una letra: el acento va con ella. */
    @Test
    fun `un acento descompuesto cae dentro de su letra`() {
        val text = "canción nueva"
        assertEquals(listOf("canción"), hits(text, "cancion"))
    }

    /** Cada término por su cuenta: el matcher de antes pedía «token expira» en ese orden. */
    @Test
    fun `cada termino casa por su cuenta y en cualquier orden`() {
        assertEquals(listOf("expira", "token"), hits("expira el token", "token expira"))
    }

    @Test
    fun `todas las apariciones y sin solaparse`() {
        assertEquals(listOf("api", "api"), hits("api nueva y api vieja", "api"))
        // Se resalta lo tecleado, no la palabra entera: el prefijo es lo que casó.
        assertEquals(listOf("Token"), hits("Tokens", "tok token"))
    }

    /** `auth.kt` o `"in review"` son frases: sus palabras seguidas, la última como prefijo. */
    @Test
    fun `un termino con separadores es una frase`() {
        assertEquals(listOf("Auth.kt"), hits("Mirar Auth.kt y Auth.java", "auth.kt"))
        assertEquals(listOf("In Rev"), hits("Pasar a In Review mañana", "\"in rev\""))
        assertEquals(emptyList<String>(), hits("in the review", "\"in rev\""))
    }

    @Test
    fun `cuenta los terminos distintos que casan`() {
        val terms = QueryParser.parse("token expira sesion").terms
        assertEquals(2, TermHits.count("El token expira pronto", terms))
        assertEquals(1, TermHits.count("La sesión", terms))
        assertEquals(0, TermHits.count("Nada que ver", terms))
    }

    @Test
    fun `sin terminos o sin palabras no casa nada`() {
        assertEquals(emptyList<String>(), hits("texto", ""))
        assertEquals(emptyList<IntRange>(), TermHits.find("a -- b", listOf("--")))
    }
}
