package com.tasklane.ui.quickadd

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.domain.command.TaskCommand
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneSnapshot
import com.tasklane.domain.text.TriggerParser
import com.tasklane.service.TaskService
import java.awt.AWTKeyStroke
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.function.Consumer
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.LayoutFocusTraversalPolicy
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Creación rápida de una tarea, sin salir del teclado.
 *
 * Es un [JBPopup] y no un `DialogWrapper` por una decisión de fricción: esto tiene
 * que durar tres segundos, y un diálogo modal con OK/Cancel cuesta más que la tarea
 * que se está apuntando. `setCancelOnWindowDeactivation(false)` es parte de lo
 * mismo: irse a mirar otra ventana a media frase no puede borrar lo escrito.
 *
 * Ver `docs/architecture.html` §6.
 */
internal class QuickAddPopup(private val project: Project) {

    private val service = TaskService.getInstance(project)
    private val snapshot: TasklaneSnapshot = service.snapshot.value
    private val config = snapshot.config

    /**
     * Un elemento de un desplegable. Existe sólo para que la etiqueta salga del
     * `toString()`: [DropDownLink] pinta los items con su representación de texto, y
     * la de un `data class` del dominio no es algo que enseñar a un usuario.
     *
     * La igualdad **es por valor y no por identidad**, y eso no es un detalle:
     * `DropDownLink.selectedItem` sólo notifica cuando el nuevo elemento es distinto
     * del actual. Con igualdad por identidad, [refreshPriority] —que construye un
     * `Choice` nuevo cada vez— dispararía el listener que vuelve a llamarla, y la
     * recursión no pararía nunca.
     */
    private class Choice<T>(val value: T, private val label: String) {
        override fun toString(): String = label

        override fun equals(other: Any?): Boolean =
            this === other || (other is Choice<*> && value == other.value)

        override fun hashCode(): Int = value.hashCode()
    }

    // ------------------------------------------------------------------ estado

    /** Lo que el usuario eligió a mano en el desplegable. */
    private var chosenPriority: TaskPriority = config.defaultPriority

    /** Lo que dictó el trigger escrito. Manda sobre [chosenPriority] mientras exista. */
    private var triggerPriority: TaskPriority? = null

    /**
     * El último trigger reconocido. Se compara con el nuevo para actuar sólo cuando
     * **cambia**: así elegir una prioridad a mano no se pierde en la siguiente tecla,
     * y borrar el trigger devuelve la prioridad que había antes.
     */
    private var lastTrigger: String? = null

    private var state: TaskState = config.defaultState
    private var repo: RepositoryRef? = defaultRepo()

    private fun priority(): TaskPriority = triggerPriority ?: chosenPriority

    // --------------------------------------------------------------- componentes

