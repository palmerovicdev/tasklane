package com.tasklane.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.tasklane.service.AttachmentService
import com.tasklane.service.TaskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Tira los adjuntos que ya no referencia ninguna tarea, una vez por apertura del
 * proyecto.
 *
 * **Espera a que las tareas estén leídas, y si no lo están no borra nada.** Es la
 * garantía que sostiene todo lo demás: las referencias se sacan del snapshot, y un
 * repositorio sin leer no tiene ninguna. Recolectar antes de tiempo no encontraría
 * basura, encontraría todas las imágenes del usuario.
 *
 * Al abrir y no periódicamente porque es cuando el coste no se nota y cuando hay algo
 * que recoger: lo que dejó huérfano la sesión anterior. Durante la sesión, un blob de
 * más no molesta a nadie.
 */
internal class AttachmentGcActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val tasks = TaskService.getInstance(project)

        val ready = withTimeoutOrNull(WAIT) {
            tasks.snapshot.first { it.repositories.isNotEmpty() && it.loading.isEmpty() }
        }
        // Sin carga completa no se sabe qué está referenciado, así que no se toca
        // nada. Ya habrá otra apertura.
        if (ready == null) return

        withContext(Dispatchers.IO) { AttachmentService.getInstance(project).collectGarbage() }
    }

    private companion object {
        val WAIT = 60.seconds
    }
}
