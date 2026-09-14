package com.tasklane.ui.common

import com.intellij.ui.JBColor
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import com.tasklane.domain.model.TaskPriority
import javax.swing.Icon

/**
 * El punto de color con el que se reconoce una prioridad.
 *
 * Vive fuera de quien pinta la fila porque desde la `1.3.0` la prioridad se cambia
 * desde dos menús además de desde el diálogo, y en los tres sitios tiene que ser el
 * mismo punto: el color es lo único que identifica una prioridad de un vistazo —el
 * nombre lo pone el usuario y puede ser cualquier cosa—, así que si el distintivo de
 * la tarjeta y el del menú no coincidieran, elegir sería adivinar.
 *
 * El color se arma en un [JBColor] con los dos valores del modelo, que es lo que deja
 * que el tema resuelva cuál toca.
 */
internal object PriorityDot {

    /** El de la tarjeta: pequeño, porque va dentro de una línea de texto menuda. */
    fun chip(priority: TaskPriority): Icon = ColorIcon(JBUI.scale(CHIP), color(priority))

    /**
     * El de un menú: un cuadro de color dentro del hueco de icono de 16 que reserva
     * la plataforma. Con el mismo tamaño que el de la tarjeta quedaría flotando en
     * una esquina del hueco y la columna de colores dejaría de leerse como columna.
     */
    fun menu(priority: TaskPriority): Icon =
        ColorIcon(JBUI.scale(MENU), JBUI.scale(MENU_COLOR), color(priority), true)

    private fun color(priority: TaskPriority) = JBColor(priority.colorLight, priority.colorDark)

    private const val CHIP = 8
    private const val MENU = 16
    private const val MENU_COLOR = 10
}
