package com.tasklane.domain

import com.tasklane.domain.text.TodoText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué parte de un comentario TODO es la tarea, y cuándo se puede borrar el comentario. */
class TodoTextTest {

    @Test
    fun `quita la palabra clave, el autor y el separador`() {
        assertEquals("arreglar el login", TodoText.clean(listOf("TODO(ana): arreglar el login")))
        assertEquals("revisar", TodoText.clean(listOf("FIXME - revisar")))
        assertEquals("sin separador", TodoText.clean(listOf("todo sin separador")))
    }

    @Test
    fun `junta las lineas de continuacion en una frase`() {
        assertEquals("partir esto en dos", TodoText.clean(listOf("TODO: partir esto", "  en dos")))
    }

    @Test
    fun `la palabra clave va en minusculas`() {
        assertEquals("fixme", TodoText.keyword("FIXME: algo"))
    }

    @Test
    fun `un comentario que es solo el TODO se puede borrar`() {
        val comment = "// TODO arreglar"
        assertTrue(TodoText.isOnlyTodo(comment, listOf(3 until comment.length)))
        val block = "/*\n * TODO: arreglar\n */"
        val start = block.indexOf("TODO")
        assertTrue(TodoText.isOnlyTodo(block, listOf(start until start + "TODO: arreglar".length)))
    }

    @Test
    fun `uno con mas cosas no`() {
        val doc = "/** Devuelve el usuario. TODO cachear */"
        val start = doc.indexOf("TODO")
        assertFalse(TodoText.isOnlyTodo(doc, listOf(start until start + "TODO cachear".length)))
    }

    @Test
    fun `las marcas del lenguaje cuentan`() {
        val comment = "REM TODO algo"
        assertTrue(TodoText.isOnlyTodo(comment, listOf(4 until comment.length), listOf("REM")))
    }
}
