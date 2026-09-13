package com.tasklane.ui.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
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
import com.tasklane.domain.text.TriggerParser
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
import javax.swing.SwingUtilities

/**
 * Alta y edición de una tarea.
 *
 * Es el **único** sitio donde se escribe una tarea: lo abren *New Task*, `Enter` o el
 * doble clic sobre una fila, y desde la `0.6.8` también `⌘⌥R`. Hasta entonces Quick
 * Add tenía un popup propio, más ligero pero con la mitad de los campos —sin
 * vencimiento, sin etiquetas, sin barra de formato—, y una tarea apuntada de prisa
 * nacía distinta de una escrita con calma. Un solo diálogo y un solo sitio que tocar.
 *
 * Desde la Fase 6 el cuerpo se edita en un [MarkdownField] —un editor de verdad del
 * IDE— en vez de en un área de texto: es lo que permite pegar una captura y verla
 * aquí mismo. Necesita saber de qué repositorio es la tarea porque los adjuntos se
 * guardan junto a sus tareas, no en un pozo común del proyecto.
 *
 * **Los atributos se editan aquí y sólo aquí.** Estado, prioridad, vencimiento y
 * etiquetas son campos del modelo, no texto del cuerpo, así que no se pueden escribir
 * dentro del editor; este diálogo es su única entrada.
 *
 * La excepción son los **triggers de prioridad** —`!!! Resolver el fallo`—, que
 * antes sólo existían en el popup de Quick Add y desde la `0.6.8` viven aquí, que es
 * el único sitio donde se crea una tarea. Ver [installTriggers].
 */
