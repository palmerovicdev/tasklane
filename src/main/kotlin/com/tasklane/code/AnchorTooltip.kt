package com.tasklane.code

import com.intellij.openapi.util.text.StringUtil
import com.tasklane.domain.model.AnchoredTask
import com.tasklane.domain.model.TasklaneConfig
import java.time.Instant

/**
 * Lo que dice la marca del editor cuando el ratón se para encima.
 *
 * Un tooltip es **la mitad de la función**: sin él la marca sólo dice que hay algo, y
 * para saber el qué habría que ir a la ventana —justo el viaje que la marca existe para
 * ahorrar—. Por eso enseña el título entero y no un resumen, y el estado y la prioridad
 * debajo: son las dos cosas que deciden si esto se atiende ahora o se sigue leyendo.
 *
 * Se construye a mano y no con un componente Swing porque el margen del editor sólo
 * acepta texto: `GutterIconRenderer.getTooltipText` devuelve HTML y la plataforma lo
 * pinta con su propio estilo, que es el que tienen los demás tooltips del IDE.
 *
 * **Sin colores ni tamaños.** El HTML del tooltip lo pinta Swing con la hoja de estilo
 * del tema; meter un gris cableado aquí lo dejaría ilegible en cuanto alguien cambiara
 * de tema, que es exactamente lo que `JBColor` existe para evitar y aquí no se puede
 * usar.
 *
 * Kotlin puro salvo el escapado: recibe textos ya resueltos —fechas incluidas— y
 * devuelve una cadena, así que se prueba sin IDE.
 */
internal object AnchorTooltip {

    /**
     * @param formatDate cómo se escribe un vencimiento. Se inyecta por lo mismo que en
     *   `TaskTreeRenderer`: depende del locale y del reloj, y así la prueba no depende
     *   de ninguno de los dos.
     * @param hint la línea final que dice qué pasa al pulsar. Va como parámetro para
     *   que este objeto no dependa del bundle y siga siendo puro.
     */
    fun html(
        entries: List<AnchoredTask>,
        config: TasklaneConfig,
        formatDate: (Instant) -> String,
        hint: String,
        more: (Int) -> String,
    ): String {
        if (entries.isEmpty()) return ""
        val shown = entries.take(MAX)
        val blocks = shown.map { entry -> block(entry, config, formatDate) }.toMutableList()
        // Un fichero con veinte tareas en la misma línea existe, y un tooltip de veinte
        // bloques tapa la pantalla en vez de informar. Se dice cuántas quedan y se abre
        // la ventana para verlas: ahí es donde caben.
        if (entries.size > shown.size) blocks += escape(more(entries.size - shown.size))
        blocks += escape(hint)
        return blocks.joinToString(SEPARATOR, prefix = "<html><body>", postfix = "</body></html>")
    }

    private fun block(entry: AnchoredTask, config: TasklaneConfig, formatDate: (Instant) -> String): String {
        val task = entry.task
        val title = task.title.ifBlank { entry.anchor.label }
        val meta = buildList {
            add(config.stateOrDefault(task.stateId).name)
            add(config.priorityOrDefault(task.priorityId).name)
            task.dueDate?.let { add(formatDate(it)) }
            task.tags.forEach { add("#$it") }
        }
        return "<b>${escape(title)}</b><br>${escape(meta.joinToString(" · "))}"
    }

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    /** Cuántas tareas caben antes de que el tooltip deje de ser un tooltip. */
    private const val MAX = 4

    private const val SEPARATOR = "<br><br>"
}
