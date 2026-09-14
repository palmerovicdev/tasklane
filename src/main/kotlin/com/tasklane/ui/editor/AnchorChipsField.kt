package com.tasklane.ui.editor

import com.intellij.icons.AllIcons
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.CodeAnchor
import javax.swing.JPanel

/**
 * Las anclas de código de una tarea, como fichas que sólo se pueden quitar.
 *
 * No hay forma de **añadir** una desde aquí, y no es un olvido: un ancla es un sitio
 * del editor, y el único momento en que se sabe cuál es, es cuando se crea la tarea
 * desde él. Escribirla a mano sería teclear una ruta y un número de línea, que es
 * exactamente lo que la función viene a evitar.
 *
 * El campo entero **desaparece cuando no hay ninguna**: la mayoría de las tareas no
 * salen del editor, y una fila vacía con su etiqueta las haría pagar un hueco por una
 * función que no usan.
 */
internal class AnchorChipsField(
    initial: List<CodeAnchor>,
    /** Se avisa al quitar una: la etiqueta del diálogo se esconde con la última. */
    private val onChange: () -> Unit = {},
) : JPanel(ChipsLayout()) {

    private var chips: List<CodeAnchor> = initial.distinct()

    val anchors: List<CodeAnchor> get() = chips

    /** Si hay algo que enseñar. Lo mira el diálogo para esconder también la etiqueta. */
    val isEmpty: Boolean get() = chips.isEmpty()

    init {
        isOpaque = false
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        chips.forEach { anchor -> add(chipFor(anchor)) }
        isVisible = chips.isNotEmpty()
        revalidate()
        repaint()
    }

    /**
     * El texto es `Fichero.kt:42` y la ruta entera va al tooltip: en el diálogo hay
     * sitio para el nombre, no para `src/main/kotlin/com/…/AuthService.kt`, y el
     * nombre con su línea es lo que identifica el sitio para quien lo acaba de anclar.
     */
    private fun chipFor(anchor: CodeAnchor) = Chip(
        text = anchor.label,
        icon = AllIcons.FileTypes.Any_type,
        tooltip = anchor.path,
        removeTooltip = TasklaneBundle.message("dialog.task.code.remove", anchor.label),
    ) {
        chips = chips - anchor
        rebuild()
        onChange()
    }
}
