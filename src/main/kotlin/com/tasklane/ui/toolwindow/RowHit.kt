package com.tasklane.ui.toolwindow

import java.awt.Rectangle
import javax.swing.JTree
import javax.swing.JViewport

/**
 * Dónde está cada fila **de verdad**, que no es lo que contesta `JTree`.
 *
 * `getRowForLocation` exige que el punto caiga dentro del rectángulo que el árbol
 * calcula para la fila, y ese rectángulo mide lo que mide el renderer con su tamaño
 * preferido: el texto y poco más. La tarjeta, en cambio, se pinta hasta el borde
 * visible —el árbol estira el renderer al pintarlo—, así que a la derecha del título
 * hay tarjeta pintada y ninguna fila que devolver. El síntoma era que el marcador y
 * el menú `⋮` sólo aparecían pasando el ratón por encima de las letras, y no por el
 * hueco de al lado, que es tarjeta igual.
 *
 * Aquí se resuelve la fila **por la altura** y se ensancha su rectángulo hasta donde
 * se pinta. Con eso, resolver un control o un enlace vuelve a medir sobre el mismo
 * ancho con el que se pintó, que es la única forma de que el sitio donde se ve una
 * cosa y el sitio donde responde sean el mismo.
 */

/** La fila que hay a la altura [y], mire donde mire la `x`. `-1` si no hay ninguna. */
internal fun rowAtHeight(tree: JTree, y: Int): Int {
    val row = tree.getClosestRowForLocation(0, y)
    if (row < 0) return -1
    val bounds = tree.getRowBounds(row) ?: return -1
    return if (y >= bounds.y && y < bounds.y + bounds.height) row else -1
}

/**
 * El rectángulo en el que se pinta la fila [row]: el suyo, estirado hasta el borde
 * visible. Si el árbol ya lo da así de ancho —según la versión de la plataforma— esto
 * no lo toca.
 */
internal fun paintedRowBounds(tree: JTree, row: Int): Rectangle? {
    val bounds = tree.getRowBounds(row) ?: return null
    val viewport = tree.parent as? JViewport
    val right = if (viewport != null) viewport.viewPosition.x + viewport.width else tree.width
    if (right - bounds.x > bounds.width) bounds.width = right - bounds.x
    return bounds
}
