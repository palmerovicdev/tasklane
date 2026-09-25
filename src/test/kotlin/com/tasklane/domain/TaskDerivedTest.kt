package com.tasklane.domain

import com.tasklane.domain.model.DetailBlock
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.LinkExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Lo que la tarjeta deriva del cuerpo: descripción y lista de comprobación.
 *
 * Ninguna de las dos es un campo guardado, y eso es el punto: se escriben en el
 * editor como Markdown normal y la fila las entiende, sin que haya dos fuentes de
 * verdad que puedan discrepar.
 */
class TaskDerivedTest {

    private fun task(body: String) = Task(
        id = TaskId.random(),
        repo = RepoKey.ROOT,
        body = body,
        stateId = TasklaneConfig.TODO,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /**
     * Que el cuerpo llegue a la tarjeta desplegada **en el orden en que se escribió**.
     * Es lo que permite pintar cada captura debajo de su párrafo, como hace el
     * diálogo; con el texto por un lado y las imágenes por otro acabarían todas
     * amontonadas al final.
     */
    @Test
    fun `el cuerpo conserva el orden de texto e imagenes`() {
        val task = task("Arreglar login\nFalla al refrescar\n![](tasklane:$SHA)\nY en Safari tampoco")

        assertEquals(
            listOf("t:Falla al refrescar", "i:$SHA", "t:Y en Safari tampoco"),
            task.detailBlocks.map(::describe),
        )
    }

    /**
     * Cada párrafo lleva **sus** enlaces, con el rango en coordenadas del párrafo ya
     * limpio: sin la captura que tenía delante y sin la sangría. Es lo que deja pulsar un
     * enlace del cuerpo sobre la tarjeta (2.3.0) sin volver a buscarlo al pintar.
     */
    @Test
    fun `los enlaces del cuerpo van con su parrafo y en sus coordenadas`() {
        val body = "Migrar\n   ![](tasklane:$SHA) la guia: https://ejemplo.com/guia\nsin nada\ny [los pasos](https://ejemplo.com/pasos)"
        val task = task(body).copy(links = LinkExtractor.extract(body))

        val texts = task.detailTexts
        assertEquals(listOf("la guia: https://ejemplo.com/guia", "sin nada", "y [los pasos](https://ejemplo.com/pasos)"), texts.map { it.text })

        val guia = texts[0].links.single()
        assertEquals("https://ejemplo.com/guia", texts[0].text.substring(guia.range.first, guia.range.last + 1))
        assertTrue(texts[1].links.isEmpty())
        val pasos = texts[2].links.single()
        assertEquals("https://ejemplo.com/pasos", pasos.url)
        assertEquals("[los pasos](https://ejemplo.com/pasos)", texts[2].text.substring(pasos.range.first, pasos.range.last + 1))
    }

    /**
     * El título es su línea **sin** las imágenes, así que una captura escrita ahí no
     * es parte de él. Si tampoco se recogiera aquí no se pintaría en ningún sitio.
     */
    @Test
    fun `una imagen en la linea del titulo tambien se recoge`() {
        val task = task("Arreglar login ![](tasklane:$SHA)\nFalla al refrescar")

        assertEquals("Arreglar login ![](tasklane:$SHA)", task.title)
        assertEquals(listOf("i:$SHA", "t:Falla al refrescar"), task.detailBlocks.map(::describe))
    }

    /** Las líneas de texto salen de los bloques, así que no pueden discrepar de ellos. */
    @Test
    fun `las lineas del cuerpo son los bloques de texto`() {
        val task = task("Arreglar login\nprimera\n![](tasklane:$SHA)\nsegunda")

        assertEquals(listOf("primera", "segunda"), task.detailLines)
        assertEquals("primera", task.description)
    }

    private fun describe(block: DetailBlock): String = when (block) {
        is DetailBlock.Text -> "t:" + block.text
        is DetailBlock.Image -> "i:" + block.id.value
        is DetailBlock.Code -> "c:" + block.lines.joinToString("/")
    }

    @Test
    fun `la descripcion es la primera linea util bajo el titulo`() {
        val task = task("Arreglar login\n\nFalla al refrescar el token")
        assertEquals("Arreglar login", task.title)
        assertEquals("Falla al refrescar el token", task.description)
    }

    @Test
    fun `una tarea de una sola linea no tiene descripcion`() {
        val task = task("Comprar pan")
        assertEquals("", task.description)
        assertFalse(task.hasDetail)
    }

    @Test
    fun `una linea que solo es una imagen no es descripcion`() {
        val sha = "a".repeat(64)
        val task = task("Bug del boton\n![](tasklane:$sha)\nPasa en dark mode")
        assertEquals("Pasa en dark mode", task.description)
    }

    @Test
    fun `un bloque entre vallas sale como codigo literal y sin las vallas`() {
        val task = task("Arreglar el parser\n```kotlin\n  val *p = 1\n  if (a) b()\n```\nDespués **esto**")

        assertEquals("Arreglar el parser", task.title)
        val blocks = task.detailBlocks
        assertEquals(2, blocks.size)
        val code = blocks[0] as DetailBlock.Code
        assertEquals(listOf("val *p = 1", "if (a) b()"), code.lines)
        assertEquals("kotlin", code.language)
        assertEquals("Después **esto**", (blocks[1] as DetailBlock.Text).text)
        assertTrue(task.hasDetail)
    }

    @Test
    fun `el titulo no es una valla`() {
        val task = task("```\nnpm run build\n```\nFalla en CI")

        assertEquals("Falla en CI", task.title)
        assertEquals(listOf("npm run build"), (task.detailBlocks.single() as DetailBlock.Code).lines)
    }

    @Test
    fun `un cuerpo que es solo codigo toma su primera linea de titulo`() {
        val task = task("```\nuno\ndos\n```")

        assertEquals("uno", task.title)
        assertEquals(listOf("dos"), (task.detailBlocks.single() as DetailBlock.Code).lines)
    }

    @Test
    fun `vencida solo si paso la fecha y sigue abierta`() {
        val ayer = Instant.parse("2026-09-12T10:00:00Z")
        val ahora = Instant.parse("2026-09-13T10:00:00Z")
        assertTrue(task("x").copy(dueDate = ayer).isOverdue(ahora))
        assertFalse("una tarea cerrada ya no vence", task("x").copy(dueDate = ayer, completedAt = ahora).isOverdue(ahora))
        assertFalse("sin fecha no hay vencimiento", task("x").isOverdue(ahora))
    }

    private companion object {
        /** Un SHA-256 de mentira, con la longitud exacta que exige `ImageRefParser`. */
        const val SHA = "abc123def456abc123def456abc123def456abc123def456abc123def4561234"
    }
}
