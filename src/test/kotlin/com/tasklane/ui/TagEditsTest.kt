package com.tasklane.ui

import com.tasklane.domain.model.TagColor
import com.tasklane.domain.model.TagCount
import com.tasklane.ui.settings.TagEdits
import com.tasklane.ui.settings.TagRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lo que la tabla de etiquetas de los ajustes le hace al proyecto al aplicar (P30). */
class TagEditsTest {

    private val blue = TagColor(0x0000AA, 0x6666FF)
    private val red = TagColor(0xAA0000, 0xFF6666)

    private fun row(tag: String, tasks: Int = 1, color: TagColor? = null) = TagRow.of(TagCount(tag, tasks), color)

    // ------------------------------------------------------------------ nombres

    @Test
    fun `sin tocar nada no se renombra nada`() {
        assertEquals(emptyMap<String, String?>(), TagEdits.renames(listOf(row("api"), row("ui")), emptyMap()))
    }

    @Test
    fun `renombrar es de la etiqueta de hoy a la nueva, limpia como en el dialogo`() {
        val api = row("api").apply { name = "  #backend " }
        assertEquals(mapOf("api" to "backend"), TagEdits.renames(listOf(api, row("ui")), emptyMap()))
    }

    @Test
    fun `fusionar es renombrar a una que ya esta`() {
        val apis = row("apis").apply { name = "api" }
        assertEquals(mapOf("apis" to "api"), TagEdits.renames(listOf(row("api"), apis), emptyMap()))
    }

    @Test
    fun `intercambiar dos nombres son dos renombres que se aplican a la vez`() {
        val a = row("a").apply { name = "b" }
        val b = row("b").apply { name = "a" }
        assertEquals(mapOf("a" to "b", "b" to "a"), TagEdits.renames(listOf(a, b), emptyMap()))
    }

    @Test
    fun `borrar sin destino la quita, y con destino va a la fila con el nombre que lleve al aplicar`() {
        val api = row("api").apply { name = "backend" }
        val renames = TagEdits.renames(listOf(api), mapOf("wip" to null, "apis" to "api"))
        assertEquals(mapOf("api" to "backend", "wip" to null, "apis" to "backend"), renames)
    }

    @Test
    fun `borrar hacia una que despues tambien se borra sigue la cadena`() {
        val renames = TagEdits.renames(listOf(row("c")), mapOf("a" to "b", "b" to "c"))
        assertEquals(mapOf("a" to "c", "b" to "c"), renames)
        // Y si la cadena acaba en «quitar», se quitan las dos.
        assertEquals(mapOf("a" to null, "b" to null), TagEdits.renames(emptyList(), mapOf("a" to "b", "b" to null)))
    }

    @Test
    fun `un ciclo acaba quitando en vez de dar vueltas`() {
        assertEquals(mapOf("a" to null, "b" to null), TagEdits.renames(emptyList(), mapOf("a" to "b", "b" to "a")))
    }

    @Test
    fun `un nombre vacio o con separadores no se puede aplicar`() {
        assertEquals(TagEdits.Problem.BLANK, TagEdits.problemOf(row("a").apply { name = "  " }))
        assertEquals(TagEdits.Problem.BLANK, TagEdits.problemOf(row("a").apply { name = "#" }))
        assertEquals(TagEdits.Problem.SEPARATOR, TagEdits.problemOf(row("a").apply { name = "en revision" }))
        assertEquals(TagEdits.Problem.SEPARATOR, TagEdits.problemOf(row("a").apply { name = "a,b" }))
        assertNull(TagEdits.problemOf(row("a").apply { name = "#api" }))
    }

    // ------------------------------------------------------------------ colores

    @Test
    fun `sin cambios los colores quedan como estaban`() {
        val base = mapOf("api" to blue, "otra" to red)
        val rows = listOf(row("api", color = blue), row("ui"))
        assertEquals(base, TagEdits.colors(base, rows, emptyMap(), keep = null))
    }

    @Test
    fun `el color se guarda sin mirar mayusculas`() {
        val colors = TagEdits.colors(emptyMap(), listOf(row("API", color = blue)), emptyMap(), keep = null)
        assertEquals(mapOf("api" to blue), colors)
    }

    @Test
    fun `renombrar se lleva el color, y deja el viejo si la etiqueta sigue en otro repositorio`() {
        val api = row("api", color = blue).apply { name = "backend" }
        val renames = TagEdits.renames(listOf(api), emptyMap())
        val base = mapOf("api" to blue)
        assertEquals(mapOf("backend" to blue), TagEdits.colors(base, listOf(api), renames, keep = emptySet()))
        assertEquals(
            mapOf("api" to blue, "backend" to blue),
            TagEdits.colors(base, listOf(api), renames, keep = setOf("API")),
        )
    }

    @Test
    fun `mientras la pagina esta abierta no se quita el color de nadie`() {
        val api = row("api", color = blue).apply { name = "backend" }
        val colors = TagEdits.colors(mapOf("api" to blue), listOf(api), TagEdits.renames(listOf(api), emptyMap()), keep = null)
        assertEquals(mapOf("api" to blue, "backend" to blue), colors)
    }

    @Test
    fun `fusionar una sin color en otra con color no le quita el suyo`() {
        val apis = row("apis").apply { name = "api" }
        val api = row("api", color = blue)
        val renames = TagEdits.renames(listOf(api, apis), emptyMap())
        assertEquals(mapOf("api" to blue), TagEdits.colors(mapOf("api" to blue), listOf(api, apis), renames, keep = emptySet()))
    }

    @Test
    fun `fusionar dos con color deja el de la que se queda`() {
        val apis = row("apis", color = red).apply { name = "api" }
        val api = row("api", color = blue)
        val rows = listOf(apis, api)
        val renames = TagEdits.renames(rows, emptyMap())
        val base = mapOf("api" to blue, "apis" to red)
        assertEquals(mapOf("api" to blue), TagEdits.colors(base, rows, renames, keep = emptySet()))
    }

    @Test
    fun `borrar una etiqueta que no queda en ningun sitio borra su color`() {
        val base = mapOf("wip" to red, "api" to blue)
        val rows = listOf(row("api", color = blue))
        val renames = TagEdits.renames(rows, mapOf("wip" to null))
        assertEquals(mapOf("api" to blue), TagEdits.colors(base, rows, renames, keep = emptySet()))
        assertEquals(base, TagEdits.colors(base, rows, renames, keep = setOf("wip")))
    }

    @Test
    fun `quitar el color a mano lo quita, no tenerlo no`() {
        val base = mapOf("api" to blue)
        val cleared = row("api").apply { colorCleared = true }
        assertEquals(emptyMap<String, TagColor>(), TagEdits.colors(base, listOf(cleared), emptyMap(), keep = null))
        assertEquals(base, TagEdits.colors(base, listOf(row("api")), emptyMap(), keep = null))
    }

    @Test
    fun `las dos formas de escribir la misma etiqueta comparten color`() {
        // `API` se renombra y `api` sigue: el color de `api` no se va con la otra.
        val upper = row("API", color = blue).apply { name = "rest" }
        val lower = row("api", color = blue)
        val rows = listOf(upper, lower)
        val colors = TagEdits.colors(mapOf("api" to blue), rows, TagEdits.renames(rows, emptyMap()), keep = emptySet())
        assertEquals(mapOf("api" to blue, "rest" to blue), colors)
    }
}
