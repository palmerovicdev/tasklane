package com.tasklane.ui

import com.tasklane.ui.editor.ImageInserter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La lista de formatos que se aceptan es una sola y la comparten los tres gestos que
 * adjuntan —pegar, soltar y elegir—. Esto es lo que impide que se separen.
 */
class ImageInserterTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `acepta imagenes por extension, sin mirar dentro`() {
        assertTrue(ImageInserter.isImage(folder.newFile("captura.png")))
        assertTrue(ImageInserter.isImage(folder.newFile("foto.JPG")))
        assertTrue(ImageInserter.isImage(folder.newFile("animacion.gif")))
    }

    @Test
    fun `lo que no es imagen no entra`() {
        assertFalse(ImageInserter.isImage(folder.newFile("informe.pdf")))
        assertFalse(ImageInserter.isImage(folder.newFile("notas.txt")))
        assertFalse(ImageInserter.isImage(folder.newFile("sin-extension")))
    }

    /**
     * Una carpeta llamada `capturas.png` existe y acaba en `.png`, y sin la
     * comprobación de fichero entraría por aquí para morir al decodificarla.
     */
    @Test
    fun `una carpeta no es una imagen aunque lo parezca`() {
        assertFalse(ImageInserter.isImage(folder.newFolder("capturas.png")))
    }
}
