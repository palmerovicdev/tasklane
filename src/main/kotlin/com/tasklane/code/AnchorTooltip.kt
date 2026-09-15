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
 * **Las capturas también salen.** Media tarea es una imagen pegada —el error que se vio,
 * el diseño que hay que copiar—, y un tooltip que sólo dice el título obliga a abrir la
 * ventana justo en el caso en que menos falta hace leer: mirar la captura ya es la
 * respuesta. Van como `<img>` con el ancho y el alto **ya calculados** por quien llama:
 * sin ellos Swing mide el bloque cuando la imagen termina de cargar, que es después de
 * que el globo haya decidido su tamaño, y la vista previa sale recortada.
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
     * Una captura ya resuelta a lo que el HTML necesita: de dónde se lee y cuánto ocupa.
     *
     * [src] es una URL absoluta —`file:/…`—, porque el HTML de un `JLabel` no tiene
     * documento base contra el que resolver una ruta relativa. El tamaño viene dado y no
     * se deduce aquí: leer un PNG para medirlo es IO, y esto es dominio.
     */
    data class Preview(val src: String, val width: Int, val height: Int)

    /**
     * @param formatDate cómo se escribe un vencimiento. Se inyecta por lo mismo que en
     *   `TaskTreeRenderer`: depende del locale y del reloj, y así la prueba no depende
     *   de ninguno de los dos.
     * @param hint la línea final que dice qué pasa al pulsar. Va como parámetro para
     *   que este objeto no dependa del bundle y siga siendo puro.
     * @param previews las capturas de cada tarea, ya leídas y medidas. Por defecto
     *   ninguna: el tooltip del margen se construye en el EDT al instalar las marcas y
     *   ahí no se puede ir al disco. Ver [com.tasklane.code.AnchorMarkers].
     */
    fun html(
        entries: List<AnchoredTask>,
        config: TasklaneConfig,
        formatDate: (Instant) -> String,
        hint: String,
        more: (Int) -> String,
        previews: (AnchoredTask) -> List<Preview> = { emptyList() },
    ): String {
        if (entries.isEmpty()) return ""
        val shown = entries.take(MAX)
        val blocks = shown.map { entry -> block(entry, config, formatDate, previews(entry)) }.toMutableList()
        // Un fichero con veinte tareas en la misma línea existe, y un tooltip de veinte
        // bloques tapa la pantalla en vez de informar. Se dice cuántas quedan y se abre
        // la ventana para verlas: ahí es donde caben.
        if (entries.size > shown.size) blocks += escape(more(entries.size - shown.size))
        blocks += escape(hint)
        return blocks.joinToString(SEPARATOR, prefix = "<html><body>", postfix = "</body></html>")
    }

    private fun block(
        entry: AnchoredTask,
        config: TasklaneConfig,
        formatDate: (Instant) -> String,
        previews: List<Preview>,
    ): String {
        val task = entry.task
        val title = task.title.ifBlank { entry.anchor.label }
        val meta = buildList {
            add(config.stateOrDefault(task.stateId).name)
            add(config.priorityOrDefault(task.priorityId).name)
            task.dueDate?.let { add(formatDate(it)) }
            task.tags.forEach { add("#$it") }
        }
        val shots = previews.joinToString("") { "<br>${img(it)}" }
        return "<b>${escape(title)}</b><br>${escape(meta.joinToString(" · "))}$shots"
    }

    /**
     * La ruta se escapa como cualquier otro texto: un fichero puede llamarse `a&b.png`,
     * y sin escapar eso rompe el atributo. El analizador de HTML lo deshace al leerlo.
     */
    private fun img(preview: Preview): String =
        "<img src=\"${escape(preview.src)}\" width=\"${preview.width}\" height=\"${preview.height}\">"

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    /** Cuántas tareas caben antes de que el tooltip deje de ser un tooltip. */
    const val MAX = 4

    private const val SEPARATOR = "<br><br>"
}
