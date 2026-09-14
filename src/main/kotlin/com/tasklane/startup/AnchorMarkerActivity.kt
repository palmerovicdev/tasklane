package com.tasklane.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.tasklane.code.AnchorMarkers

/**
 * Enciende las marcas del editor al abrir el proyecto.
 *
 * Un servicio de la plataforma no existe hasta que alguien lo pide, y aquí no hay nadie
 * que lo pida: las marcas no se piden, aparecen. Sin este empujón no se verían hasta
 * abrir la tool window o los ajustes —o sea, justo en los proyectos donde uno *no* abre
 * la ventana, que son en los que más falta hacen—.
 *
 * No espera a nada: el servicio se suscribe al modelo y pinta en cuanto haya tareas
 * leídas, así que llegar antes que la carga no es un problema sino el orden normal.
 */
internal class AnchorMarkerActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        AnchorMarkers.getInstance(project)
    }
}
