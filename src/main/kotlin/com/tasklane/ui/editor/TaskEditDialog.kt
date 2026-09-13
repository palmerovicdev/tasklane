package com.tasklane.ui.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.DueDates
import com.tasklane.domain.model.DuePreset
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/**
 * Alta y edición de una tarea.
 *
 * Sigue siendo un [DialogWrapper] después de la Fase 4, y a propósito: el popup
 * compacto es para **apuntar** algo en tres segundos —ahí la fricción de un OK/Cancel
 * se nota—, mientras que editar es una tarea deliberada sobre algo que ya existe, con
 * cuerpo largo y sin prisa. Ahí un diálogo con confirmación explícita es lo correcto.
 *
 * Desde la Fase 6 el cuerpo se edita en un [MarkdownField] —un editor de verdad del
 * IDE— en vez de en un área de texto: es lo que permite pegar una captura y verla
 * aquí mismo. Necesita saber de qué repositorio es la tarea porque los adjuntos se
 * guardan junto a sus tareas, no en un pozo común del proyecto.
 *
 * **Los atributos se editan aquí y sólo aquí.** Estado, prioridad, vencimiento y
 * etiquetas son campos del modelo, no texto del cuerpo, así que no se pueden escribir
 * dentro del editor; este diálogo es su única entrada. La excepción deliberada es la
 * lista de comprobación, que sí es Markdown del cuerpo y por eso vive en la barra de
 * formato y no en un control propio.
 */
