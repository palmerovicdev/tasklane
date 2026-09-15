package com.tasklane.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.tasklane.TasklaneBundle
import com.tasklane.code.AnchorMarkers
import com.tasklane.data.config.TasklaneConfigService
import com.tasklane.data.config.TasklaneDefaultsService
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.model.AnchorMarkerStyle
import com.tasklane.domain.model.ConfigProblem
import com.tasklane.domain.model.ConfigValidator
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.service.TaskService
import javax.swing.SwingConstants

/**
 * Página de ajustes del proyecto: las dos tablas, el interruptor de triggers y los
 * dos accesos que la arquitectura pide —guardar como plantilla y el enlace al Keymap—.
 *
 * Nada se escribe hasta pulsar Apply. Ni siquiera las reasignaciones de un borrado:
 * el diálogo sólo **anota** el destino, y los comandos salen todos juntos en
 * [apply]. Así cancelar los ajustes deja el proyecto exactamente como estaba, que es
 * lo que cualquiera espera de un botón Cancel.
 *
 * Los atajos no se configuran aquí a propósito: hay un enlace a Settings → Keymap,
 * porque duplicar el sistema de keymap es justo lo que el requisito prohíbe.
 */
class TasklaneConfigurable(private val project: Project) : BoundSearchableConfigurable(
    TasklaneBundle.message("settings.title"),
    HELP_TOPIC,
    ID,
) {

    private val configService = TasklaneConfigService.getInstance(project)

    private val statesTable = StatesTable(::updateProblems, ::confirmStateRemoval)
    private val prioritiesTable = PrioritiesTable(::updateProblems, ::confirmPriorityRemoval)
    private val triggersCheckBox = JBCheckBox(TasklaneBundle.message("settings.triggers.enabled"))

    /**
     * Profundidad de detección de repositorios. Es un filtro de vista, no un borrado:
     * bajarla nunca esconde un repositorio que ya tenga tareas.
     */
    private val depthSpinner = JBIntSpinner(
        TasklaneConfig.DEFAULT_REPO_DEPTH,
        0,
        TasklaneConfig.MAX_REPO_DEPTH,
    )

    /**
     * Lado mayor al que se reescala una imagen pegada. Es un ajuste del proyecto y no
     * de la máquina porque lo que decide es **qué se escribe en disco**, y eso lo
     * comparten todos los que abran el proyecto.
     */
    private val imageSizeSpinner = JBIntSpinner(
        TasklaneConfig.DEFAULT_IMAGE_MAX_SIZE,
        TasklaneConfig.MIN_IMAGE_MAX_SIZE,
        TasklaneConfig.MAX_IMAGE_MAX_SIZE,
        IMAGE_SIZE_STEP,
    )

    /**
     * A partir de cuántos megabytes de imágenes se avisa (§4.5). Cero apaga el aviso, y
     * por eso el mínimo del *spinner* es cero y no el mínimo de la cuota: «no me avises»
     * tiene que poder decirse.
     */
    private val imageQuotaSpinner = JBIntSpinner(
        TasklaneConfig.DEFAULT_IMAGE_QUOTA_MB,
        TasklaneConfig.NO_IMAGE_QUOTA,
        TasklaneConfig.MAX_IMAGE_QUOTA_MB,
        IMAGE_QUOTA_STEP,
    )

    /** Aviso de validación bajo las tablas; la fila exacta la marca la tabla. */
    private val problemLabel = JBLabel("", AllIcons.General.Error, SwingConstants.LEADING).apply {
        isVisible = false
    }

    private val keymapLink = ActionLink(TasklaneBundle.message("settings.keymap.link")) { openKeymap() }

    private val markers = AnchorMarkers.getInstance(project)

    private val markerRenderer =
        SimpleListCellRenderer.create<AnchorMarkerStyle?>("") { it?.label().orEmpty() }

    /**
     * Destinos elegidos en los diálogos de borrado, pendientes de aplicarse.
     * Se iteran en orden de inserción, así que una cadena A→B seguida de B→C acaba
     * dejando en C también lo que venía de A.
     */
    private val stateReassign = LinkedHashMap<StateId, StateId>()
    private val priorityReassign = LinkedHashMap<PriorityId, PriorityId>()

    // ------------------------------------------------------------------ UI

    override fun createPanel(): DialogPanel = panel {
        group(TasklaneBundle.message("settings.states.title")) {
            row { cell(statesTable.component).align(Align.FILL) }.resizableRow()
            row { comment(TasklaneBundle.message("settings.states.comment")) }
        }

        group(TasklaneBundle.message("settings.priorities.title")) {
            row { cell(prioritiesTable.component).align(Align.FILL) }.resizableRow()
            row { cell(triggersCheckBox) }
            row { comment(TasklaneBundle.message("settings.priorities.comment")) }
        }

        group(TasklaneBundle.message("settings.repos.title")) {
            row(TasklaneBundle.message("settings.repos.depth")) { cell(depthSpinner) }
            row { comment(TasklaneBundle.message("settings.repos.comment")) }
        }

        // La marca del editor es del usuario, no del proyecto: se guarda en
        // `workspace.xml` y por eso no pasa por `currentConfig()` sino por su servicio,
        // que la escribe y de paso repinta los editores abiertos. El `bind` del DSL se
        // encarga de Apply, Cancel y de saber si hay algo modificado.
        group(TasklaneBundle.message("settings.anchors.title")) {
            row(TasklaneBundle.message("settings.anchors.marker")) {
                comboBox(AnchorMarkerStyle.entries, markerRenderer)
                    .bindItem({ markers.style }, { markers.style = it ?: AnchorMarkerStyle.GUTTER })
            }
            row { comment(TasklaneBundle.message("settings.anchors.comment")) }
        }

        group(TasklaneBundle.message("settings.images.title")) {
            row(TasklaneBundle.message("settings.images.maxSize")) { cell(imageSizeSpinner) }
            row { comment(TasklaneBundle.message("settings.images.comment")) }
            row(TasklaneBundle.message("settings.images.quota")) { cell(imageQuotaSpinner) }
            row { comment(TasklaneBundle.message("settings.images.quota.comment")) }
        }

        row { cell(problemLabel) }

        row {
            cell(ActionLink(TasklaneBundle.message("settings.template.save")) { saveAsTemplate() })
            cell(keymapLink)
        }
    }

    // -------------------------------------------------------- ciclo de vida

    override fun reset() {
        val config = configService.config.value
        val (states, priorities) = config.toRows()
        statesTable.rows = states
        prioritiesTable.rows = priorities
        triggersCheckBox.isSelected = config.triggersEnabled
        depthSpinner.number = config.repoDepth
        imageSizeSpinner.number = config.imageMaxSize
        imageQuotaSpinner.number = config.imageQuotaMegabytes
        stateReassign.clear()
        priorityReassign.clear()
        updateProblems()
        super.reset()
    }

    override fun isModified(): Boolean =
        super.isModified() ||
            currentConfig() != configService.config.value ||
            stateReassign.isNotEmpty() ||
            priorityReassign.isNotEmpty()

    override fun apply() {
        val config = currentConfig()
        ConfigValidator.validate(config).firstOrNull()?.let {
            throw ConfigurationException(describe(it), TasklaneBundle.message("settings.title"))
        }

        val service = TaskService.getInstance(project)

        // 1. Reasignaciones explícitas ANTES de tocar la configuración: mientras el
        //    estado de origen siga existiendo, mover es un cambio de estado normal.
        stateReassign.forEach { (from, to) -> service.apply(TaskCommand.ReassignState(from, to)) }
        priorityReassign.forEach { (from, to) -> service.apply(TaskCommand.ReassignPriority(from, to)) }
        stateReassign.clear()
        priorityReassign.clear()

        // 2. Se calcula con la configuración vieja todavía en pie; después ya no
        //    habría forma de saber qué estados acaban de volverse terminales.
        val backfill = newlyTerminalWithOpenTasks(config)

        configService.update(config)

        if (backfill.isNotEmpty() && askBackfill(backfill)) {
            service.apply(TaskCommand.BackfillCompletedAt(backfill.map { it.id }.toSet()))
        }

        super.apply()
    }

    private fun currentConfig(): TasklaneConfig =
        buildConfig(
            statesTable.rows,
            prioritiesTable.rows,
            triggersCheckBox.isSelected,
            depthSpinner.number,
            imageSizeSpinner.number,
            imageQuotaSpinner.number,
        ).normalized()

    // ----------------------------------------------------------- validación

    private fun updateProblems() {
        val problems = ConfigValidator.validate(currentConfig())
        statesTable.markProblems(problems.filterIsInstance<ConfigProblem.BlankStateName>().mapNotNull { it.index }.toSet())
        prioritiesTable.markProblems(
            problems.filter { it !is ConfigProblem.BlankStateName }.mapNotNull { it.index }.toSet(),
        )
        problemLabel.text = problems.firstOrNull()?.let(::describe).orEmpty()
        problemLabel.isVisible = problems.isNotEmpty()
    }

    private fun describe(problem: ConfigProblem): String = when (problem) {
        ConfigProblem.NoStates -> TasklaneBundle.message("settings.problem.noStates")
        ConfigProblem.NoPriorities -> TasklaneBundle.message("settings.problem.noPriorities")
        is ConfigProblem.BlankStateName -> TasklaneBundle.message("settings.problem.blankName")
        is ConfigProblem.BlankPriorityName -> TasklaneBundle.message("settings.problem.blankName")
        is ConfigProblem.DuplicateTrigger ->
            TasklaneBundle.message("settings.problem.duplicateTrigger", problem.trigger)
        is ConfigProblem.TriggerWithSpace ->
            TasklaneBundle.message("settings.problem.triggerWithSpace", problem.trigger)
    }

    // -------------------------------------------------------------- borrado

    private fun confirmStateRemoval(row: StateRow): Boolean {
        // Siempre debe quedar al menos uno: sin estados no hay dónde poner una tarea.
        if (statesTable.rows.size <= 1) {
            Messages.showErrorDialog(
                project,
                TasklaneBundle.message("settings.states.lastOne"),
                TasklaneBundle.message("settings.title"),
            )
            return false
        }

        val count = countInState(row.id)
        if (count == 0) return true

        val targets = statesTable.rows.filter { it !== row }
        val dialog = ReassignDialog(
            project = project,
            dialogTitle = TasklaneBundle.message("settings.states.delete.title", row.name),
            question = TasklaneBundle.message("settings.states.delete.question", count),
            options = targets,
            label = StateRow::name,
            confirmText = TasklaneBundle.message("settings.delete"),
        )
        if (!dialog.showAndGet()) return false
        stateReassign[row.id] = dialog.target.id
        return true
    }

    private fun confirmPriorityRemoval(row: PriorityRow): Boolean {
        if (prioritiesTable.rows.size <= 1) {
            Messages.showErrorDialog(
                project,
                TasklaneBundle.message("settings.priorities.lastOne"),
                TasklaneBundle.message("settings.title"),
            )
            return false
        }

        val count = countInPriority(row.id)
        if (count == 0) return true

        val targets = prioritiesTable.rows.filter { it !== row }
        val dialog = ReassignDialog(
            project = project,
            dialogTitle = TasklaneBundle.message("settings.priorities.delete.title", row.name),
            question = TasklaneBundle.message("settings.priorities.delete.question", count),
            options = targets,
            label = PriorityRow::name,
            confirmText = TasklaneBundle.message("settings.delete"),
        )
        if (!dialog.showAndGet()) return false
        priorityReassign[row.id] = dialog.target.id
        return true
    }

    /**
     * Dónde acabará la tarea una vez se apliquen las reasignaciones ya anotadas.
     *
     * Sin esto, borrar A→B y después B daría un recuento que ignora las 42 tareas que
     * A está a punto de traer, y el diálogo estaría mintiendo sobre cuántas mueve.
     * El conjunto `seen` corta cualquier ciclo que se cuele.
     */
    private fun effectiveState(task: Task): StateId = effectiveState(task.stateId)

    private fun effectiveState(from: StateId): StateId {
        var id = from
        val seen = mutableSetOf(id)
        while (true) {
            val next = stateReassign[id] ?: return id
            if (!seen.add(next)) return id
            id = next
        }
    }

    private fun effectivePriority(task: Task): PriorityId = effectivePriority(task.priorityId)

    private fun effectivePriority(from: PriorityId): PriorityId {
        var id = from
        val seen = mutableSetOf(id)
        while (true) {
            val next = priorityReassign[id] ?: return id
            if (!seen.add(next)) return id
            id = next
        }
    }

    /**
     * Cuántas tareas acabarían en [state] con las reasignaciones que el diálogo lleva
     * pendientes. Todos los repositorios: la configuración es del proyecto, no de la
     * pestaña abierta.
     *
     * Sale de los contadores del almacén —cinco filas— y no de recorrer las tareas.
     * Cuenta igual porque lo que se sigue es la **cadena** de reasignaciones, que
     * depende del estado de origen y no de la tarea.
     */
    private fun countInState(state: StateId): Int =
        TaskService.getInstance(project).countsByState()
            .filterKeys { effectiveState(it) == state }
            .values.sum()

    private fun countInPriority(priority: PriorityId): Int =
        TaskService.getInstance(project).countsByPriority()
            .filterKeys { effectivePriority(it) == priority }
            .values.sum()

    // ------------------------------------------------------ estado terminal

    /**
     * Estados que pasan a ser terminales y ya tienen tareas sin `completedAt`. Sin
     * rellenarla esas tareas caerían todas en el mismo grupo de fecha vacío pese a
     * llevar meses cerradas.
     */
    private fun newlyTerminalWithOpenTasks(next: TasklaneConfig): List<TaskState> {
        val before = configService.config.value
        return next.states.filter { state ->
            state.terminal &&
                before.state(state.id)?.terminal == false &&
                TaskService.getInstance(project).openCountOf(state.id) > 0
        }
    }

    private fun askBackfill(states: List<TaskState>): Boolean = Messages.showYesNoDialog(
        project,
        TasklaneBundle.message("settings.backfill.question", states.joinToString { it.name }),
        TasklaneBundle.message("settings.backfill.title"),
        Messages.getQuestionIcon(),
    ) == Messages.YES

    // --------------------------------------------------------------- extras

    private fun saveAsTemplate() {
        val config = currentConfig()
        ConfigValidator.validate(config).firstOrNull()?.let {
            Messages.showErrorDialog(project, describe(it), TasklaneBundle.message("settings.title"))
            return
        }
        TasklaneDefaultsService.getInstance().saveAsTemplate(config)
        Messages.showInfoMessage(
            project,
            TasklaneBundle.message("settings.template.saved"),
            TasklaneBundle.message("settings.title"),
        )
    }

    /**
     * Salta al Keymap **dentro** del mismo diálogo cuando se puede, en vez de abrir
     * una segunda ventana de ajustes encima de esta.
     */
    private fun openKeymap() {
        val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(keymapLink))
        val keymap = settings?.find(KEYMAP_ID)
        if (settings != null && keymap != null) {
            settings.select(keymap)
        } else {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, KEYMAP_ID)
        }
    }

    companion object {
        const val ID = "com.tasklane.settings"
        private const val HELP_TOPIC = "com.tasklane.settings"
        private const val KEYMAP_ID = "preferences.keymap"

        /** Saltos de 100 px: el ajuste es un orden de magnitud, no una medida fina. */
        private const val IMAGE_SIZE_STEP = 100

        /** Un giga por paso: la cuota se piensa en gigas, no en megas. */
        private const val IMAGE_QUOTA_STEP = 1024
    }
}
