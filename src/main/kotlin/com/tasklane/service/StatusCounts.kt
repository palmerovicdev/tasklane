package com.tasklane.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.domain.model.StatusBarChoice
import com.tasklane.domain.model.StatusBarCounts
import com.tasklane.domain.model.TasklaneSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toKotlinDuration

/**
 * Lo que cuenta el widget de la barra de estado (2.14.0): «ToDo 3 · Doing 1 · 2 overdue»
 * del repositorio activo.
 *
 * **Sólo el repositorio activo**, como todo en Tasklane: la barra habla de la lista que se
 * tiene delante al abrir la ventana. Y cuenta **como las pestañas con el filtro *All
 * tasks***: el archivo de lo terminado sí se aplica —es qué se da por visto en un estado
 * terminal, y *Done 4.213* sobre una pestaña de doce no cuadraría con nada—, pero el filtro
 * de vista no, porque es una forma pasajera de mirar la lista y la barra está para no
 * tener que mirarla.
 *
 * **Sólo trabaja mientras alguien escucha.** [counts] se comparte con `WhileSubscribed`: con
 * el widget escondido desde el menú de la barra no se cuenta nada.
 *
 * **Cuándo se cuenta.** Con cada escritura —el snapshot sube de revisión—, al cambiar de
 * repositorio, de configuración o de elección, y **cuando vence algo**: una tarea vence sin
 * que nadie escriba nada, así que después de cada cuenta se espera justo hasta la
 * siguiente que va a vencer, y a medianoche si el archivo va a esconder algo más. Con
 * un techo de [MAX_WAIT], porque la espera no avanza con el equipo dormido.
 */
@Service(Service.Level.PROJECT)
class StatusCounts(project: Project, scope: CoroutineScope) {

    private val tasks = TaskService.getInstance(project)
    private val view = ViewService.getInstance(project)
    private val workspace = TasklaneWorkspaceService.getInstance(project)

    private val _choice = MutableStateFlow(workspace.statusBar)
    val choice: StateFlow<StatusBarChoice> = _choice.asStateFlow()

    /** Lo pide la página de ajustes al aplicar. */
    fun setChoice(value: StatusBarChoice) {
        workspace.statusBar = value
        _choice.value = value
    }

    private data class Input(
        val snapshot: TasklaneSnapshot,
        val choice: StatusBarChoice,
        val archive: ViewService.Archive,
    )

    private class Read(val counts: StatusBarCounts, val wake: Duration)

    /** `null` mientras no hay catálogo de repositorios: antes de eso no hay de quién contar. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val counts: StateFlow<StatusBarCounts?> =
        combine(tasks.snapshot.filter { it.repositories.isNotEmpty() }, _choice, view.archive, ::Input)
            // Una importación son cientos de escrituras seguidas; la barra no tiene que
            // enseñarlas una a una.
            .debounce(COALESCE)
            .transformLatest { input ->
                while (true) {
                    val read = try {
                        read(input, Instant.now())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Sin esto una base que se cierra a destiempo mataría el flujo, y el
                        // widget se quedaría con la última cifra para siempre.
                        LOG.warn("Tasklane: no se pudo contar para la barra de estado", e)
                        null
                    }
                    if (read != null) emit(read.counts)
                    delay((read?.wake ?: MAX_WAIT).toKotlinDuration())
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    private fun read(input: Input, now: Instant): Read {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val snapshot = input.snapshot
        val repo = snapshot.activeRepo
        val states = input.choice.statesIn(snapshot.config)
        val counted = if (states.isEmpty()) emptyMap() else tasks.stateCounts(repo, now, input.archive.cutoff(today, zone))
        val due = if (input.choice.overdue) tasks.dueCount(repo, now) else null

        // El corte del archivo es por días: a medianoche, lo cerrado hace N días deja de contar.
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
            .takeIf { input.archive.active && states.any { it.terminal } }

        return Read(
            StatusBarCounts(
                repo = snapshot.activeRepository?.displayName ?: repo.value,
                states = states.map { StatusBarCounts.StateCount(it, counted[it.id] ?: 0) },
                overdue = due?.overdue,
            ),
            waitUntil(now, due?.next, midnight),
        )
    }

    companion object {
        private val LOG = logger<StatusCounts>()

        private val COALESCE = 200.milliseconds

        /** Lo mínimo que se espera entre dos cuentas, para no girar en vacío con un reloj que salta. */
        internal val MIN_WAIT: Duration = Duration.ofSeconds(1)

        /**
         * Lo máximo. La espera de una corrutina no avanza con el equipo dormido: sin techo,
         * una tarea que venció durante la noche no se vería hasta mucho después de abrir la
         * tapa.
         */
        internal val MAX_WAIT: Duration = Duration.ofMinutes(5)

        /**
         * Cuánto esperar hasta la próxima cuenta: hasta el primero de [moments] que llegue
         * —justo después, porque una tarea vence cuando su fecha **ya pasó**—, entre
         * [MIN_WAIT] y [MAX_WAIT].
         */
        internal fun waitUntil(now: Instant, vararg moments: Instant?): Duration {
            val next = moments.filterNotNull().minOrNull() ?: return MAX_WAIT
            val wait = Duration.between(now, next).plusMillis(1)
            return when {
                wait < MIN_WAIT -> MIN_WAIT
                wait > MAX_WAIT -> MAX_WAIT
                else -> wait
            }
        }

        fun getInstance(project: Project): StatusCounts = project.service()
    }
}
