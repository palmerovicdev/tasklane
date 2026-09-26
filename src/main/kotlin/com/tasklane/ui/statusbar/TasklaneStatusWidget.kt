package com.tasklane.ui.statusbar

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.StatusBarCounts
import com.tasklane.service.StatusCounts
import com.tasklane.service.TaskService
import com.tasklane.ui.common.TasklaneIcons
import com.tasklane.ui.toolwindow.TaskReveal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent

/**
 * El widget de la barra de estado (2.14.0): «ToDo 3 · Doing 1 · 2 overdue» del
 * repositorio activo, con lo vencido en rojo.
 *
 * Qué cuenta se elige en *Settings → Tools → Tasklane → Status bar*, y si se ve o no, con
 * el menú de la propia barra (clic derecho), que es donde el IDE enciende y apaga todos los
 * suyos: repetirlo en los ajustes serían dos interruptores para lo mismo.
 *
 * **Cada cuenta se pulsa.** Un estado abre la ventana en su pestaña; lo vencido, como el
 * *Show* del aviso de vencimientos, lleva a esas tareas; el icono o cualquier otro sitio
 * abre la ventana tal como estaba.
 */
internal class TasklaneStatusWidget(
    private val project: Project,
    private val scope: CoroutineScope,
) : CustomStatusBarWidget {

    private val label = CountsLabel()

    init {
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && !e.isPopupTrigger) open(label.getFragmentTagAt(e.x))
            }
        })
        // Con cualquier modalidad: se aplica desde los ajustes, que son modales, y la barra
        // tiene que cambiar delante de quien acaba de pulsar Apply.
        scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            StatusCounts.getInstance(project).counts.collect { label.show(it) }
        }
    }

    override fun ID(): String = ID

    override fun getComponent(): JComponent = label

    private fun open(tag: Any?) {
        when (tag) {
            is StateId -> TaskReveal.showState(project, tag)
            Overdue -> scope.launch(Dispatchers.IO) {
                val tasks = TaskService.getInstance(project)
                val due = tasks.dueBy(tasks.snapshot.value.activeRepo, Instant.now())
                withContext(Dispatchers.EDT) {
                    // Pudo dejar de haberlas entre pintar y pulsar: entonces, la ventana.
                    if (due.isEmpty()) TaskReveal.open(project) else TaskReveal.show(project, due)
                }
            }
            else -> TaskReveal.open(project)
        }
    }

    /** La etiqueta de lo vencido. Los estados llevan su [StateId]. */
    internal object Overdue

    /**
     * Texto de varios colores con la fuente, el color y los márgenes de un widget de la
     * plataforma. `SimpleColoredComponent` y no el `TextPanel` de la barra, que pinta todo
     * de un color: lo vencido tiene que poder ir en rojo sin llevarse el resto.
     */
    internal class CountsLabel : SimpleColoredComponent() {

        init {
            isOpaque = false
            // El fondo al pasar el ratón lo pinta la barra, debajo: con un fondo propio lo
            // taparía.
            setIconOpaque(false)
            icon = TasklaneIcons.StatusBar
            setMyBorder(null)
            ipad = JBUI.emptyInsets()
            border = JBUI.CurrentTheme.StatusBar.Widget.border()
        }

        /** La de la barra, siempre: al cambiar de tema o de tamaño de letra cambia sola. */
        override fun getFont(): Font = JBUI.CurrentTheme.StatusBar.font()

        fun show(counts: StatusBarCounts?) {
            clear()
            var first = true
            fun separate() {
                if (!first) append(SEPARATOR, SEPARATOR_STYLE)
                first = false
            }
            counts?.states?.forEach { (state, n) ->
                separate()
                append(TasklaneBundle.message("statusbar.state", state.name, n), TEXT_STYLE, state.id)
            }
            val overdue = counts?.overdue ?: 0
            if (overdue > 0) {
                separate()
                append(TasklaneBundle.message("statusbar.overdue", overdue), OVERDUE_STYLE, Overdue)
            }
            toolTipText = tooltip(counts)
            // `getAccessibleContext()` y no `accessibleContext`: ver el de `StateTabRow`.
            getAccessibleContext().accessibleName = TasklaneBundle.message("statusbar.a11y", describe(counts))
            revalidate()
            repaint()
        }

        /**
         * Como en `StateTabRow`: `SimpleColoredComponent` no crea su contexto accesible, y
         * ponerle nombre sin esto lanzaba un `NullPointerException`.
         */
        override fun getAccessibleContext(): AccessibleContext {
            if (accessibleContext == null) {
                accessibleContext = object : AccessibleJComponent() {
                    override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON
                }
            }
            return accessibleContext
        }

        private fun tooltip(counts: StatusBarCounts?): String {
            if (counts == null) return TasklaneBundle.message("statusbar.name")
            // Los nombres van dentro de HTML: un estado llamado «<wip>» se lo comería.
            val repo = StringUtil.escapeXmlEntities(counts.repo)
            if (counts.states.isEmpty() && counts.overdue == null) {
                return TasklaneBundle.message("statusbar.tooltip.nothing", repo)
            }
            return TasklaneBundle.message("statusbar.tooltip", repo, StringUtil.escapeXmlEntities(describe(counts)))
        }

        /** Todo en una línea, también lo vencido cuando es cero: el tooltip sí lo dice. */
        private fun describe(counts: StatusBarCounts?): String {
            if (counts == null) return ""
            val parts = counts.states.map { (state, n) -> TasklaneBundle.message("statusbar.state", state.name, n) } +
                listOfNotNull(
                    counts.overdue?.let {
                        if (it > 0) TasklaneBundle.message("statusbar.overdue", it)
                        else TasklaneBundle.message("statusbar.overdue.none")
                    },
                )
            return parts.joinToString(SEPARATOR)
        }
    }

    companion object {
        const val ID = "Tasklane.StatusBar"

        private const val SEPARATOR = " · "

        private val TEXT_STYLE = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND)

        private val SEPARATOR_STYLE = SimpleTextAttributes.GRAYED_ATTRIBUTES

        /** El rojo de la fecha vencida en la tarjeta: el mismo aviso, el mismo color. */
        private val OVERDUE_STYLE = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.RED)
    }
}

/** Lo que registra `plugin.xml`. El IDE crea un widget por ventana de proyecto. */
internal class TasklaneStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = TasklaneStatusWidget.ID

    override fun getDisplayName(): String = TasklaneBundle.message("statusbar.name")

    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget =
        TasklaneStatusWidget(project, scope)
}
