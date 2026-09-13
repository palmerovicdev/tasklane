package com.tasklane.ui.editor

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import javax.imageio.ImageIO

/**
 * Qué cuenta como «una imagen en el portapapeles», en un solo sitio.
 *
 * Dos cosas: una imagen de verdad —lo que deja una captura de pantalla— o un fichero
 * de imagen copiado del explorador. El segundo caso cuesta cuatro líneas y evita la
 * pregunta obvia de por qué no funciona.
 *
 * Lo usan el editor del diálogo y el popup de *Quick Add*. Con la regla escrita dos
 * veces, pegar acabaría haciendo cosas distintas según dónde se pegue.
 */
internal object ClipboardImage {

    /**
     * Si el portapapeles trae algo que **pueda** ser una imagen, sin llegar a
     * decodificarla: esto se pregunta en el EDT cada vez que se pulsa el atajo, y leer
     * un PNG de disco ahí se notaría.
     */
    fun available(): Boolean {
        val contents = CopyPasteManager.getInstance().contents ?: return false
        return runCatching {
            contents.isDataFlavorSupported(DataFlavor.imageFlavor) || file(contents) != null
        }.getOrDefault(false)
    }

    fun read(): Image? {
        val contents = CopyPasteManager.getInstance().contents ?: return null
        runCatching {
            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                return contents.getTransferData(DataFlavor.imageFlavor) as? Image
            }
            return file(contents)?.let(ImageIO::read)
        }
        return null
    }

    /**
     * El fichero de imagen del portapapeles, o `null`.
     *
     * Que sea un fichero no sobra: macOS publica una URL copiada del navegador como
     * lista de ficheros, así que sin comprobarlo, pegar un enlace entraba por aquí y
     * se quedaba por el camino.
     */
    private fun file(contents: Transferable): File? {
        if (!contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
        @Suppress("UNCHECKED_CAST")
        val files = contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
        return files?.firstOrNull()?.takeIf(ImageInserter::isImage)
    }
}
