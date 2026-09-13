package com.tasklane.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.tasklane.TasklaneBundle
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Los botones de formato que van bajo el cuerpo de la tarea.
 *
 * Son acciones de la plataforma y no `JButton`s porque así la barra sale con el
 * aspecto y el espaciado de cualquier otra del IDE, y cada botón trae su *tooltip*
 * del bundle sin trabajo extra. No se declaran en `plugin.xml`: fuera de este
 * diálogo no significan nada, así que ni deben salir en *Search Everywhere* ni
 * ocupar un identificador global.
 *
 * Lo que insertan es Markdown-lite, el mismo que entiende el resto del plugin: las
 * casillas de la lista de comprobación que aparecen aquí son exactamente las que
 * luego cuenta la tarjeta de la fila.
 */
internal object MarkdownToolbar {

    fun create(field: MarkdownField, target: JComponent): JComponent {
        // Negrita, cursiva y código van como glifo y no como icono porque la
        // plataforma no trae ninguno para ellos, y «B» en negrita se entiende en
        // cualquier editor del mundo mejor que un icono inventado aquí.
        val group = DefaultActionGroup(
            button("bold", glyph = "B") { field.wrapSelection("**") },
            button("italic", glyph = "I") { field.wrapSelection("*") },
            button("code", glyph = "</>") { field.wrapSelection("`") },
            Separator.getInstance(),
            // El cursor queda entre los corchetes, que es donde se escribe el texto
            // del enlace; la URL va detrás, ya seleccionable de un doble clic.
            button("link", icon = AllIcons.ToolbarDecorator.AddLink) { field.wrapSelection("[", "](url)") },
            // La imagen no se inserta como marca: se elige el fichero, se guarda y lo
            // que entra en el texto es la referencia al blob. Por eso vive aquí y no
            // en un `wrapSelection` como el resto.
            button("image", icon = AllIcons.FileTypes.Image) { field.chooseImage() },
            Separator.getInstance(),
            button("bullet", icon = AllIcons.Actions.ListFiles) { field.prefixLines("- ") },
            button("numbered", glyph = "1.") { field.prefixLines("", numbered = true) },
            button("checklist", icon = AllIcons.Actions.Checked) { field.prefixLines("- [ ] ") },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.EDITOR_TOOLBAR, group, true)
        toolbar.targetComponent = target
        return toolbar.component
    }

    private fun button(
        key: String,
        icon: Icon? = null,
        glyph: String? = null,
        run: () -> Unit,
    ) = object : DumbAwareAction(
        // El texto es el glifo cuando lo hay; la descripción —que es lo que sale en
        // el tooltip— siempre es el nombre completo y traducible de la acción.
        glyph ?: TasklaneBundle.message("dialog.task.format.$key"),
        TasklaneBundle.message("dialog.task.format.$key"),
        icon,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = run()
    }
}
