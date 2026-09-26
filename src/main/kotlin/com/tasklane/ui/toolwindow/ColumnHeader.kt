package com.tasklane.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import java.awt.BorderLayout
import java.awt.Point
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * El tablero visto desde una de sus columnas (2.17.0).
 *
 * Una lista de la tool window hace sola lo que en el tablero es de todas las columnas a la
 * vez —buscar, llevar la selección a otro estado, soltar una tarjeta fuera de su lista— y
 * aquí se lo pide a quien las tiene a todas. Con `null` en su sitio, [TasklanePanel] es la
 * pestaña de siempre.
 */
internal interface BoardHost {

    /**
     * Si [state] tiene columna (2.17.1). Pasar de columna con el teclado salta los estados
     * que no están en el tablero: llevar una tarea con `⇧⌥→` a uno sin columna la haría
     * desaparecer de delante sin que se viera adónde.
     */
    fun shows(state: StateId): Boolean

    /** El buscador es uno para todo el tablero, encima de las columnas. */
    fun focusSearch()

    /**
     * Seleccionar [ids] en la columna de [state] en cuanto lleguen, y ponerla a la vista.
     * Lo pide *Move To* sobre una columna: la selección se va con las tarjetas, y un
     * segundo `⇧⌥→` las sigue llevando.
     */
    fun follow(state: StateId, ids: Set<TaskId>)

    /** Ver [CardCarry.over]. [source] es la columna de la que salen. */
    fun carry(source: TasklanePanel, tasks: List<Task>, screen: Point): Boolean

    /** Ver [CardCarry.drop]. */
    fun drop(source: TasklanePanel, tasks: List<Task>, screen: Point): Boolean
}

/**
 * La cabecera de una columna del tablero (2.17.0): el nombre del estado y cuántas tiene, y
 * a la derecha crear ahí y cómo se agrupa.
 *
 * Es lo que en la tool window hacen la fila de pestañas y la barra. En el tablero las dos
 * sobran: las pestañas son las columnas, y la barra de cada una diría lo mismo que las
 * demás. Crear y agrupar sí son de la columna —la tarea nace en ese estado, y la agrupación
 * es del estado—, y salen del grupo `Tasklane.BoardColumn` de `plugin.xml`, con las mismas
 * acciones que la barra de la ventana.
 */
internal class ColumnHeader : JPanel(BorderLayout()) {

    private val title = object : SimpleColoredComponent() {
        /** Ver el mismo arreglo en [StateTabRow]: sin esto no hay dónde poner el nombre accesible. */
        override fun getAccessibleContext(): AccessibleContext {
            if (accessibleContext == null) {
                accessibleContext = object : AccessibleJComponent() {
                    override fun getAccessibleRole(): AccessibleRole = AccessibleRole.LABEL
                }
            }
            return accessibleContext
        }
    }

    private val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar(
        PLACE,
        ActionManager.getInstance().getAction(GROUP) as ActionGroup,
        true,
    )

    init {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(2, 10, 2, 2),
        )
        title.isOpaque = false
        toolbar.component.isOpaque = false
        toolbar.component.border = JBUI.Borders.empty()
        add(title, BorderLayout.CENTER)
        add(toolbar.component, BorderLayout.EAST)
    }

    /** Con quién hablan sus acciones: la lista de la columna, para que vean su `DataContext`. */
    fun target(component: JComponent) {
        toolbar.targetComponent = component
    }

    fun show(name: String, count: Int) {
        title.clear()
        title.append(name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        title.append("  $count", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        title.getAccessibleContext().accessibleName = TasklaneBundle.message("a11y.tab", name, count)
        title.repaint()
    }

    private companion object {
        const val GROUP = "Tasklane.BoardColumn"
        const val PLACE = "TasklaneBoardColumn"
    }
}