internal class TaskEditDialog(
    project: Project,
    private val config: TasklaneConfig,
    repo: RepoKey,
    initialBody: String = "",
    initialState: StateId? = null,
    initialPriority: PriorityId? = null,
    initialTags: List<String> = emptyList(),
    initialDueDate: Instant? = null,
) : DialogWrapper(project) {

    private val isNew = initialBody.isEmpty()

    private val bodyField = MarkdownField(project, repo, initialBody, disposable).apply {
        component.setPlaceholder(TasklaneBundle.message("dialog.task.placeholder"))
    }

    private val hint = JBLabel(TasklaneBundle.message("dialog.task.paste.hint")).apply {
        font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
        foreground = UIUtil.getContextHelpForeground()
    }

    private val stateCombo = comboOf(config.states, initialState?.let(config::state) ?: config.defaultState) { it.name }
    private val priorityCombo =
        comboOf(config.priorities, initialPriority?.let(config::priority) ?: config.defaultPriority) { it.name }

    /**
     * Se construye **una vez** y se guarda. Generarla dos veces —una para el modelo y
     * otra para elegir la seleccionada— daba objetos distintos con el mismo texto, y
     * como [DueOption] se compara por identidad el desplegable arrancaba mostrando
     * una opción que no era ninguna de las suyas.
     */
    private val dueOptions = buildDueOptions(initialDueDate)

    private val dueCombo = comboOf(
        dueOptions,
        dueOptions.firstOrNull { it.instant == initialDueDate } ?: dueOptions.first(),
    ) { it.label }

    /**
     * Las etiquetas se escriben separadas por comas en un campo de texto normal.
     *
     * No es un control de fichas como el del boceto porque la plataforma no trae
     * ninguno, y escribir uno desde cero —con su borrado por retroceso, su navegación
     * con flechas y su accesibilidad— es un proyecto en sí mismo. Un campo con
     * comas es lo que hace que la función **exista** hoy; el control bonito es una
     * mejora encima, no un requisito para poder etiquetar.
     */
    private val tagsField = JBTextField(initialTags.joinToString(", ")).apply {
        emptyText.text = TasklaneBundle.message("dialog.task.tags.hint")
    }

    init {
        title = TasklaneBundle.message(if (isNew) "dialog.task.new.title" else "dialog.task.edit.title")
        setOKButtonText(TasklaneBundle.message(if (isNew) "dialog.task.create" else "dialog.task.save"))
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(GAP))).apply {
        add(
            JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                add(bodyField.component, BorderLayout.CENTER)
                add(
                    JPanel(BorderLayout()).apply {
                        add(MarkdownToolbar.create(bodyField, bodyField.component), BorderLayout.WEST)
                        add(hint, BorderLayout.EAST)
                    },
                    BorderLayout.SOUTH,
                )
            },
            BorderLayout.CENTER,
        )
        add(attributes(), BorderLayout.SOUTH)
    }

    /** Estado, prioridad y vencimiento en una fila; las etiquetas debajo, a lo ancho. */
    private fun attributes(): JComponent = JPanel(GridBagLayout()).apply {
        val gap = JBUI.scale(GAP)
        fun at(x: Int, y: Int, weight: Double, width: Int = 1) = GridBagConstraints().apply {
            gridx = x
            gridy = y
            gridwidth = width
            weightx = weight
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(if (y == 0) 0 else gap / 2, if (x == 0) 0 else gap, 0, 0)
        }

        add(label("dialog.task.status"), at(0, 0, 1.0))
        add(label("dialog.task.priority"), at(1, 0, 1.0))
        add(label("dialog.task.due"), at(2, 0, 1.0))
        add(stateCombo, at(0, 1, 1.0))
        add(priorityCombo, at(1, 1, 1.0))
        add(dueCombo, at(2, 1, 1.0))
        add(label("dialog.task.tags"), at(0, 2, 1.0, width = 3))
        add(tagsField, at(0, 3, 1.0, width = 3))
    }

    private fun label(key: String) = JBLabel(TasklaneBundle.message(key)).apply {
        font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
        foreground = UIUtil.getContextHelpForeground()
    }

    override fun getPreferredFocusedComponent(): JComponent = bodyField.component

    /** Una tarea sin texto no es una tarea: se bloquea el OK en vez de crear ruido. */
    override fun doValidate(): ValidationInfo? =
        if (bodyField.text.isBlank()) {
            ValidationInfo(TasklaneBundle.message("dialog.task.empty"), bodyField.component)
        } else {
            null
        }

    val body: String get() = bodyField.text.trim()
    val stateId: StateId get() = (stateCombo.selectedItem as TaskState).id
    val priorityId: PriorityId get() = (priorityCombo.selectedItem as TaskPriority).id
    val dueDate: Instant? get() = (dueCombo.selectedItem as DueOption).instant

    /**
     * Se acepta la coma y también la almohadilla de delante: quien copia `#api` de una
     * tarea no tiene por qué saber que aquí la marca sobra.
     */
    val tags: List<String>
        get() = tagsField.text
            .split(',', ' ', '\n')
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
            .distinct()

    // ------------------------------------------------------------- vencimiento

    /** Una entrada del desplegable de vencimiento. [instant] nulo == sin fecha. */
    private class DueOption(val label: String, val instant: Instant?)

    private fun buildDueOptions(current: Instant?): List<DueOption> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

        val presets = DuePreset.entries.map {
            DueOption(TasklaneBundle.message(bundleKey(it)), DueDates.resolve(it, today, zone, firstDayOfWeek))
        }
        val none = DueOption(TasklaneBundle.message("dialog.task.due.none"), null)
        // La fecha que ya tenía la tarea entra como opción propia si no la produce
        // ningún preajuste: sin esto, abrir para cambiar el título y aceptar movería
        // el vencimiento al lunes más cercano sin avisar.
        val exact = current
            ?.takeIf { due -> presets.none { it.instant == due } }
            ?.let { DueOption(formatExact(it, zone), it) }

        return listOfNotNull(none) + presets + listOfNotNull(exact)
    }

    private fun bundleKey(preset: DuePreset): String = when (preset) {
        DuePreset.TODAY -> "dialog.task.due.today"
        DuePreset.TOMORROW -> "dialog.task.due.tomorrow"
        DuePreset.END_OF_WEEK -> "dialog.task.due.endOfWeek"
        DuePreset.NEXT_WEEK -> "dialog.task.due.nextWeek"
    }

    private fun formatExact(due: Instant, zone: ZoneId): String =
        java.time.format.DateTimeFormatter.ofLocalizedPattern("yMMMd")
            .withZone(zone)
            .format(due)

    private fun <T : Any> comboOf(items: List<T>, selected: T, label: (T) -> String): JComboBox<T> {
        // Nada de items.toTypedArray(): exigiria un parametro de tipo reificado.
        val model: ComboBoxModel<T> = DefaultComboBoxModel<T>().apply { items.forEach(::addElement) }
        return JComboBox(model).apply {
            selectedItem = selected
            // Ambos overloads de SimpleListCellRenderer.create estan deprecados,
            // asi que se extiende la clase directamente.
            renderer = object : SimpleListCellRenderer<T>() {
                override fun customize(
                    list: JList<out T>,
                    value: T?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value?.let(label).orEmpty()
                }
            }
        }
    }

    private companion object {
        const val GAP = 8
    }
}
