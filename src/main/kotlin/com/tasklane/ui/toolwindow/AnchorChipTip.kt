package com.tasklane.ui.toolwindow

import com.intellij.openapi.util.text.StringUtil
import com.tasklane.domain.model.AnchorSnippet
import com.tasklane.domain.model.CodeAnchor

/**
 * El tooltip de la ficha de un ancla en la tarjeta (2.15.0): la ruta entera con sus líneas
 * y, debajo, **el código** que hay ahí hoy.
 *
 * La ficha dice `Auth.kt:42`, y hasta ahora el tooltip sólo añadía la ruta. Para saber de
 * qué trozo hablaba la nota había que pulsarla e irse al editor, que es justo lo que no
 * se quiere cuando se está repasando la lista. Con el código en el tooltip, pasar el
 * ratón por la ficha ya contesta.
 *
 * Kotlin puro salvo el escapado, como `AnchorTooltip`: el fragmento llega ya leído —ver
 * [CardSnippets]— y el texto de «quedan N líneas», ya resuelto.
 */
internal object AnchorChipTip {

    /** Cuántas líneas caben antes de que el tooltip tape la lista de la que habla. */
    const val MAX_LINES = 12

    /** Y cuántos caracteres de cada una: el resto de una línea larga no explica nada más. */
    const val MAX_COLUMNS = 100

    /**
     * Sin [snippet] —se está leyendo, o el fichero no tiene texto— es la ruta a secas, que
     * es lo que decía antes.
     */
    fun html(anchor: CodeAnchor, snippet: AnchorSnippet?, more: (Int) -> String): String {
        val lines = snippet?.lines?.takeIf { it.isNotEmpty() } ?: return anchor.reference
        val shown = lines.take(MAX_LINES)
        val left = snippet.total - shown.size
        return buildString {
            append("<html><b>").append(StringUtil.escapeXmlEntities(anchor.reference)).append("</b>")
            append("<pre>")
            shown.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                val cut = if (line.length > MAX_COLUMNS) line.take(MAX_COLUMNS - 1) + "…" else line
                append(StringUtil.escapeXmlEntities(cut))
            }
            append("</pre>")
            if (left > 0) append(StringUtil.escapeXmlEntities(more(left)))
            append("</html>")
        }
    }
}
