package com.tasklane.ui.common

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * [IconLoader] resuelve solo la variante `_dark` y el escalado HiDPI, así que
 * basta con referenciar el fichero base.
 */
internal object TasklaneIcons {

    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/tasklane.svg", TasklaneIcons::class.java)

    /**
     * El del widget de la barra de estado (2.14.0): el mismo dibujo que la tool window en
     * la rejilla de 16 de la barra. El de la ventana es de 13, y escalarlo emborrona el
     * trazo.
     */
    @JvmField
    val StatusBar: Icon = IconLoader.getIcon("/icons/status.svg", TasklaneIcons::class.java)

    /**
     * El vencimiento de una fila. Es propio porque `AllIcons` no trae calendario ni
     * reloj, y sin él la fila tenía que llevar la palabra «Due» delante de la fecha
     * para distinguirla de la de modificación, que va justo al lado.
     */
    @JvmField
    val Calendar: Icon = IconLoader.getIcon("/icons/calendar.svg", TasklaneIcons::class.java)

    /**
     * La barra de formato del diálogo, entera. Ver [com.tasklane.ui.editor.MarkdownToolbar].
     *
     * Son siete y **van juntos**: media fila con iconos de la plataforma y media con
     * los propios se notaba a la primera —`FileTypes.Image` es azul, `Actions.Checked`
     * es un visto suelto sin lista— y una barra de formato tiene que leerse como un
     * único conjunto. Mismo trazo, misma rejilla de 16 y los mismos dos grises que el
     * resto de iconos del plugin. Se generan con `docs/tools/gen_format_icons.py`, que
     * escribe las dos variantes desde un solo cuerpo para que no puedan divergir.
     */
    @JvmField
    val FormatBold: Icon = format("bold")

    @JvmField
    val FormatItalic: Icon = format("italic")

    @JvmField
    val FormatCode: Icon = format("code")

    @JvmField
    val FormatLink: Icon = format("link")

    @JvmField
    val FormatImage: Icon = format("image")

    @JvmField
    val FormatBullet: Icon = format("bullet")

    @JvmField
    val FormatNumbered: Icon = format("numbered")

    @JvmField
    val FormatChecklist: Icon = format("checklist")

    /**
     * El desplegable de «agrupar por» de la barra.
     *
     * Propio y no `AllIcons.Actions.GroupBy` porque ese, en la interfaz nueva de la
     * plataforma, se dibuja como un **ojo**: en una barra de herramientas eso se lee
     * como «vista previa», así que el botón estaba ahí y nadie lo reconocía. Se
     * genera con el mismo script y la misma rejilla que la barra de formato.
     */
    @JvmField
    val GroupBy: Icon = IconLoader.getIcon("/icons/group_by.svg", TasklaneIcons::class.java)

    /**
     * El tablero (2.17.0): tres columnas de alto distinto. Va en *Open Board* y en su
     * pestaña del editor. `AllIcons` no trae uno: lo más cercano son las divisiones del
     * editor, que en una barra se leen como «partir la ventana».
     */
    @JvmField
    val Board: Icon = IconLoader.getIcon("/icons/board.svg", TasklaneIcons::class.java)

    private fun format(name: String): Icon =
        IconLoader.getIcon("/icons/format_$name.svg", TasklaneIcons::class.java)
}
