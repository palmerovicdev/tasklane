package com.tasklane.service

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.tasklane.TasklaneBundle
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * El aviso de vencimientos (2.9.0): «2 overdue · 1 due today in backend».
 *
 * Hasta aquí `dueDate` sólo pintaba la tarjeta en rojo y alimentaba el filtro *Overdue*:
 * había que mirar para enterarse, y lo que vence es justo lo que no se está mirando.
 *
 * **Cuándo se avisa.** Al abrir el proyecto, al cambiar de repositorio y cada
 * [PERIOD] —una tarea puesta para hoy que vence mientras se programa—. Pero **sólo de lo
 * que no se ha avisado ya hoy**: el aviso repite la cuenta entera, así que en cuanto sale
 * uno nuevo el anterior sobra y se retira. Pasada la medianoche todo vuelve a contar, que
 * es lo que un recordatorio diario tiene que hacer.
 *
 * **Sólo el repositorio activo**, como todo en Tasklane: un aviso con las tareas de otro
 * hablaría de una lista que no es la que se tiene delante.
 *
 * Se apaga en *Settings → Tools → Tasklane* y es de la persona, no del proyecto: va a
 * `workspace.xml` como la marca del editor.
 */
@Service(Service.Level.PROJECT)
class DueReminders(private val project: Project, private val scope: CoroutineScope) {

    private val tasks = TaskService.getInstance(project)
    private val workspace = TasklaneWorkspaceService.getInstance(project)

    /** Lo avisado hoy, por repositorio. Se vacía al cambiar de día. */
    private val told = HashMap<RepoKey, MutableSet<TaskId>>()
    private var toldOn: LocalDate? = null
    private var shown: Notification? = null

    @Volatile
    private var started = false

    /** Arranca los avisos. Lo llama la actividad de inicio, una vez. */
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            // El repositorio activo cuando ya hay catálogo: antes de eso el activo es la
            // selección restaurada, que puede no existir, y avisar de ella sería avisar de nada.
            tasks.snapshot
                .filter { it.repositories.isNotEmpty() }
                .map { it.activeRepo }
                .distinctUntilChanged()
                .collect { check() }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(PERIOD)
                check()
            }
        }
    }

    /** Mira el repositorio activo y avisa si hay algo nuevo que decir. */
    @Synchronized
    internal fun check(clock: Clock = Clock.systemDefaultZone()) {
        if (!workspace.dueReminders || project.isDisposed) return
        val zone = clock.zone
        val now = Instant.now(clock)
        val today = LocalDate.now(clock)
        if (toldOn != today) {
            told.clear()
            toldOn = today
        }

        val repo = tasks.snapshot.value.activeRepo
        val due = tasks.dueBy(repo, endOfDay(today, zone))
        val summary = DueSummary.of(due, now)
        if (summary.isEmpty) return

        val seen = told.getOrPut(repo) { HashSet() }
        if (!seen.addAll(due.map { it.id })) return

        notify(repo, summary, due)
    }

    private fun notify(repo: RepoKey, summary: DueSummary, due: List<Task>) {
        val name = tasks.snapshot.value.repositories.firstOrNull { it.key == repo }?.displayName ?: repo.value
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(TaskService.NOTIFICATION_GROUP)
            .createNotification(
                TasklaneBundle.message("due.title"),
                summary.describe(name),
                if (summary.overdue > 0) NotificationType.WARNING else NotificationType.INFORMATION,
            )
            .addAction(
                NotificationAction.createSimpleExpiring(TasklaneBundle.message("due.show")) {
                    tasks.revealTasks(due)
                },
            )
            .addAction(
                NotificationAction.createSimpleExpiring(TasklaneBundle.message("due.mute")) {
                    workspace.dueReminders = false
                },
            )
        shown?.expire()
        shown = notification
        notification.notify(project)
    }

    private fun endOfDay(day: LocalDate, zone: ZoneId): Instant = day.plusDays(1).atStartOfDay(zone).toInstant()

    companion object {
        /** Cada cuánto se vuelve a mirar. Lo que vence hoy vence al acabar el día, no a una hora. */
        private val PERIOD = 30.minutes

        fun getInstance(project: Project): DueReminders = project.service()
    }
}

/**
 * Cuántas tareas están vencidas y cuántas vencen hoy. Aparte del servicio para poder
 * probar el texto y la cuenta sin un proyecto.
 */
internal data class DueSummary(val overdue: Int, val today: Int) {

    val isEmpty: Boolean get() = overdue == 0 && today == 0

    fun describe(repo: String): String = when {
        overdue > 0 && today > 0 -> TasklaneBundle.message("due.both", overdue, today, repo)
        overdue > 0 -> TasklaneBundle.message("due.overdue", overdue, repo)
        else -> TasklaneBundle.message("due.today", today, repo)
    }

    companion object {
        /**
         * [due] son las abiertas que vencen antes de que acabe hoy. Las que ya pasaron de
         * [now] están vencidas; las demás, para hoy.
         */
        fun of(due: List<Task>, now: Instant): DueSummary {
            val overdue = due.count { it.isOverdue(now) }
            return DueSummary(overdue, due.count { it.completedAt == null } - overdue)
        }
    }
}
