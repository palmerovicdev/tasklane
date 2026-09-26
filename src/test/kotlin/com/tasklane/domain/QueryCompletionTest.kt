package com.tasklane.domain

import com.tasklane.domain.model.TagCount
import com.tasklane.domain.query.QueryCompletion
import com.tasklane.domain.query.QueryCompletionResult
import com.tasklane.domain.query.QueryVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryCompletionTest {

    private val vocabulary = QueryVocabulary(
        states = listOf("ToDo", "Doing", "In Review", "Done"),
        priorities = listOf("High", "Normal", "Low"),
        repos = listOf("proyecto", "web"),
        tags = listOf(TagCount("api", 7), TagCount("apis", 1), TagCount("infra", 3)),
    )

    /** Con el cursor al final, como al teclear. */
    private fun complete(text: String, explicit: Boolean = false, caret: Int = text.length): QueryCompletionResult? =
        QueryCompletion.complete(text, caret, vocabulary, explicit)

    private fun texts(text: String, explicit: Boolean = false) = complete(text, explicit)?.suggestions?.map { it.text }

    @Test
    fun `tras state se ofrecen los estados y el primero sale elegido`() {
        val found = complete("fallo state:")!!
        assertEquals(listOf("state:ToDo", "state:Doing", "state:\"In Review\"", "state:Done"), found.suggestions.map { it.text })
        assertTrue(found.preselect)
        assertEquals("sustituye el token, no la consulta", 6, found.from)
    }

    @Test
    fun `los valores casan por prefijo sin mayusculas ni comillas`() {
        assertEquals(listOf("state:Doing", "state:Done"), texts("state:do"))
        assertEquals(listOf("state:\"In Review\""), texts("state:\"in r"))
        assertEquals(listOf("p:Low"), texts("p:l"))
    }

    @Test
    fun `tras la almohadilla, las etiquetas con cuantas tareas llevan`() {
        val found = complete("#ap")!!
        assertEquals(listOf("#api", "#apis"), found.suggestions.map { it.text })
        assertEquals("7", found.suggestions.first().hint)
    }

    @Test
    fun `la negacion se conserva en lo que se inserta`() {
        assertEquals(listOf("-#infra"), texts("-#in"))
        assertEquals(listOf("-p:Low"), texts("-p:lo"))
        assertEquals(listOf("-is:done"), texts("-is:d"))
    }

    @Test
    fun `is, has y las fechas ofrecen sus valores`() {
        assertEquals(listOf("is:open", "is:overdue"), texts("is:o"))
        assertEquals(listOf("has:checklist", "has:code"), texts("has:c"))
        assertEquals(listOf("due:today", "due:tomorrow", "due:week", "due:month", "due:<7d"), texts("due:"))
        assertEquals("lo cerrado mira atrás", listOf("closed:today", "closed:yesterday", "closed:week", "closed:month", "closed:<7d"), texts("closed:"))
    }

    /** Quien busca «stack» no puede encontrarse con un `state:` puesto al pulsar Enter. */
    @Test
    fun `una palabra que empieza como un operador lo ofrece sin elegirlo`() {
        val found = complete("st")!!
        assertEquals(listOf("state:"), found.suggestions.map { it.text })
        assertFalse(found.preselect)
        assertEquals(listOf("closed:", "created:"), texts("c", explicit = true))
    }

    @Test
    fun `una sola letra no abre nada si no se pide`() {
        assertNull(complete("s"))
        assertNull(complete("stack"))
    }

    @Test
    fun `pedido a mano y sin nada escrito salen todos los operadores`() {
        val all = texts("fallo ", explicit = true)!!
        assertEquals("state:", all.first())
        assertTrue(all.containsAll(listOf("is:", "has:", "due:", "closed:", "created:", "updated:", "file:", "repo:", "#")))
        assertNull("sin pedirlo, un token vacío no abre nada", complete("fallo "))
    }

    @Test
    fun `repo solo con mas de un repositorio`() {
        val single = vocabulary.copy(repos = listOf("proyecto"))
        val all = QueryCompletion.complete("", 0, single, explicit = true)!!.suggestions.map { it.text }
        assertFalse(all.contains("repo:"))
    }

    @Test
    fun `lo que ya esta escrito entero no se ofrece`() {
        assertNull(complete("state:Doing"))
        assertNull(complete("is:overdue"))
    }

    @Test
    fun `un operador sin valores que ofrecer no abre nada`() {
        assertNull(complete("file:Auth"))
        assertNull(complete("https://example.com"))
    }

    @Test
    fun `con el cursor en medio se sustituye el token entero`() {
        val text = "state:do fallo"
        val found = QueryCompletion.complete(text, 8, vocabulary, explicit = false)!!
        assertEquals(0, found.from)
        assertEquals(8, found.to)
        assertEquals(listOf("state:Doing", "state:Done"), found.suggestions.map { it.text })
    }

    /** Lo que se sustituye al insertar, con un valor entre comillas que lleva espacios. */
    @Test
    fun `el token respeta las comillas`() {
        val text = "fallo state:\"In Review\" x"
        assertEquals(6 to 23, QueryCompletion.token(text, 15))
        assertEquals(24 to 25, QueryCompletion.token(text, text.length))
    }
}
