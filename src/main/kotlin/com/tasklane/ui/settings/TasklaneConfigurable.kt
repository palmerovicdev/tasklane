package com.tasklane.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.tasklane.TasklaneBundle
import com.tasklane.code.AnchorMarkers
import com.tasklane.data.config.TasklaneConfigService
import com.tasklane.data.config.TasklaneDefaultsService
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.diagnostics.BlobStats
import com.tasklane.diagnostics.TasklaneDiagnostics
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.model.AnchorMarkerStyle
import com.tasklane.domain.model.ConfigProblem
import com.tasklane.domain.model.ConfigValidator
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.StatusBarChoice
import com.tasklane.domain.model.TagColor
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.service.AttachmentService
import com.tasklane.service.StatusCounts
import com.tasklane.service.TaskService
import com.tasklane.service.ViewService
import javax.swing.JList
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

    private val statusCounts = StatusCounts.getInstance(project)

    /**
     * Qué cuenta la barra de estado (2.14.0): lo vencido, aquí; los estados, en la columna
     * *Status bar* de la tabla de estados (2.17.1; antes eran una fila de casillas aparte
     * que repetía los nombres).
     */
    private val statusOverdueCheckBox = JBCheckBox(TasklaneBundle.message("settings.statusBar.overdue"))

    /**
     * Los estados marcados, o `null` mientras nadie toque una casilla: entonces se marcan
     * los no terminales de la tabla tal como esté, y lo que se guarda sigue siendo «lo de
     * fábrica». Ver `StatusBarChoice`.
     */
    private var statusStates: MutableSet<StateId>? = null

    /** Los estados sin pestaña en la tool window (2.17.1), en la columna *Tool window* de la tabla. */
    private var windowHidden: MutableSet<StateId> = mutableSetOf()

    /** Los estados sin columna en el tablero (2.17.1), en la columna *Board* de la tabla. */
    private var boardHidden: MutableSet<StateId> = mutableSetOf()

    /**
     * Las tres columnas de la tabla que son de la persona y van a `workspace.xml`, no a
     * `tasklane.xml`. Ver [PersonalColumn].
     */
    private val personalColumns: List<PersonalColumn> = listOf(
        PersonalColumn(
            TasklaneBundle.message("settings.column.window"),
            TasklaneBundle.message("settings.column.window.tooltip"),
            WINDOW_WIDTH,
            get = { it.id !in windowHidden },
            set = { row, shown -> if (shown) windowHidden -= row.id else windowHidden += row.id },
        ),
        PersonalColumn(
            TasklaneBundle.message("settings.column.board"),
            TasklaneBundle.message("settings.column.board.tooltip"),
            BOARD_WIDTH,
            get = { it.id !in boardHidden },
            set = { row, shown -> if (shown) boardHidden -= row.id else boardHidden += row.id },
        ),
        PersonalColumn(
            TasklaneBundle.message("settings.column.statusBar"),
            TasklaneBundle.message("settings.column.statusBar.tooltip"),
            STATUS_BAR_WIDTH,
            get = { row -> statusStates?.contains(row.id) ?: !row.terminal },
            set = { row, counted ->
                // La primera casilla que se toca fija la elección entera: desde ahí ya no
                // es «lo de fábrica».
                val chosen = statusStates ?: statesTable.rows.filter { !it.terminal }.mapTo(mutableSetOf()) { it.id }
                if (counted) chosen += row.id else chosen -= row.id
                statusStates = chosen
            },
        ),
    )

    private val statesTable: StatesTable = StatesTable(::onStatesChanged, ::confirmStateRemoval, personalColumns)
    private val prioritiesTable = PrioritiesTable(project, ::updateProblems, ::confirmPriorityRemoval)
    private val triggersCheckBox = JBCheckBox(TasklaneBundle.message("settings.triggers.enabled"))

    /** Las etiquetas del repositorio activo (P30). Se leen fuera del EDT al abrir: ver [loadTags]. */
    private val tagsTable = TagsTable(project, ::updateProblems, ::confirmTagRemoval)

    /**
     * Las etiquetas borradas, de su nombre de hoy al de la fila adonde van sus tareas, o a
     * `null` para sólo quitarlas; y cuántas tareas llevaba cada una. Como las
     * reasignaciones de estado: se anotan y salen al aplicar. Ver [TagEdits.renames].
     */
    private val tagRemovals = LinkedHashMap<String, String?>()
    private val removedTagTasks = HashMap<String, Int>()

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
     * Lo que pesan las imágenes **del repositorio activo**, siempre a la vista (2.3).
     *
     * Se calcula fuera del EDT al abrir la página y después de cada limpieza: sale de la
     * tabla `blob`, que en un repositorio con millones de capturas no es un salto de
     * índice, y los ajustes no pueden abrirse congelados por una cifra.
     */
    private val imageWeightLabel = JBLabel()

    /** La última cifra que se leyó, para que la confirmación de «borrar todas» diga cuántas son. */
    @Volatile
    private var imageStats: BlobStats? = null

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

    private val workspace = TasklaneWorkspaceService.getInstance(project)

    private val view = ViewService.getInstance(project)

    /**
     * El archivo de lo terminado (2.10.0): la casilla dice si se esconde algo y el número
     * desde cuándo. Sin marcar se ve todo, que es lo de fábrica. Van a mano y no con el
     * `bind` del DSL porque los dos controles son **un** valor guardado —`0` es «todo»— y
     * el número tiene que sobrevivir a desmarcar y volver a marcar.
     */
    private val archiveCheckBox = JBCheckBox(TasklaneBundle.message("settings.archive.enabled")).apply {
        addActionListener { archiveSpinner.isEnabled = isSelected }
    }
    private val archiveSpinner = JBIntSpinner(DEFAULT_ARCHIVE_DAYS, 1, MAX_ARCHIVE_DAYS).apply {
        isEnabled = false
    }

    // Subclase y no SimpleListCellRenderer.create, que la 2026.2 marca para eliminarse.
    private val markerRenderer = object : SimpleListCellRenderer<AnchorMarkerStyle?>() {
        override fun customize(
            list: JList<out AnchorMarkerStyle?>,
            value: AnchorMarkerStyle?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            text = value?.label().orEmpty()
        }
    }

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

        // Del repositorio activo, como todo lo que toca tareas: renombrar, fusionar y borrar
        // cambian sólo las suyas. Los colores, en cambio, son del proyecto, como los de las
        // prioridades, y el comentario lo dice.
        group(TasklaneBundle.message("settings.tags.title", activeRepoName().second)) {
            row { cell(tagsTable.component).align(Align.FILL) }.resizableRow()
            row { comment(TasklaneBundle.message("settings.tags.comment")) }
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

        // Como la marca del editor, el aviso es de la persona: `workspace.xml`.
        group(TasklaneBundle.message("settings.due.title")) {
            row {
                checkBox(TasklaneBundle.message("settings.due.remind"))
                    .bindSelected({ workspace.dueReminders }, { workspace.dueReminders = it })
            }
            row { comment(TasklaneBundle.message("settings.due.comment")) }
        }

        // Tuyo también, en `workspace.xml`. Encender o apagar el widget no está aquí: es el
        // menú de la barra, que es donde el IDE lo hace con todos.
        group(TasklaneBundle.message("settings.statusBar.title")) {
            row { cell(statusOverdueCheckBox) }
            row { comment(TasklaneBundle.message("settings.statusBar.comment")) }
        }

        group(TasklaneBundle.message("settings.archive.title")) {
            row {
                cell(archiveCheckBox)
                cell(archiveSpinner)
                label(TasklaneBundle.message("settings.archive.days"))
            }
            row { comment(TasklaneBundle.message("settings.archive.comment")) }
        }

        // Todo lo de este grupo es del repositorio activo, como el resto de Tasklane: el
        // peso que se enseña, los dos botones y el umbral del aviso.
        group(TasklaneBundle.message("settings.images.title")) {
            row(TasklaneBundle.message("settings.images.weight")) { cell(imageWeightLabel) }
            row {
                button(TasklaneBundle.message("settings.images.purgeUnused")) { purgeUnusedImages() }
                button(TasklaneBundle.message("settings.images.purgeAll")) { purgeAllImages() }
            }
            row { comment(TasklaneBundle.message("settings.images.comment")) }
            row(TasklaneBundle.message("settings.images.quota")) { cell(imageQuotaSpinner) }
            row { comment(TasklaneBundle.message("settings.images.quota.comment")) }
        }

        // Encargar una tarea a un agente (P26). De la aplicación, no del proyecto: ver AgentGroup.
        agentGroup(project)

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
        refreshImageWeight()
        imageQuotaSpinner.number = config.imageQuotaMegabytes
        val days = view.archive.value.days
        archiveCheckBox.isSelected = days > 0
        archiveSpinner.number = if (days > 0) days else DEFAULT_ARCHIVE_DAYS
        archiveSpinner.isEnabled = days > 0
        val choice = statusCounts.choice.value
        statusStates = choice.states?.toMutableSet()
        statusOverdueCheckBox.isSelected = choice.overdue
        windowHidden = view.windowHidden.value.toMutableSet()
        boardHidden = view.boardHidden.value.toMutableSet()
        statesTable.refresh()
        stateReassign.clear()
        priorityReassign.clear()
        tagRemovals.clear()
        removedTagTasks.clear()
        loadTags()
        updateProblems()
        super.reset()
    }

    override fun isModified(): Boolean =
        super.isModified() ||
            currentConfig() != configService.config.value ||
            stateReassign.isNotEmpty() ||
            priorityReassign.isNotEmpty() ||
            tagRemovals.isNotEmpty() ||
            tagsTable.rows.any { TagEdits.clean(it.name) != it.original } ||
            archiveDays() != view.archive.value.days ||
            statusChoice() != statusCounts.choice.value ||
            windowHidden != view.windowHidden.value ||
            boardHidden != view.boardHidden.value

    override fun apply() {
        var config = currentConfig()
        ConfigValidator.validate(config).firstOrNull()?.let {
            throw ConfigurationException(describe(it), TasklaneBundle.message("settings.title"))
        }
        tagProblem()?.let { throw ConfigurationException(it, TasklaneBundle.message("settings.title")) }

        // 0. Las etiquetas, lo primero: es lo único que se puede cancelar a medias, y
        //    cancelar tiene que dejar el proyecto entero como estaba.
        config = config.copy(tagColors = applyTags())

        val service = TaskService.getInstance(project)
        if (archiveDays() != view.archive.value.days) view.setArchiveDays(archiveDays())
        if (statusChoice() != statusCounts.choice.value) statusCounts.setChoice(statusChoice())
        if (windowHidden != view.windowHidden.value) view.setWindowHidden(windowHidden.toSet())
        if (boardHidden != view.boardHidden.value) view.setBoardHidden(boardHidden.toSet())

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

    private fun archiveDays(): Int = if (archiveCheckBox.isSelected) archiveSpinner.number else 0

    // ------------------------------------------------------- barra de estado

    /**
     * Cualquier cambio en la tabla. Las casillas personales se repintan porque una puede
     * depender de otra columna: lo que cuenta la barra de fábrica son los no terminales.
     */
    private fun onStatesChanged() {
        updateProblems()
        statesTable.refresh()
    }

    /**
     * Lo elegido, tal cual. Un estado borrado puede quedarse en la elección sin hacer daño
     * —`StatusBarChoice.statesIn` sólo cuenta los que existen—, y quitarlo aquí haría que la
     * página se abriera ya «modificada» cuando lo guardado nombra uno que ya no está.
     */
    private fun statusChoice(): StatusBarChoice =
        StatusBarChoice(statusStates?.toSet(), statusOverdueCheckBox.isSelected)

    private fun currentConfig(): TasklaneConfig =
        buildConfig(
            statesTable.rows,
            prioritiesTable.rows,
            triggersCheckBox.isSelected,
            depthSpinner.number,
            imageQuotaSpinner.number,
            // Sin quitar el color a las que se renombran: saber si siguen en otro
            // repositorio es una consulta, y esto se pregunta a cada momento. Lo hace
            // [applyTags].
            TagEdits.colors(configService.config.value.tagColors, tagsTable.rows, pendingTagRenames(), keep = null),
        ).normalized()

    // ----------------------------------------------------------- validación

    private fun updateProblems() {
        val problems = ConfigValidator.validate(currentConfig())
        statesTable.markProblems(problems.filterIsInstance<ConfigProblem.BlankStateName>().mapNotNull { it.index }.toSet())
        prioritiesTable.markProblems(
            problems.filter { it !is ConfigProblem.BlankStateName }.mapNotNull { it.index }.toSet(),
        )
        val tagRows = tagsTable.rows
        tagsTable.markProblems(tagRows.indices.filterTo(HashSet()) { TagEdits.problemOf(tagRows[it]) != null })
        val message = problems.firstOrNull()?.let(::describe) ?: tagProblem()
        problemLabel.text = message.orEmpty()
        problemLabel.isVisible = message != null
    }

    /** La primera fila de etiquetas que no se puede aplicar, dicha. */
    private fun tagProblem(): String? = tagsTable.rows.firstNotNullOfOrNull { row ->
        when (TagEdits.problemOf(row)) {
            null -> null
            TagEdits.Problem.BLANK -> TasklaneBundle.message("settings.tags.problem.blank")
            TagEdits.Problem.SEPARATOR -> TasklaneBundle.message("settings.tags.problem.separator", row.name.trim())
        }
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

    // ----------------------------------------------------- etiquetas (P30)

    /** Adónde van las tareas de una etiqueta borrada: otra fila, o `null` para sólo quitarla. */
    private class TagTarget(val row: TagRow?)

    /**
     * Borrar una etiqueta pregunta qué hacer con sus tareas, como un estado. A diferencia
     * de un estado, aquí **sí** hay un «sólo quítala» —una tarea sin etiquetas es una
     * tarea—, y es lo que se ofrece primero; la otra salida es darles otra, que es fusionar.
     */
    private fun confirmTagRemoval(row: TagRow): Boolean {
        val count = row.tasks + tagRemovals.keys.filter { tagDestination(it) == row.original }
            .sumOf { removedTagTasks[it] ?: 0 }
        val options = listOf(TagTarget(null)) + tagsTable.rows.filter { it !== row }.map(::TagTarget)
        val dialog = ReassignDialog(
            project = project,
            dialogTitle = TasklaneBundle.message("settings.tags.delete.title", row.name),
            question = TasklaneBundle.message("settings.tags.delete.question", count),
            options = options,
            label = { target ->
                target.row?.let { TasklaneBundle.message("settings.tags.delete.into", it.name) }
                    ?: TasklaneBundle.message("settings.tags.delete.remove")
            },
            confirmText = TasklaneBundle.message("settings.delete"),
        )
        if (!dialog.showAndGet()) return false
        tagRemovals[row.original] = dialog.target.row?.original
        removedTagTasks[row.original] = count
        return true
    }

    /** La fila en la que acaba una etiqueta borrada, siguiendo la cadena. `null`: se quita. */
    private fun tagDestination(original: String): String? {
        var at = original
        val seen = mutableSetOf(at)
        while (at in tagRemovals) {
            at = tagRemovals[at] ?: return null
            if (!seen.add(at)) return null
        }
        return at
    }

    private fun pendingTagRenames(): Map<String, String?> = TagEdits.renames(tagsTable.rows, tagRemovals)

    /**
     * Las etiquetas del repositorio activo, fuera del EDT: salen de agrupar la tabla
     * `tag`, que no es un salto de índice, y los ajustes no pueden abrirse congelados por
     * ella. Es lo que hace el peso de las imágenes.
     */
    private fun loadTags() {
        val (repo, _) = activeRepoName()
        ApplicationManager.getApplication().executeOnPooledThread {
            val counts = TaskService.getInstance(project).tagCounts(repo)
            ApplicationManager.getApplication().invokeLater(
                {
                    val config = configService.config.value
                    tagsTable.rows = counts.map { TagRow.of(it, config.tagColor(it.tag)) }
                    updateProblems()
                },
                ModalityState.any(),
            )
        }
    }

    /**
     * Renombra, fusiona y quita lo que diga la tabla en las tareas del repositorio activo,
     * con barra modal y cancelable, como la limpieza de imágenes. Devuelve los colores
     * como quedan.
     *
     * Cancelar no deja nada a medias —es una transacción— y para aquí el *Apply* entero,
     * que es lo que dice el aviso.
     */
    private fun applyTags(): Map<String, TagColor> {
        val rows = tagsTable.rows
        val renames = pendingTagRenames()
        val base = configService.config.value.tagColors
        if (renames.isEmpty()) return TagEdits.colors(base, rows, renames, keep = null)

        val (repo, name) = activeRepoName()
        val service = TaskService.getInstance(project)
        if (service.isReadOnly(repo)) {
            throw ConfigurationException(
                TasklaneBundle.message("settings.tags.readOnly", name),
                TasklaneBundle.message("settings.title"),
            )
        }
        var done = false
        ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Modal(
                project,
                TasklaneBundle.message("settings.tags.progress", name),
                true,
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    indicator.isIndeterminate = false
                    service.retag(repo, renames) { at, total ->
                        indicator.checkCanceled()
                        indicator.fraction = at.toDouble() / total.coerceAtLeast(1)
                    }
                    done = true
                }
            },
        )
        if (!done) {
            throw ConfigurationException(
                TasklaneBundle.message("settings.tags.cancelled", name),
                TasklaneBundle.message("settings.title"),
            )
        }

        val colors = TagEdits.colors(base, rows, renames, keep = service.tagsOutside(repo))
        // Ya aplicado, la tabla es la de ahora: una fila por etiqueta, con su cuenta nueva.
        tagRemovals.clear()
        removedTagTasks.clear()
        tagsTable.rows = service.tagCounts(repo).map { TagRow.of(it, colors[TagColor.key(it.tag)]) }
        return colors
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

    // ----------------------------------------------------- imágenes (2.3)

    private fun activeRepoName(): Pair<RepoKey, String> {
        val snapshot = TaskService.getInstance(project).snapshot.value
        return snapshot.activeRepo to (snapshot.activeRepository?.displayName ?: snapshot.activeRepo.value)
    }

    private fun refreshImageWeight() {
        val (repo, name) = activeRepoName()
        imageWeightLabel.text = TasklaneBundle.message("settings.images.weight.loading", name)
        ApplicationManager.getApplication().executeOnPooledThread {
            val stats = TaskService.getInstance(project).blobStatsOf(repo)
            imageStats = stats
            ApplicationManager.getApplication().invokeLater(
                { imageWeightLabel.text = describeWeight(name, stats) },
                // Los ajustes son modales: sin esto la cifra esperaría a que se cerraran.
                ModalityState.any(),
            )
        }
    }

    private fun describeWeight(name: String, stats: BlobStats): String {
        val weight = TasklaneDiagnostics.humanBytes(stats.bytes)
        return if (stats.missing > 0) {
            TasklaneBundle.message("settings.images.weight.missing", name, weight, stats.present, stats.missing)
        } else {
            TasklaneBundle.message("settings.images.weight.value", name, weight, stats.present)
        }
    }

    /** Borra ya las imágenes que no usa ninguna tarea del repositorio activo. Ver `AttachmentService.purgeUnused`. */
    private fun purgeUnusedImages() {
        val (repo, name) = activeRepoName()
        runCleanup(TasklaneBundle.message("settings.images.purgeUnused.progress", name), name) { indicator ->
            AttachmentService.getInstance(project).purgeUnused(repo) { indicator.isCanceled }
        }
    }

    /**
     * Borra todas las imágenes del repositorio activo, **tras confirmarlo** con cuántas son
     * y qué les pasa a las tareas que las usan. Ver `AttachmentService.purgeAll`.
     */
    private fun purgeAllImages() {
        val (repo, name) = activeRepoName()
        val stats = imageStats
        val question = if (stats != null) {
            TasklaneBundle.message(
                "settings.images.purgeAll.question",
                name,
                stats.present,
                TasklaneDiagnostics.humanBytes(stats.bytes),
            )
        } else {
            TasklaneBundle.message("settings.images.purgeAll.questionUncounted", name)
        }
        val confirmed = MessageDialogBuilder.yesNo(TasklaneBundle.message("settings.images.purgeAll.title", name), question)
            .yesText(TasklaneBundle.message("settings.images.purgeAll.confirm"))
            .noText(Messages.getCancelButton())
            .asWarning()
            .ask(imageWeightLabel)
        if (!confirmed) return

        runCleanup(TasklaneBundle.message("settings.images.purgeAll.progress", name), name) { indicator ->
            indicator.isIndeterminate = true
            AttachmentService.getInstance(project).purgeAll(repo, { indicator.isCanceled }) { files ->
                indicator.text2 = TasklaneBundle.message("settings.images.purgeAll.files", files)
            }
        }
    }

    /**
     * Una limpieza con barra modal y cancelable, y lo que salió dicho al terminar. Modal
     * y no en segundo plano porque se lanza desde un diálogo que ya es modal: el usuario
     * está mirando justo esto y la cifra de arriba tiene que cambiar delante de él.
     */
    private fun runCleanup(
        title: String,
        name: String,
        block: (com.intellij.openapi.progress.ProgressIndicator) -> AttachmentService.Cleanup,
    ) {
        var result: AttachmentService.Cleanup? = null
        ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Modal(project, title, true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    result = block(indicator)
                }

                override fun onFinished() {
                    refreshImageWeight()
                    val message = when (val done = result) {
                        null -> return
                        AttachmentService.Cleanup.Refused -> TasklaneBundle.message("settings.images.refused", name)
                        is AttachmentService.Cleanup.Done -> when {
                            !done.complete -> TasklaneBundle.message(
                                "settings.images.purged.cancelled",
                                name,
                                done.images,
                                TasklaneDiagnostics.humanBytes(done.bytes),
                            )
                            done.images == 0 && done.bytes == 0L ->
                                TasklaneBundle.message("settings.images.purged.none", name)
                            else -> TasklaneBundle.message(
                                "settings.images.purged",
                                name,
                                done.images,
                                TasklaneDiagnostics.humanBytes(done.bytes),
                            )
                        }
                    }
                    Messages.showInfoMessage(imageWeightLabel, message, TasklaneBundle.message("settings.images.title"))
                }
            },
        )
    }

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

        /** Lo que propone la casilla del archivo al marcarla por primera vez: un mes. */
        private const val DEFAULT_ARCHIVE_DAYS = 30

        /** Diez años: por encima, «todo» dice lo mismo sin el número. */
        private const val MAX_ARCHIVE_DAYS = 3650

        /** Las columnas personales de la tabla de estados: lo que pide su cabecera. */
        private const val WINDOW_WIDTH = 100
        private const val BOARD_WIDTH = 70
        private const val STATUS_BAR_WIDTH = 90

        /** Un giga por paso: la cuota se piensa en gigas, no en megas. */
        private const val IMAGE_QUOTA_STEP = 1024
    }
}
