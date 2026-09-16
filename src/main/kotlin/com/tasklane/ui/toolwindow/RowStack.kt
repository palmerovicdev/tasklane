package com.tasklane.ui.toolwindow

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * Apila de arriba abajo las líneas visibles de una fila, cada una con su altura
 * natural y todo el ancho disponible.
 *
 * Sustituye al `BorderLayout` de dos huecos que tenía la fila cuando sólo podía
 * tener dos líneas. Con el título envuelto en hasta tres, la descripción y la línea
 * de metadatos, los huecos con nombre se acabaron; y `BoxLayout` no sirve porque
 * estira los componentes hasta su `maximumSize`, que en un `SimpleColoredComponent`
 * es ilimitado.
 *
 * Los invisibles no ocupan: es lo que permite que una tarea sin descripción ni
 * etiquetas siga midiendo exactamente una línea.
 *
 * **La última línea no se cae.** Si la fila resulta ser más corta de lo que pide su
 * contenido, la de abajo —la de distintivos: prioridad, vencimiento, etiquetas— se queda
 * con su sitio pegada al fondo, y lo que se va es el renglón de texto que no cabe. La
 * altura de una fila la mide el árbol una vez y la guarda, mientras que el texto se
 * envuelve contra el ancho de cada momento: basta con que las dos medidas no coincidan
 * para que sobre una línea, y la que sobra es siempre la última. Se prefiere perder un
 * renglón de título —que ya venía recortado con puntos suspensivos, y que además se
 * lee entero desplegando la tarjeta— antes que la línea que dice de qué va la tarea,
 * que desaparecía sin dejar ninguna pista de por qué.
 *
 * **Y la que sabe encogerse, encoge en vez de caerse** (2.6.0). Para un renglón de
 * texto, irse es barato: son doce píxeles y el mismo texto está en el diálogo. Para una
 * vista previa no: es *todo* lo que la tarjeta desplegada tenía que enseñar, y perderla
 * por un píxel de desacuerdo entre la medida y el pintado dejaba una tarjeta alta y
 * vacía —la imagen aparecía y desaparecía sin que nada lo explicara, según hubiera
 * barra de desplazamiento cuando al árbol le tocó medir—. Una línea con
 * `minimumSize` más bajo que su `preferredSize` —hoy sólo [CardImageView]— se queda con
 * el hueco que haya y se pinta dentro; sólo se cae si ni para eso llega. Para las
 * demás, `minimumSize == preferredSize` y no cambia nada.
 *
 * Apilarlas encima no valdría: los hijos se pintan del último al primero, así que la de
 * abajo quedaría **debajo** del texto, y el *hit testing* encontraría antes la línea de
 * arriba. Invisible e impulsable, que es de donde se venía.
 *
 * Cuando la fila mide lo que tiene que medir, nada de esto cambia un píxel.
 */
internal class RowStack : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component) = Unit

    override fun removeLayoutComponent(comp: Component) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = measure(parent)

    override fun minimumLayoutSize(parent: Container): Dimension = measure(parent)

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val width = parent.width - insets.left - insets.right
        val visible = parent.components.filter { it.isVisible }
        val heights = visible.map { it.preferredSize.height }
        val room = parent.height - insets.top - insets.bottom
        val short = heights.sum() > room

        // El hueco que se le guarda a la de abajo cuando la fila viene corta.
        val reserved = if (short) heights.last() else 0

        var y = insets.top
        for ((index, child) in visible.withIndex()) {
            val height = heights[index]
            // Hasta dónde puede llegar esta línea sin comerse el hueco de la de abajo.
            val limit = insets.top + room - reserved
            when {
                index == visible.lastIndex -> {
                    // El tope es el borde de arriba: sacarla por ahí sería mudar de
                    // sitio el mismo problema.
                    val top = if (short) (insets.top + room - height).coerceAtLeast(insets.top) else y
                    child.setBounds(insets.left, top, width, height)
                }

                !short || y + height <= limit -> {
                    child.setBounds(insets.left, y, width, height)
                    y += height
                }

                // Lo que cabría si esta línea se conformara con el hueco que queda.
                (limit - y) >= child.minimumSize.height && limit > y -> {
                    child.setBounds(insets.left, y, width, limit - y)
                    y = limit
                }

                else -> child.setBounds(0, 0, 0, 0)
            }
        }
    }

    private fun measure(parent: Container): Dimension {
        var width = 0
        var height = 0
        for (child in parent.components) {
            if (!child.isVisible) continue
            val size = child.preferredSize
            width = maxOf(width, size.width)
            height += size.height
        }
        val insets = parent.insets
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }
}