internal class TaskEditDialog(
    private val project: Project,
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

    /**
     * Sustituye a la pista de «pega una captura»: dice lo mismo y además se puede
     * usar. Soltar y elegir acaban los dos en [MarkdownField], igual que el pegado.
     */
    private val dropZone = AttachmentDropZone(
        onFiles = bodyField::attachFiles,
        onClick = bodyField::chooseImage,
    )

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

    /** Las etiquetas, como fichas. Ver [TagChipsField]. */
    private val tagsField = TagChipsField(initialTags)

    init {
        title = TasklaneBundle.message(if (isNew) "dialog.task.new.title" else "dialog.task.edit.title")
        setOKButtonText(TasklaneBundle.message(if (isNew) "dialog.task.create" else "dialog.task.save"))
        init()
        installEscape()
    }

    /**
     * `Escape` cierra el diálogo **también con el cursor dentro del cuerpo**, que es
     * donde arranca el foco y donde no lo hacía.
     *
     * `DialogWrapper` ya registra la tecla en su `JRootPane`, pero por la vía de Swing
     * y con `WHEN_IN_FOCUSED_WINDOW`: esa vía es la última de la cola y sólo llega si
     * nadie se ha quedado la pulsación antes. Con el foco dentro de un editor de la
     * plataforma la tecla pasa primero por el despachador de acciones del IDE, así que
     * el camino que sí se recorre es registrar una acción, no un binding de Swing.
     *
     * Va **acotada al cuerpo** y no al diálogo entero a propósito. Registrada más
     * arriba se adelantaría también a los desplegables, y `Escape` con la lista de
     * prioridad desplegada tiene que cerrar la lista, no el diálogo.
     */
    private fun installEscape() {
        val cancel = object : DumbAwareAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            /**
             * Apagada cuando el editor tiene algo suyo que hacer con la tecla —deshacer
             * una selección o volver de varios cursores a uno—. Son dos acciones sobre
             * el mismo atajo y sólo puede estar viva una: ésta es exactamente la
             * condición que enciende la del editor, negada. De paso sale el orden que
             * espera quien viene de escribir código: primero se suelta la selección y
             * en la siguiente pulsación se cierra.
             */
            override fun update(e: AnActionEvent) {
                val editor = bodyField.component.editor
                e.presentation.isEnabled = editor == null ||
                    (!editor.selectionModel.hasSelection() && editor.caretModel.caretCount == 1)
            }

            override fun actionPerformed(e: AnActionEvent) = doCancelAction()
        }
        cancel.registerCustomShortcutSet(CommonShortcuts.ESCAPE, bodyField.component, disposable)
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(GAP))).apply {
        add(
            JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                add(bodyField.component, BorderLayout.CENTER)
                add(
                    JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                        add(MarkdownToolbar.create(bodyField, bodyField.component), BorderLayout.NORTH)
                        add(dropZone, BorderLayout.SOUTH)
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

        add(label("dialog.task.status", stateCombo), at(0, 0, 1.0))
        add(label("dialog.task.priority", priorityCombo), at(1, 0, 1.0))
        add(label("dialog.task.due", dueCombo), at(2, 0, 1.0))
        add(stateCombo, at(0, 1, 1.0))
        add(priorityCombo, at(1, 1, 1.0))
        add(dueCombo, at(2, 1, 1.0))
        add(label("dialog.task.tags", tagsField), at(0, 2, 1.0, width = 3))
        add(tagsField, at(0, 3, 1.0, width = 3))
    }

    /**
     * La etiqueta va **asociada** a su control con `labelFor`. Es lo que hace que un
     * lector de pantalla diga «Priority, combo» al llegar al desplegable en vez de
     * «combo» a secas: las tres etiquetas de la fila están encima de sus controles,
     * no al lado, y esa relación no se deduce de la posición.
     */
    private fun label(key: String, forComponent: JComponent) = JBLabel(TasklaneBundle.message(key)).apply {
        font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
        foreground = UIUtil.getContextHelpForeground()
        labelFor = forComponent
    }

    override fun getPreferredFocusedComponent(): JComponent = bodyField.component

    /**
     * Una tarea sin texto no es una tarea: se bloquea el OK en vez de crear ruido. Se
     * mide sobre el cuerpo **sin el trigger**, o `!!!` a secas pasaría por tarea.
     */
    override fun doValidate(): ValidationInfo? =
        if (stripTrigger(bodyField.text).isBlank()) {
            ValidationInfo(TasklaneBundle.message("dialog.task.empty"), bodyField.component)
        } else {
            null
        }

    val body: String get() = stripTrigger(bodyField.text).trim()
    val stateId: StateId get() = (stateCombo.selectedItem as TaskState).id
    val priorityId: PriorityId get() = (priorityCombo.selectedItem as TaskPriority).id
    val dueDate: Instant? get() = (dueCombo.selectedItem as DueOption).instant

    val tags: List<String> get() = tagsField.tags

    // ------------------------------------------------------------- vencimiento

    /**
     * Una entrada del desplegable de vencimiento. [instant] nulo == sin fecha, salvo
     * en la de [pick], que no es una fecha sino la puerta al calendario.
     */
    private class DueOption(
        val label: String,
        val instant: Instant?,
        val pick: Boolean = false,
        /** La fecha concreta, la única entrada que se sustituye al elegir otra. */
        val exact: Boolean = false,
    )

    /** La última opción que era de verdad una fecha, para volver si se cancela el calendario. */
    private var lastDue: DueOption? = null

    /** La entrada de fecha concreta que haya ahora mismo en la lista, si hay alguna. */
    private var exactOption: DueOption? = null

    /**
     * Segundo bloque de inicialización, y tiene que ir **aquí**: los dos campos de
     * arriba se declaran después del primero, y en Kotlin un `init` no puede escribir
     * en una propiedad que todavía no se ha declarado.
     */
    init {
        // La que traía la tarea, si no la produce ningún preajuste: es la que hay que
        // reemplazar cuando se elija otra en el calendario.
        exactOption = dueOptions.firstOrNull { it.exact }
        lastDue = dueCombo.selectedItem as? DueOption
        dueCombo.addActionListener { onDueChanged() }
    }

    /**
     * Abre el calendario cuando se elige «elegir fecha…», y deja el desplegable como
     * estaba si se cancela. Va en un `invokeLater` para que el desplegable termine de
     * cerrarse antes: abrir un diálogo con la lista aún desplegada deja el popup
     * colgado por encima.
     */
    private fun onDueChanged() {
        val option = dueCombo.selectedItem as? DueOption ?: return
        if (!option.pick) {
            lastDue = option
            return
        }
        SwingUtilities.invokeLater {
            val zone = ZoneId.systemDefault()
            val start = lastDue?.instant?.atZone(zone)?.toLocalDate() ?: LocalDate.now(zone)
            val picker = DueDateDialog(project, start, WeekFields.of(Locale.getDefault()).firstDayOfWeek)
            if (picker.showAndGet()) selectExact(picker.date, zone) else dueCombo.selectedItem = lastDue
        }
    }

    /**
     * Mete la fecha elegida en la lista y la selecciona. Sustituye a la exacta
     * anterior en vez de acumularlas: un desplegable con cinco fechas concretas de
     * intentos previos no ayuda a nadie.
     */
    private fun selectExact(date: LocalDate, zone: ZoneId) {
        val model = dueCombo.model as DefaultComboBoxModel<DueOption>
        exactOption?.let(model::removeElement)
        val instant = DueDates.atEndOfDay(date, zone)
        val option = DueOption(formatExact(instant, zone), instant, exact = true)
        exactOption = option
        // Delante de «elegir fecha…», que siempre cierra la lista.
        model.insertElementAt(option, model.size - 1)
        dueCombo.selectedItem = option
        lastDue = option
    }

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
            ?.let { DueOption(formatExact(it, zone), it, exact = true) }

        return listOfNotNull(none) + presets + listOfNotNull(exact) +
            DueOption(TasklaneBundle.message("dialog.task.due.pick"), null, pick = true)
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

    // ---------------------------------------------------------------- triggers

    /**
     * La prioridad elegida **a mano**, que es a la que se vuelve al borrar el
     * trigger. No basta con leer el desplegable: ahí puede estar la que dictó el
     * prefijo.
     */
    private var chosenPriority: TaskPriority = priorityCombo.selectedItem as TaskPriority

    /**
     * El último trigger reconocido. Se compara con el nuevo para actuar sólo cuando
     * **cambia**: así elegir una prioridad a mano no se pierde en la siguiente tecla,
     * y borrar el trigger devuelve la que había antes.
     */
    private var lastTrigger: String? = null

    /** Para no confundir un cambio nuestro del desplegable con una elección del usuario. */
    private var applyingTrigger = false

    /**
     * Tercer bloque de inicialización, y por lo mismo que el segundo: [installTriggers]
     * escribe en las tres propiedades de arriba, y en Kotlin un `init` no puede tocar
     * una propiedad que todavía no se ha declarado.
     */
    init {
        if (isNew) installTriggers()
    }

    /**
     * `!!! Resolver el fallo` crea la tarea *Resolver el fallo* con prioridad *High*:
     * el prefijo elige la prioridad y no se guarda. Vivía en el popup de Quick Add,
     * que era donde se escribía una tarea de un tirón; al pasar Quick Add a este
     * diálogo tenía que venirse con él o el gesto se perdía.
     *
     * **Sólo al crear.** El prefijo es una forma de teclear la prioridad mientras se
     * apunta algo, y sobre una tarea que ya existe el desplegable está a un clic. Es
     * además lo que evita que abrir una tarea cuyo cuerpo empieza por `!!! ` —texto
     * legítimo, escrito antes de configurar el prefijo— le cambie la prioridad y le
     * recorte el cuerpo sólo por haberla abierto y aceptado.
     */
    private fun installTriggers() {
        priorityCombo.addActionListener {
            if (applyingTrigger) return@addActionListener
            chosenPriority = priorityCombo.selectedItem as TaskPriority
        }
        bodyField.component.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = onBodyChanged()
        })
    }

    private fun onBodyChanged() {
        val match = TriggerParser.match(bodyField.text, config)
        if (match?.trigger == lastTrigger) return
        lastTrigger = match?.trigger

        val priority = match?.let { config.priority(it.priorityId) } ?: chosenPriority
        applyingTrigger = true
        try {
            priorityCombo.selectedItem = priority
        } finally {
            applyingTrigger = false
        }
    }

    /** El cuerpo sin el trigger: es una forma de escribir la prioridad, no parte de la tarea. */
    private fun stripTrigger(text: String): String =
        if (isNew) TriggerParser.strip(text, config) else text

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