    private val textArea = JBTextArea(2, 42).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(2)
        // Sin esto el tabulador se escribiría en el texto en vez de mover el foco, y
        // el recorrido prioridad → estado → proyecto no existiría.
        setFocusTraversalKeys(
            KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
            setOf(AWTKeyStroke.getAWTKeyStroke(KeyEvent.VK_TAB, 0)),
        )
        setFocusTraversalKeys(
            KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
            setOf(AWTKeyStroke.getAWTKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK)),
        )
    }

    private val chip = JBLabel()
    private val hint = JBLabel().apply { font = UIUtil.getFont(UIUtil.FontSize.SMALL, font) }

    private val priorityLink = link(config.priorities, priority(), TaskPriority::name) {
        chosenPriority = it
        // Elegir a mano gana: el trigger que hubiera deja de mandar hasta que el
        // usuario escriba otro distinto.
        triggerPriority = null
        refreshPriority()
    }

    private val stateLink = link(config.states, state, TaskState::name) { state = it }

    private val repoLink = repo?.let { current ->
        link(snapshot.repositories, current, RepositoryRef::displayName) {
            repo = it
            // Cambiar de repositorio puede cambiar si se puede crear: uno abierto en
            // solo lectura no acepta tareas nuevas.
            refreshHint()
        }
    }

    private lateinit var popup: JBPopup

    // ------------------------------------------------------------------- montaje

    fun show() {
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent) = onTextChanged()
        })
        installKeys()
        refreshPriority()
        refreshHint()

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content(), textArea)
            .setTitle(TasklaneBundle.message("quickAdd.title"))
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            // Cambiar de ventana no puede perder lo escrito. Es la razón de que esto
            // sea un popup y no un diálogo, así que no se hereda el default.
            .setCancelOnWindowDeactivation(false)
            .createPopup()

        popup.showCenteredInCurrentWindow(project)
    }

    private fun content(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(chip)
            add(priorityLink)
            add(stateLink)
            repoLink?.let(::add)
        }

        val bottom = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(row, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply { isOpaque = false; add(hint) }, BorderLayout.EAST)
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            // Raiz de ciclo de foco: el tabulador recorre los tres desplegables y
            // vuelve al texto, en vez de escaparse a la ventana de detras.
            isFocusCycleRoot = true
            focusTraversalPolicy = LayoutFocusTraversalPolicy()
            add(
                JBScrollPane(
                    textArea,
                    JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                ).apply { border = JBUI.Borders.empty() },
                BorderLayout.CENTER,
            )
            add(bottom, BorderLayout.SOUTH)
            preferredSize = JBUI.size(460, 104)
        }
    }

    private fun <T : Any> link(
        items: List<T>,
        selected: T,
        label: (T) -> String,
        onSelect: (T) -> Unit,
    ): DropDownLink<Choice<T>> {
        val choices = items.map { Choice(it, label(it)) }
        val current = choices.firstOrNull { it.value == selected } ?: choices.first()
        return DropDownLink(current, choices, Consumer { onSelect(it.value) }, true)
    }

    // --------------------------------------------------------------------- teclas

    /**
     * Las teclas del popup se enganchan por `InputMap`/`ActionMap` del área de texto
     * y no como acciones registradas en el keymap. No es una excepción a la decisión
     * de §2 sobre atajos: `Enter` para aceptar y `⇧Enter` para saltar de línea son
     * estructura del control —lo mismo que hace `DialogWrapper`—, no comandos que
     * alguien vaya a querer reasignar en *Settings → Keymap*.
     */
    private fun installKeys() {
        val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "tasklane-create") { create(keepOpen = false) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuMask), "tasklane-create-more") { create(keepOpen = true) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "tasklane-newline") {
            textArea.replaceSelection("\n")
        }

        // ⌥1…9 selecciona prioridad por posición. Se registran las que existen: con
        // tres prioridades, ⌥4 no debe hacer nada en vez de fallar en silencio.
        config.priorities.take(9).forEachIndexed { index, priority ->
            val key = KeyStroke.getKeyStroke(KeyEvent.VK_1 + index, InputEvent.ALT_DOWN_MASK)
            bind(key, "tasklane-priority-$index") {
                chosenPriority = priority
                triggerPriority = null
                refreshPriority()
            }
        }
    }

    private fun bind(keyStroke: KeyStroke, name: String, action: () -> Unit) {
        textArea.inputMap.put(keyStroke, name)
        textArea.actionMap.put(
            name,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = action()
            },
        )
    }

    // -------------------------------------------------------------------- lógica

    private fun onTextChanged() {
        val match = TriggerParser.match(textArea.text, config)
        if (match?.trigger != lastTrigger) {
            lastTrigger = match?.trigger
            triggerPriority = match?.let { config.priority(it.priorityId) }
            refreshPriority()
        }
        refreshHint()
    }

    private fun targetRepo(): RepoKey = repo?.key ?: snapshot.activeRepo

    /**
     * Una tarea vacía no es una tarea, y un repositorio abierto en solo lectura no
     * acepta tareas nuevas: el servicio no escribiría el fichero, así que crearla
     * daría una fila que desaparece al recargar. El hint apagado es el aviso.
     */
    private fun canCreate(): Boolean =
        TriggerParser.strip(textArea.text, config).isNotBlank() && !service.isReadOnly(targetRepo())

    private fun create(keepOpen: Boolean) {
        if (!canCreate()) return
        val body = TriggerParser.strip(textArea.text, config).trim()

        service.apply(TaskCommand.Create(targetRepo(), body, state.id, priority().id))

        if (!keepOpen) {
            popup.cancel()
            return
        }
        // Encadenar varias: se limpia el texto pero se conservan estado y proyecto,
        // que es lo que se repite al apuntar tres cosas seguidas.
        textArea.text = ""
        triggerPriority = null
        lastTrigger = null
        refreshPriority()
        textArea.requestFocusInWindow()
    }

    private fun refreshPriority() {
        val priority = priority()
        chip.icon = ColorIcon(JBUI.scale(10), JBColor(priority.colorLight, priority.colorDark))
        priorityLink.selectedItem = Choice(priority, priority.name)
        priorityLink.text = priority.name
    }

    private fun refreshHint() {
        hint.text = TasklaneBundle.message("quickAdd.hint.create")
        hint.foreground =
            if (canCreate()) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
    }

    /**
     * El proyecto propuesto no es «el último usado» sino **el repositorio del fichero
     * abierto en el editor**. Es el detalle que hace que apuntar algo sobre el
     * fichero en el que estás trabajando no obligue a tocar el selector.
     *
     * Se elige la raíz más larga que contiene el fichero, no la primera: con un
     * submódulo dentro de un repositorio, el fichero pertenece al submódulo.
     */
    private fun defaultRepo(): RepositoryRef? {
        val repositories = snapshot.repositories
        if (repositories.isEmpty()) return null

        val path = FileEditorManager.getInstance(project).selectedEditor?.file?.path
        val fromEditor = path?.let { file ->
            repositories
                .filter { it.available && file.startsWith(it.rootPath.removeSuffix("/") + "/") }
                .maxByOrNull { it.rootPath.length }
        }
        return fromEditor
            ?: repositories.firstOrNull { it.key == snapshot.activeRepo }
            ?: repositories.first()
    }
}
