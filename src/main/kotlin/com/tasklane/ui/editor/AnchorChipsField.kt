package com.tasklane.ui.editor

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.code.CodeAnchors
import com.tasklane.domain.model.AnchorReference
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.service.TaskService
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Las anclas de código de una tarea, como fichas, y un hueco al final donde se
 * **escribe** la siguiente: `plans/deploy.md:28`.
 *
 * Hasta la 2.8.0 no había forma de añadir una desde aquí: el único sitio del que salía
 * un ancla era el editor, con el cursor encima. Se abrió porque hay un caso en que el
 * sitio ya viene escrito —lo nombra una traza, un chat, un agente que acaba de escribir
 * un plan— y abrir el fichero sólo para volver a señalar la línea es un rodeo. Ver
 * [AnchorReference] para lo que se acepta.
 *
 * Se confirma con `Intro`, y ése es el único punto en que este campo le roba la tecla
 * al diálogo: **sólo con texto escrito**. Con el hueco vacío `Intro` sigue aceptando,
 * como en el resto del diálogo. Y lo escrito sin confirmar **cuenta** al aceptar, igual
 * que en [TagChipsField]: ver [commitPending].
 *
 * El hueco **autocompleta** rutas del proyecto mientras se escribe: ver
 * [ProjectPathCompletion]. Por eso es un campo de editor de la plataforma y no un
 * `JBTextField`: la lista de sugerencias es la misma que la del editor de código.
 */
internal class AnchorChipsField(
    private val project: Project,
    initial: List<CodeAnchor>,
    parentDisposable: Disposable,
) : JPanel(ChipsLayout(stretchLast = true)) {

    private var chips: List<CodeAnchor> = initial.distinct()

    private val editor = TextFieldWithCompletion(
        project,
        ProjectPathCompletion(project),
        "",
        true,
        true,
        false,
    ).apply {
        border = JBUI.Borders.empty()
        toolTipText = TasklaneBundle.message("dialog.task.code.tooltip")
    }

    /**
     * El error se pinta **debajo del hueco**, como el de cualquier campo de la
     * plataforma, y no en un diálogo aparte: quien se equivoca en un número quiere
     * corregirlo ahí mismo.
     */
    private val validator = ComponentValidator(parentDisposable).installOn(editor)

    val anchors: List<CodeAnchor> get() = chips

    init {
        isOpaque = true
        background = UIUtil.getTextFieldBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.namedColor("Component.borderColor", JBColor.GRAY), 1),
            JBUI.Borders.empty(PADDING),
        )
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                editor.requestFocusInWindow()
            }
        })
        editor.addDocumentListener(object : DocumentListener {
            // Se escribe otra cosa: el error de la anterior ya no aplica.
            override fun documentChanged(event: DocumentEvent) = showError(null)
        })
        installKeys(parentDisposable)
        rebuild()
    }

    /**
     * `Intro` confirma y retroceso con el hueco vacío se lleva la última ficha.
     *
     * Como acciones y no como `KeyListener`, por lo mismo que el `Escape` del diálogo:
     * el hueco es un editor de la plataforma y la tecla pasa antes por el despachador
     * de acciones del IDE. Cada una se **apaga** cuando no le toca, y entonces la tecla
     * sigue su camino: `Intro` con la lista de sugerencias abierta elige la sugerencia,
     * y con el hueco vacío acepta el diálogo.
     */
    private fun installKeys(parentDisposable: Disposable) {
        key(KeyEvent.VK_ENTER, parentDisposable, {
            editor.text.isNotBlank() && editor.editor?.let(LookupManager::getActiveLookup) == null
        }) { commitPending() }
        key(KeyEvent.VK_BACK_SPACE, parentDisposable, { editor.text.isEmpty() && chips.isNotEmpty() }) {
            chips = chips.dropLast(1)
            rebuild()
        }
    }

    private fun key(code: Int, parentDisposable: Disposable, enabled: () -> Boolean, run: () -> Unit) {
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = run()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(code, 0)), editor, parentDisposable)
    }

    /**
     * Convierte lo escrito en ficha. `null` == todo bien, incluido no haber nada
     * escrito; si no, el motivo, que ya se está enseñando junto al campo.
     *
     * Lo llama también el diálogo al aceptar: una ruta escrita y a la vista no puede
     * perderse por no haber pulsado `Intro`, y una mal escrita tiene que parar el
     * diálogo en vez de desaparecer.
     */
    fun commitPending(): ValidationInfo? {
        val text = editor.text
        if (text.isBlank()) return null
        val reference = AnchorReference.parse(text)
            ?: return fail(TasklaneBundle.message("dialog.task.code.invalid"))
        return when (val lookup = CodeAnchors.fromReference(project, reference)) {
            is CodeAnchors.Lookup.Missing -> fail(TasklaneBundle.message("dialog.task.code.missing", lookup.path))
            is CodeAnchors.Lookup.Folder -> fail(TasklaneBundle.message("dialog.task.code.folder", lookup.path))
            is CodeAnchors.Lookup.OutOfRange ->
                fail(TasklaneBundle.message("dialog.task.code.range", lookup.path, lookup.lines, reference.line + 1))
            is CodeAnchors.Lookup.Found -> {
                val anchor = lookup.anchor
                // Dos veces el mismo sitio es una ficha repetida, no dos anclas.
                if (chips.none { it.path == anchor.path && it.line == anchor.line }) chips = chips + anchor
                editor.text = ""
                rebuild()
                null
            }
        }
    }

    private fun fail(message: String): ValidationInfo {
        val info = ValidationInfo(message, editor)
        showError(info)
        return info
    }

    private fun showError(info: ValidationInfo?) {
        validator.updateInfo(info)
    }

    private fun rebuild() {
        removeAll()
        chips.forEach { anchor -> add(chipFor(anchor)) }
        editor.setPlaceholder(if (chips.isEmpty()) TasklaneBundle.message("dialog.task.code.hint") else null)
        add(editor)
        revalidate()
        repaint()
        if (isShowing) editor.requestFocusInWindow()
    }

    /**
     * El texto es `Fichero.kt:42` y la ruta entera va al tooltip: en el diálogo hay
     * sitio para el nombre, no para `src/main/kotlin/com/…/AuthService.kt`.
     *
     * Una rota (2.13.0) lleva el icono de aviso, como en la tarjeta: aquí es donde se
     * quita, y quien abre la tarea para limpiarla tiene que ver cuál sobra.
     */
    private fun chipFor(anchor: CodeAnchor): Chip {
        val where = "${anchor.path}:${anchor.line + 1}"
        val broken = anchor.path in TaskService.getInstance(project).snapshot.value.brokenAnchors
        return Chip(
            text = anchor.label,
            icon = if (broken) AllIcons.General.Warning else AllIcons.FileTypes.Any_type,
            tooltip = if (broken) {
                TasklaneBundle.message("toolwindow.row.anchor.broken.tooltip", StringUtil.escapeXmlEntities(where))
            } else {
                where
            },
            removeTooltip = TasklaneBundle.message("dialog.task.code.remove", anchor.label),
        ) {
            chips = chips - anchor
            rebuild()
        }
    }

    private companion object {
        const val PADDING = 3
    }
}
