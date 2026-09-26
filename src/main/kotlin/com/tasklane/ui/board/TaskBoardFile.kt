package com.tasklane.ui.board

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.VirtualFileWithoutContent
import com.intellij.testFramework.LightVirtualFile
import com.tasklane.TasklaneBundle
import com.tasklane.ui.common.TasklaneIcons
import javax.swing.Icon

/**
 * Lo que abre la pestaña del tablero (2.17.0). Un editor de la plataforma se abre sobre un
 * fichero, y el tablero no tiene ninguno en disco: éste existe sólo para eso.
 *
 * **Vive en su propio sistema de ficheros**, [TaskBoardFileSystem], y no en el de los
 * ficheros ligeros de siempre. Es lo que hace la propia plataforma con los ajustes abiertos
 * en una pestaña, y por lo mismo: la plataforma recuerda las pestañas por su URL, y al
 * reabrir el proyecto vuelve a pedir `tasklane://<proyecto>`. Con un fichero ligero esa URL
 * no llevaría a ningún sitio y el tablero no volvería a abrirse solo. (En la 2.17.0 no se
 * llegó a comprobar en un IDE que se reabra; por eso la documentación no lo promete.)
 *
 * Uno por proyecto —el camino es el `locationHash`—, porque la URL tiene que decir de qué
 * proyecto es el tablero que se reabre. Sin contenido: ningún editor de texto lo reclama.
 */
internal class TaskBoardFile(private val hash: String) :
    LightVirtualFile(TasklaneBundle.message("board.tab"), TaskBoardFileType, ""),
    VirtualFileWithoutContent {

    override fun getFileSystem(): VirtualFileSystem = TaskBoardFileSystem.getInstance()

    override fun getPath(): String = hash

    override fun isWritable(): Boolean = false

    override fun getPresentableName(): String = TasklaneBundle.message("board.tab")
}

/** El tipo de [TaskBoardFile]: sólo pone el icono de la pestaña. No se registra, como el de los ajustes. */
internal object TaskBoardFileType : FileType {
    override fun getName(): String = "Tasklane Board"

    override fun getDescription(): String = TasklaneBundle.message("board.filetype.description")

    override fun getDefaultExtension(): String = ""

    override fun getIcon(): Icon = TasklaneIcons.Board

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true
}

/**
 * `tasklane://<locationHash>`. Ver [TaskBoardFile]. Sólo sabe encontrar el tablero de un
 * proyecto abierto: todo lo demás que se le pide a un sistema de ficheros no tiene sentido
 * aquí.
 */
internal class TaskBoardFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? =
        ProjectManager.getInstance().openProjects
            .firstOrNull { !it.isDisposed && it.locationHash == path }
            ?.let(TaskBoardFiles::of)

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

    override fun refresh(asynchronous: Boolean) = Unit

    override fun deleteFile(requestor: Any?, vFile: VirtualFile) = throw UnsupportedOperationException()

    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) =
        throw UnsupportedOperationException()

    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) =
        throw UnsupportedOperationException()

    companion object {
        const val PROTOCOL = "tasklane"

        fun getInstance(): TaskBoardFileSystem =
            VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as TaskBoardFileSystem
    }
}

/** El [TaskBoardFile] de cada proyecto. Siempre el mismo: abrirlo otra vez va a la pestaña que ya hay. */
@Service(Service.Level.PROJECT)
internal class TaskBoardFiles(project: Project) {

    val file = TaskBoardFile(project.locationHash)

    companion object {
        fun of(project: Project): TaskBoardFile = project.service<TaskBoardFiles>().file

        /** Abre el tablero, o va a su pestaña si ya está abierta. */
        fun open(project: Project) {
            FileEditorManager.getInstance(project).openFile(of(project), true)
        }
    }
}
