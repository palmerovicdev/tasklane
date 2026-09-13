package com.tasklane.ui.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.tasklane.TasklaneBundle
import com.tasklane.ui.common.TasklaneIcons
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
 * Lo que insertan es Markdown-lite, el mismo que entiende el resto del plugin: el
 * énfasis que se pone aquí es exactamente el que luego pinta la tarjeta de la fila.
 * Ver `InlineMarkdown`.
 */
internal object MarkdownToolbar {

    fun create(field: MarkdownField, target: JComponent): JComponent {
        val group = DefaultActionGroup(
            button("bold", TasklaneIcons.FormatBold) { field.wrapSelection("**") },
            button("italic", TasklaneIcons.FormatItalic) { field.wrapSelection("*") },
            button("code", TasklaneIcons.FormatCode) { field.wrapSelection("`") },
            Separator.getInstance(),
            // El cursor queda entre los corchetes, que es donde se escribe el texto
            // del enlace; la URL va detrás, ya seleccionable de un doble clic.
            button("link", TasklaneIcons.FormatLink) { field.wrapSelection("[", "](url)") },
            // La imagen no se inserta como marca: se elige el fichero, se guarda y lo
            // que entra en el texto es la referencia al blob. Por eso vive aquí y no
            // en un `wrapSelection` como el resto.
            button("image", TasklaneIcons.FormatImage) { field.chooseImage() },
            Separator.getInstance(),
            button("bullet", TasklaneIcons.FormatBullet) { field.prefixLines("- ") },
            button("numbered", TasklaneIcons.FormatNumbered) { field.prefixLines("", numbered = true) },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.EDITOR_TOOLBAR, group, true)
        toolbar.targetComponent = target
        return toolbar.component
    }

    /**
     * Los siete llevan icono, y **tienen que llevarlo**: negrita, cursiva, código y
     * lista numerada se declararon en su día con el glifo puesto en el texto de la
     * acción —«B», «I», «</>», «1.»— dando por hecho que la barra lo pintaría. No lo
     * pinta: un `ActionToolbar` dibuja iconos, y a la acción que no trae ninguno le
     * pone `AllIcons.Toolbar.Unknown`, el círculo con tres puntos. Cuatro de los ocho
     * botones salían así hasta la `0.6.8`. Ver [TasklaneIcons].
     */
    private fun button(
        key: String,
        icon: Icon,
        run: () -> Unit,
    ) = object : DumbAwareAction(
        TasklaneBundle.message("dialog.task.format.$key"),
        TasklaneBundle.message("dialog.task.format.$key"),
        icon,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = run()
    }
}
