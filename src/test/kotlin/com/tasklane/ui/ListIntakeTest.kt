package com.tasklane.ui

import com.tasklane.ui.toolwindow.ListIntake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File

/**
 * Qué se lee de lo que se pega o se suelta en la lista, y en qué orden: ficheros, píxeles y
 * texto, como en el diálogo.
 */
class ListIntakeTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `el texto se lee como texto`() {
        assertEquals(ListIntake.Taken.Text("uno\ndos"), ListIntake.read(StringSelection("uno\ndos")))
    }

    @Test
    fun `un texto en blanco no es nada`() {
        assertNull(ListIntake.read(StringSelection("  \n ")))
    }

    @Test
    fun `los ficheros que existen van antes que el texto`() {
        val file = folder.newFile("notas.txt")
        val taken = ListIntake.read(clip(DataFlavor.javaFileListFlavor to listOf(file), DataFlavor.stringFlavor to file.path))
        assertEquals(ListIntake.Taken.Files(listOf(file)), taken)
    }

    /**
     * macOS publica una URL copiada del navegador también como lista de ficheros. Sin mirar
     * que existan, la URL nunca llegaría a ser una tarea con su enlace.
     */
    @Test
    fun `una lista de ficheros que no existen cae al texto`() {
        val url = "https://example.com/issue/12"
        val taken = ListIntake.read(clip(DataFlavor.javaFileListFlavor to listOf(File(url)), DataFlavor.stringFlavor to url))
        assertEquals(ListIntake.Taken.Text(url), taken)
    }

    @Test
    fun `una captura va antes que su texto`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val taken = ListIntake.read(clip(DataFlavor.imageFlavor to image, DataFlavor.stringFlavor to "algo"))
        assertEquals(ListIntake.Taken.Pixels(image), taken)
    }

    @Test
    fun `un fichero de imagen va antes que sus pixeles`() {
        val file = folder.newFile("captura.png")
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val taken = ListIntake.read(clip(DataFlavor.javaFileListFlavor to listOf(file), DataFlavor.imageFlavor to image))
        assertEquals(ListIntake.Taken.Files(listOf(file)), taken)
    }

    @Test
    fun `acepta ficheros, imagenes y texto, y nada mas`() {
        assertTrue(ListIntake.accepts(arrayOf(DataFlavor.javaFileListFlavor)))
        assertTrue(ListIntake.accepts(arrayOf(DataFlavor.imageFlavor)))
        assertTrue(ListIntake.accepts(arrayOf(DataFlavor.stringFlavor)))
        assertFalse(ListIntake.accepts(arrayOf(DataFlavor.allHtmlFlavor)))
        assertFalse(ListIntake.accepts(emptyArray()))
    }

    /** Un portapapeles que ofrece varias cosas a la vez, como los de verdad. */
    private fun clip(vararg data: Pair<DataFlavor, Any>): Transferable = object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = data.map { it.first }.toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = data.any { it.first == flavor }

        override fun getTransferData(flavor: DataFlavor): Any =
            data.firstOrNull { it.first == flavor }?.second ?: throw UnsupportedFlavorException(flavor)
    }
}
