package com.tasklane.ui

import com.intellij.ui.SimpleTextAttributes
import com.tasklane.ui.toolwindow.Run
import com.tasklane.ui.toolwindow.TitleWrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El troceado del título, medido con una métrica falsa.
 *
 * Un carácter mide un píxel: así el ancho disponible se lee como «cuántos caracteres
 * caben» y cada caso dice exactamente dónde se espera el corte. Con la métrica real
 * estas pruebas dependerían de la fuente del sistema y sólo se podrían escribir con
 * un IDE arrancado, que es justo lo que [TitleWrap] existe para evitar.
 */
class TitleWrapTest {

    private val plain = SimpleTextAttributes.REGULAR_ATTRIBUTES

    /** Un carácter, un píxel. */
    private val oneToOne: (String, SimpleTextAttributes) -> Int = { text, _ -> text.length }

    private fun wrap(text: String, available: Int, maxLines: Int = 3): List<String> =
        TitleWrap.wrap(listOf(Run(text, plain, matched = true)), available, maxLines, oneToOne)
            .map { line -> line.joinToString("") { it.text } }

    @Test
    fun `lo que cabe se queda en una linea`() {
        assertEquals(listOf("uno dos tres"), wrap("uno dos tres", 40))
    }

    @Test
    fun `corta por palabras y no por caracteres`() {
        // 10 px de ancho: "uno dos" mide 7 y "uno dos tres" mide 12.
        assertEquals(listOf("uno dos ", "tres"), wrap("uno dos tres", 10))
    }

    @Test
    fun `el espacio del final de linea no cuenta para decidir si cabe`() {
        // "uno dos" son exactamente 7 caracteres visibles; el espacio que le sigue
        // no se ve, así que no puede empujar "dos" a la línea siguiente.
        assertEquals(listOf("uno dos"), wrap("uno dos", 7))
    }

    @Test
    fun `pasado el maximo de lineas se recorta con puntos suspensivos`() {
        val lines = wrap("aaa bbb ccc ddd eee fff", 4)
        assertEquals(3, lines.size)
        assertTrue("la ultima linea tiene que avisar de que hay mas: $lines", lines.last().endsWith("…"))
    }

    @Test
    fun `nunca devuelve mas lineas de las permitidas`() {
        val lines = TitleWrap.wrap(
            listOf(Run("a b c d e f g h i j k l m n", plain, matched = true)),
            available = 3,
            maxLines = 2,
            width = oneToOne,
        )
        assertEquals(2, lines.size)
    }

    @Test
    fun `una palabra mas larga que la linea se corta por caracteres`() {
        // Antes se dejaba salir entera: se prefería eso a que desapareciera. Y se salía
        // de verdad —la fila pasaba a medir lo que la palabra—, con lo que los botones
        // de la derecha, que se colocan contra esa medida, dejaban de caer donde se
        // ven: pulsar el marcador plegaba la tarjeta. Cortarla es lo que ya hacía la
        // descripción, y los puntos suspensivos dicen que hay más.
        assertEquals(listOf("supe…"), wrap("supercalifragilistico", 5, maxLines = 3))
    }

    @Test
    fun `sin ancho conocido no se envuelve`() {
        // El árbol todavía no tiene tamaño: cortar aquí daría un reparto que habría
        // que rehacer en el primer `doLayout`.
        assertEquals(listOf("uno dos tres"), wrap("uno dos tres", 0))
    }

    @Test
    fun `los tramos seguidos del mismo origen se vuelven a juntar`() {
        // Importa porque el resaltado de la búsqueda casa el patrón contra el texto
        // que se le pasa: troceado palabra a palabra perdería cualquier coincidencia
        // de más de una.
        val lines = TitleWrap.wrap(
            listOf(Run("uno dos tres cuatro", plain, matched = true)),
            available = 40,
            maxLines = 3,
            width = oneToOne,
        )
        assertEquals(1, lines.single().size)
    }

    @Test
    fun `un enlace conserva su etiqueta al caer en otra linea`() {
        val link = com.tasklane.domain.model.TaskLink("https://ejemplo.com", "ejemplo.com", 0..10)
        val lines = TitleWrap.wrap(
            listOf(Run("texto largo ", plain, matched = true), Run("ejemplo.com", plain, link)),
            available = 12,
            maxLines = 3,
            width = oneToOne,
        )
        val tagged = lines.flatten().filter { it.link != null }
        assertTrue("el tramo del enlace tiene que seguir etiquetado", tagged.isNotEmpty())
        assertEquals(link, tagged.single().link)
    }
}
