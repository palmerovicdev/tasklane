package com.tasklane.ui.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.DueDates
import com.tasklane.domain.model.DuePreset
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.InlineTag
import com.tasklane.domain.text.TagParser
import com.tasklane.domain.text.TriggerParser
import com.tasklane.domain.text.TriggerParser.Target
import com.tasklane.ui.common.PriorityDot
import com.tasklane.ui.common.StateIcons
import com.tasklane.ui.toolwindow.KeymapShortcut
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
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
 * La excepción son los **triggers** —`Resolver el fallo !!!`, `@doing`—, que antes sólo
 * existían en el popup de Quick Add y desde la `0.6.8` viven aquí, que es el único sitio
 * donde se crea una tarea; desde la 2.30.0 también los de estado, en cualquier sitio del
 * texto y hechos pastilla. Ver [TriggerBadges]. Y desde la 2.29.0 las **etiquetas escritas
 * en el cuerpo**: `#api` y un espacio pasa a las fichas. Ver [installInlineTags].
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
    initialAnchors: List<CodeAnchor> = emptyList(),
    /**
     * Si se está **creando**. Es lo que decide el título y el botón. Por defecto se deduce
     * del cuerpo —crear empieza en blanco—, y hay que decirlo a mano en el único caso en
     * que esa deducción falla: crear desde el editor, que nace con la selección ya escrita.
     */
    private val isNew: Boolean = initialBody.isEmpty(),
) : DialogWrapper(project) {

    /**
     * La etiqueta que el cuerpo ya traía escrita al final, si traía una: ésa es texto de la
     * tarea y no se toca al aceptar. Ver [pendingTag].
     */
    private val initialTrailingTag = InlineTag.trailing(initialBody)?.tag

    /** Lo mismo con un trigger: el que el cuerpo ya traía al final es texto. Ver [pendingTrigger]. */
    private val initialTrailingTrigger = TriggerParser.trailing(initialBody, config)?.trigger

    private val bodyField = MarkdownField(project, repo, initialBody, disposable).apply {
        component.setPlaceholder(placeholder(config))
    }

    /**
     * Sustituye a la pista de «pega una captura»: dice lo mismo y además se puede
     * usar. Soltar y elegir acaban los dos en [MarkdownField], igual que el pegado.
     */
    private val dropZone = AttachmentDropZone(
        onFiles = bodyField::attachFiles,
        onClick = bodyField::chooseImage,
    )

    /** Con el círculo de cada estado y el punto de cada prioridad: los de las pastillas del cuerpo. */
    private val stateCombo = comboOf(
        config.states,
        initialState?.let(config::state) ?: config.defaultState,
        icon = { StateIcons.of(it, config.states) },
    ) { it.name }
    private val priorityCombo = comboOf(
        config.priorities,
        initialPriority?.let(config::priority) ?: config.defaultPriority,
        icon = PriorityDot::menu,
    ) { it.name }

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

    /** Las etiquetas, como fichas, con las del repositorio sugeridas. Ver [TagChipsField]. */
    private val tagsField = TagChipsField(project, repo, initialTags, config, disposable)

    /**
     * Los sitios del código de los que habla la tarea: los que trae y los que se
     * escriban a mano. Ver [AnchorChipsField].
     */
    private val anchorsField = AnchorChipsField(project, initialAnchors, disposable)

    init {
        title = TasklaneBundle.message(if (isNew) "dialog.task.new.title" else "dialog.task.edit.title")
        setOKButtonText(TasklaneBundle.message(if (isNew) "dialog.task.create" else "dialog.task.save"))
        init()
        installEscape()
        installInlineTags()
        installStateKeys()
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

        add(statusLabel(), at(0, 0, 1.0))
        add(label("dialog.task.priority", priorityCombo), at(1, 0, 1.0))
        add(label("dialog.task.due", dueCombo), at(2, 0, 1.0))
        add(stateCombo, at(0, 1, 1.0))
        add(priorityCombo, at(1, 1, 1.0))
        add(dueCombo, at(2, 1, 1.0))
        add(label("dialog.task.tags", tagsField), at(0, 2, 1.0, width = 3))
        add(tagsField, at(0, 3, 1.0, width = 3))
        // Siempre a la vista desde la 2.8.0: además de enseñar las anclas que trae,
        // es donde se escribe una nueva. Ver [AnchorChipsField].
        add(label("dialog.task.code", anchorsField), at(0, 4, 1.0, width = 3))
        add(anchorsField, at(0, 5, 1.0, width = 3))
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

    /**
     * *Status* con el atajo que lo cambia a la derecha: un atajo que no se ve no se descubre,
     * y éste no está en ningún menú. El que diga el Keymap al abrir el diálogo.
     */
    private fun statusLabel(): JComponent {
        val previous = KeymapUtil.getFirstKeyboardShortcutText(stateShortcut(PREVIOUS_STATE, KeyEvent.VK_LEFT))
        val next = KeymapUtil.getFirstKeyboardShortcutText(stateShortcut(NEXT_STATE, KeyEvent.VK_RIGHT))
        stateCombo.toolTipText = TasklaneBundle.message("dialog.task.status.shortcut", previous, next)
        return JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
            isOpaque = false
            add(label("dialog.task.status", stateCombo), BorderLayout.WEST)
            add(
                JBLabel("$previous $next").apply {
                    font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = stateCombo.toolTipText
                },
                BorderLayout.EAST,
            )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = bodyField.component

    /**
     * Una tarea sin texto no es una tarea: se bloquea el OK en vez de crear ruido. Se
     * mide sobre el cuerpo **sin el trigger**, o `!!!` a secas pasaría por tarea.
     *
     * Y una ruta escrita en el campo de código sin confirmar se confirma aquí: o entra
     * como ancla, o para el diálogo con el motivo. Ver [AnchorChipsField.commitPending].
     * Es la única validación con efecto, y puede tenerlo porque el diálogo sólo valida
     * al aceptar —no llama a `initValidation`—.
     */
    override fun doValidate(): ValidationInfo? =
        if (body.isEmpty()) {
            ValidationInfo(TasklaneBundle.message("dialog.task.empty"), bodyField.component)
        } else {
            anchorsField.commitPending()
        }

    val body: String
        get() {
            val text = withoutPendingTrigger(bodyField.text)
            return (pendingTag(text)?.let { InlineTag.remove(text, it) } ?: text).trim()
        }
    val stateId: StateId
        get() = (pendingTrigger(bodyField.text)?.target as? Target.State)?.id ?: (stateCombo.selectedItem as TaskState).id
    val priorityId: PriorityId
        get() = (pendingTrigger(bodyField.text)?.target as? Target.Priority)?.id
            ?: (priorityCombo.selectedItem as TaskPriority).id
    val dueDate: Instant? get() = (dueCombo.selectedItem as DueOption).instant

    val tags: List<String>
        get() = pendingTag(withoutPendingTrigger(bodyField.text))?.let { TagParser.add(tagsField.tags, it.tag) }
            ?: tagsField.tags

    val anchors: List<CodeAnchor> get() = anchorsField.anchors

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

    // ------------------------------------------------------ etiquetas del cuerpo

    /**
     * `#api` y un espacio, una coma o un salto de línea, escrito en el cuerpo, pasa a las
     * fichas de etiquetas y sale del texto (2.29.0): es la forma de etiquetar sin soltar el
     * teclado mientras se apunta la tarea. Qué cuenta como etiqueta lo decide [InlineTag].
     *
     * **Al teclear el separador**, no al aceptar: la ficha aparece en el momento y quien la
     * escribió ve qué ha pasado con su texto. Por eso vale también al editar —sólo mira lo
     * que se acaba de escribir—, y el texto que la tarea ya traía no se toca nunca.
     *
     * Sólo un carácter tecleado: un pegado trae texto de otro sitio —una lista de
     * incidencias, un trozo de Markdown— y se queda como llega.
     */
    private fun installInlineTags() {
        bodyField.component.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val typed = event.newFragment
                if (event.oldLength != 0 || typed.length != 1 || !TagParser.isSeparator(typed[0])) return
                val offset = event.offset
                // El documento no se puede tocar desde dentro de su propio evento.
                ApplicationManager.getApplication().invokeLater(
                    { takeTagBefore(offset, typed[0]) },
                    ModalityState.stateForComponent(bodyField.component),
                )
            }
        })
    }

    private fun takeTagBefore(offset: Int, separator: Char) {
        val document = bodyField.component.document
        val text = document.text
        // Lo que se tecleó después ya pudo moverlo todo: sólo si el separador sigue ahí.
        if (text.getOrNull(offset) != separator) return
        val found = InlineTag.endingAt(text, offset) ?: return
        val (first, last) = InlineTag.cut(text, found)
        WriteCommandAction.writeCommandAction(project)
            .withName(TasklaneBundle.message("dialog.task.tags.inline.command"))
            .run<RuntimeException> { document.deleteString(first, last) }
        tagsField.addTag(found.tag)
    }

    /**
     * La etiqueta que se quedó al final del cuerpo sin separador: aceptar con `Revisar #api`
     * escrito no puede dejar `#api` en el texto, como el campo de fichas no pierde lo que
     * está a medio escribir. Salvo que ya estuviera al abrir: ver [initialTrailingTag].
     */
    private fun pendingTag(text: String): InlineTag.Found? =
        InlineTag.trailing(text)?.takeIf { it.tag != initialTrailingTag }

    // ---------------------------------------------------------------- triggers

    /**
     * La prioridad y el estado elegidos **a mano**, que es a lo que se vuelve al quitar una
     * pastilla. No basta con leer el desplegable: ahí puede estar lo que dictó el trigger.
     */
    private var chosenPriority: TaskPriority = priorityCombo.selectedItem as TaskPriority
    private var chosenState: TaskState = stateCombo.selectedItem as TaskState

    /** Para no confundir un cambio nuestro del desplegable con una elección del usuario. */
    private var applyingTrigger = false

    /**
     * `!!! ` o `@doing ` escritos en cualquier sitio del cuerpo, hechos pastilla y llevados al
     * desplegable (2.30.0). **También al editar**: hasta la 2.29 sólo valían al crear, para que
     * abrir una tarea cuyo cuerpo ya empezaba por `!!! ` no le cambiara la prioridad; ahora sólo
     * cuenta lo que se teclea, como con las etiquetas, y el texto que traía no se toca.
     */
    private val badges = TriggerBadges(project, config, bodyField, disposable, ::onTrigger)

    /**
     * Tercer bloque de inicialización, y por lo mismo que el segundo: escribe en las
     * propiedades de arriba, y en Kotlin un `init` no puede tocar una propiedad que todavía
     * no se ha declarado.
     */
    init {
        priorityCombo.addActionListener {
            if (applyingTrigger) return@addActionListener
            chosenPriority = priorityCombo.selectedItem as TaskPriority
            badges.drop(TriggerBadges.Kind.PRIORITY)
        }
        stateCombo.addActionListener {
            if (applyingTrigger) return@addActionListener
            chosenState = stateCombo.selectedItem as TaskState
            badges.drop(TriggerBadges.Kind.STATE)
        }
    }

    private fun onTrigger(kind: TriggerBadges.Kind, target: Target?) {
        applyingTrigger = true
        try {
            when (kind) {
                TriggerBadges.Kind.PRIORITY ->
                    priorityCombo.selectedItem = (target as? Target.Priority)?.let { config.priority(it.id) } ?: chosenPriority

                TriggerBadges.Kind.STATE ->
                    stateCombo.selectedItem = (target as? Target.State)?.let { config.state(it.id) } ?: chosenState
            }
        } finally {
            applyingTrigger = false
        }
    }

    /**
     * El trigger que se quedó al final sin separador: aceptar con `Revisar el login !!!`
     * escrito no puede dejar `!!!` en la tarea. Salvo que ya estuviera al abrir: ver
     * [initialTrailingTrigger].
     */
    private fun pendingTrigger(text: String): TriggerParser.Match? =
        TriggerParser.trailing(text, config)?.takeIf { it.trigger != initialTrailingTrigger }

    private fun withoutPendingTrigger(text: String): String =
        pendingTrigger(text)?.let { TriggerParser.remove(text, it) } ?: text

    // ------------------------------------------------------- estado con el teclado

    /**
     * `⌘⌥→`/`⌘⌥←` (`Ctrl+Alt` fuera de macOS) eligen el estado siguiente o el anterior desde
     * cualquier sitio del diálogo, también con el cursor en el cuerpo (2.30.0): crear en otro
     * estado sin soltar el teclado. No el `⇧⌥←/→` de la lista, que dentro de un editor es
     * seleccionar por palabras.
     *
     * Se declaran en `plugin.xml` para que se puedan reasignar en el Keymap, y se instalan aquí
     * sobre el diálogo con lo que diga el Keymap al pulsar. Como en la lista, sin dar la vuelta:
     * en el último estado, `→` no hace nada.
     */
    private fun installStateKeys() {
        NextDialogStateAction().registerCustomShortcutSet(stateShortcut(NEXT_STATE, KeyEvent.VK_RIGHT), rootPane, disposable)
        PreviousDialogStateAction().registerCustomShortcutSet(
            stateShortcut(PREVIOUS_STATE, KeyEvent.VK_LEFT),
            rootPane,
            disposable,
        )
    }

    private fun stateShortcut(actionId: String, key: Int) = KeymapShortcut(
        actionId,
        CustomShortcutSet(
            KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx or InputEvent.ALT_DOWN_MASK),
        ),
    )

    fun canStepState(delta: Int): Boolean = stateCombo.selectedIndex + delta in 0 until stateCombo.itemCount

    /** Como elegirlo en el desplegable: manda sobre la pastilla, si la había. */
    fun stepState(delta: Int) {
        if (canStepState(delta)) stateCombo.selectedIndex += delta
    }

    private fun <T : Any> comboOf(
        items: List<T>,
        selected: T,
        icon: (T) -> Icon? = { null },
        label: (T) -> String,
    ): JComboBox<T> {
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
                    this.icon = value?.let(icon)
                }
            }
        }
    }

    private companion object {
        const val GAP = 8
        const val NEXT_STATE = "Tasklane.NextDialogState"
        const val PREVIOUS_STATE = "Tasklane.PreviousDialogState"

        /**
         * La pista del cuerpo vacío, con los triggers que haya de verdad: los de fábrica
         * pueden no ser los del proyecto. El de la prioridad más alta —la última— y el del
         * primer estado que no es el de por defecto, que son los que se quieren al apuntar
         * algo con prisa.
         */
        fun placeholder(config: TasklaneConfig): String {
            if (!config.triggersEnabled) return TasklaneBundle.message("dialog.task.placeholder")
            val priority = config.priorities.lastOrNull { it.trigger != null }?.trigger
            val state = config.states.firstOrNull { it.trigger != null && !it.isDefault }?.trigger
            return when {
                priority != null && state != null -> TasklaneBundle.message("dialog.task.placeholder.both", priority, state)
                priority != null -> TasklaneBundle.message("dialog.task.placeholder.priority", priority)
                state != null -> TasklaneBundle.message("dialog.task.placeholder.state", state)
                else -> TasklaneBundle.message("dialog.task.placeholder")
            }
        }
    }
}
