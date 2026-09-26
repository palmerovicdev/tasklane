package com.tasklane.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.tasklane.code.AnchorFiles
import com.tasklane.code.AnchorMarkers

/**
 * Enciende las marcas del editor al abrir el proyecto, y quien vigila que las anclas
 * sigan llevando a su fichero (2.13.0).
 *
 * Un servicio de la plataforma no existe hasta que alguien lo pide, y aquí no hay nadie
 * que lo pida: las marcas no se piden, aparecen. Sin este empujón no se verían hasta
 * abrir la tool window o los ajustes —o sea, justo en los proyectos donde uno *no* abre
 * la ventana, que son en los que más falta hacen—. Y con [AnchorFiles] pasa lo mismo, y
 * peor: un fichero renombrado con la ventana cerrada dejaría sus anclas atrás.
 *
 * No espera a nada: los dos se suscriben al modelo y trabajan en cuanto haya tareas
 * leídas, así que llegar antes que la carga no es un problema sino el orden normal.
 */
internal class AnchorMarkerActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        AnchorMarkers.getInstance(project)
        AnchorFiles.getInstance(project)
    }
}
