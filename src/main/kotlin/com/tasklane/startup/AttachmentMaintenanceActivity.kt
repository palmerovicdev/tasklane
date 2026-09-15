package com.tasklane.startup

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.tasklane.TasklaneBundle
import com.tasklane.service.AttachmentService
import com.tasklane.service.TaskService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * El mantenimiento de los adjuntos, una vez por apertura del proyecto: trasladar,
 * recolectar, reconciliar y mirar la cuota.
 *
 * Es lo que en la 2.0 era `AttachmentGcActivity`, con tres tareas más y **en el orden en
 * que tienen que ocurrir**:
 *
 * 1. **Trasladar** lo que quede en el directorio plano al árbol fragmentado (§4.1). Va
 *    primero porque es lo único que puede tardar de forma proporcional a lo que había
 *    antes, y porque no depende de nada: mover ficheros no necesita saber qué está
 *    referenciado.
 * 2. **Recolectar** lo que ya no nombra ninguna tarea (§4.2), pero **sólo en los
 *    repositorios cuyas referencias están completas** — ver `TaskService.referencesComplete`.
 *    Ésta es la salvaguarda que sostiene todo lo demás: recolectar mientras se importa un
 *    `tasks.xml` no encontraría basura, encontraría todas las imágenes del usuario.
 * 3. **Reconciliar** disco y tabla (§4.3), como mucho una vez por semana. Después de
 *    recolectar para no adoptar lo que se acaba de tirar.
 * 4. **Mirar la cuota** (§4.5), al final: es la única de las cuatro que le dice algo al
 *    usuario, y tiene que decírselo con las cifras ya puestas al día.
 *
 * **Todo en segundo plano, con progreso y cancelable.** Con diez millones de blobs el
 * traslado y la reconciliación son minutos de disco, y nada de esto puede impedir que
 * alguien cierre el IDE — ni hacerle esperar para empezar a trabajar: la ventana ya
 * responde mientras esto ocurre, porque ninguna de las cuatro toca la lista.
 */
internal class AttachmentMaintenanceActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val tasks = TaskService.getInstance(project)

        // Esperar a que haya repositorios detectados —no a que estén cargados, que
        // desde la Fase 3 ya no es una cosa que ocurra—. Sin repositorios no hay nada
        // que mantener, y lo que decide si se puede recolectar cada uno es su propia
        // comprobación de referencias, no esta espera.
        val ready = withTimeoutOrNull(WAIT) {
            tasks.snapshot.first { it.repositories.isNotEmpty() }
        }
        if (ready == null) return

        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, TasklaneBundle.message("attachments.progress"), true) {
                override fun run(indicator: ProgressIndicator) {
                    val service = AttachmentService.getInstance(project)
                    val cancelled = { indicator.isCanceled }
                    indicator.isIndeterminate = true

                    // Cada tarea por separado y sin que una se lleve a las demás: esto
                    // es mantenimiento en segundo plano sobre el disco de alguien, y un
                    // volumen de red que desaparece a media pasada no puede convertirse
                    // en un error del IDE ni impedir que se avise de la cuota.
                    indicator.text = TasklaneBundle.message("attachments.relocating")
                    val moved = step("trasladar") { service.relocate(cancelled) } ?: 0

                    indicator.text = TasklaneBundle.message("attachments.collecting")
                    val deleted = step("recoger") { service.collectGarbage(cancelled) } ?: 0

                    indicator.text = TasklaneBundle.message("attachments.reconciling")
                    val fsck = step("reconciliar") { service.reconcile(cancelled = cancelled) }

                    if (moved > 0 || deleted > 0 || (fsck != null && (fsck.adopted > 0 || fsck.missing > 0))) {
                        thisLogger().info(
                            "Tasklane: adjuntos · $moved trasladados, $deleted recogidos, " +
                                "${fsck?.adopted ?: 0} adoptados, ${fsck?.missing ?: 0} ausentes, " +
                                "${fsck?.temporaries ?: 0} temporales",
                        )
                    }

                    if (!indicator.isCanceled) step("cuota") { service.checkQuota() }
                }

                private fun <T> step(name: String, block: () -> T): T? = runCatching(block)
                    .onFailure { thisLogger().warn("Tasklane: fallo al $name los adjuntos", it) }
                    .getOrNull()
            },
        )
    }

    private companion object {
        val WAIT = 60.seconds
    }
}
