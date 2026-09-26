package com.tasklane.domain.model

/**
 * El punto del código del que habla una tarea: «esto es del `AuthService.kt:42`».
 *
 * Es lo que separa una lista de tareas *dentro* del IDE de una lista de tareas *del*
 * IDE. Hasta ahora las tareas vivían junto al código pero no apuntaban a él, así que
 * volver a «¿dónde era esto?» era trabajo del que escribió la nota.
 *
 * **La ruta es relativa a la raíz del proyecto**, con `/` siempre y sea cual sea el
 * sistema. Por lo mismo que las claves de repositorio: mover el proyecto entero no
 * puede romper las anclas. Un fichero de fuera del proyecto guarda su ruta absoluta —
 * es lo único honesto que se puede guardar— y deja de ser portable, que es justo lo
 * que ya era.
 *
 * Y **no es relativa al repositorio de la tarea**: una tarea de `backend` puede
 * apuntar a un fichero de `frontend`, y obligar a que no fuera así convertiría una
 * anotación en una regla.
 */
data class CodeAnchor(
    val path: String,
    /**
     * Línea **0-based**, como las cuenta el editor y como las pide
     * `OpenFileDescriptor`. Se enseñan +1, como las cuenta la gente: ver [label].
     */
    val line: Int,
    /**
     * Columna **0-based** dentro de la línea: dónde estaba el cursor exactamente.
     *
     * La línea dice de qué habla la nota; la columna dice **de qué parte de la
     * línea**, y en una línea de código eso rara vez es lo mismo —`cache.get(key)
     * ?: load(key)` son dos cosas distintas en el mismo sitio—. Es además lo que
     * permite dibujar la marca dentro del texto y no sólo en el margen: ver
     * `com.tasklane.code.AnchorMarkers`.
     *
     * Es **aproximada por definición**, igual que [line]: la línea se reencuentra por
     * [text] cuando el fichero cambia, pero dentro de ella la columna es la que se
     * guardó. Si la línea se reescribió, la columna se acota a lo que haya; apuntar
     * al principio de la línea buena es mejor que a la columna exacta de otra.
     */
    val column: Int = 0,
    /**
     * El texto de la línea cuando se ancló, recortado. No es decoración: es lo que
     * permite reencontrarla cuando el fichero ha cambiado por encima. Ver
     * [AnchorResolver]. En un rango es el de la **primera** línea.
     */
    val text: String = "",
    /**
     * Cuántas líneas **más** abarca, después de [line] (2.15.0). Cero es el ancla de
     * siempre, una línea; `span = 16` sobre la línea 41 es el bloque de la 42 a la 58
     * como las cuenta la gente.
     *
     * Un largo y no una línea final a propósito: lo que se reencuentra cuando el fichero
     * cambia es la primera línea —por su [text]—, y el bloque se va con ella entero.
     * Con una línea final guardada, un import añadido arriba movería el principio y
     * dejaría el final donde estaba, que es otro bloque. Es aproximado por lo mismo que
     * [line]: si alguien escribe **dentro** del bloque, el final se queda corto.
     */
    val span: Int = 0,
) {
    val fileName: String get() = path.substringAfterLast('/')

    /** La última línea del bloque, 0-based. En un ancla de una línea, [line]. */
    val endLine: Int get() = line + span

    /** Si abarca más de una línea. */
    val isRange: Boolean get() = span > 0

    /**
     * Lo que se pinta en la tarjeta. Sin la columna a propósito: `Auth.kt:42` es
     * como se nombra un sitio en el código en cualquier conversación, y `Auth.kt:42:17`
     * es como lo nombra un compilador. La columna ya hace su trabajo al navegar y al
     * colocar la marca; en una ficha sólo añadiría ruido. Un rango sale `Auth.kt:42-58`.
     */
    val label: String get() = "$fileName:${lines()}"

    /**
     * La ruta entera con sus líneas —`src/auth/Auth.kt:42-58`—, que es lo que se escribe
     * en el diálogo, lo que devuelve el agente por MCP y lo que sale en el CSV. Se vuelve
     * a leer con [AnchorReference.parse], así que lo que se enseña se puede pegar.
     */
    val reference: String get() = "$path:${lines()}"

    private fun lines(): String = if (isRange) "${line + 1}-${endLine + 1}" else "${line + 1}"

    companion object {
        /**
         * Tope del texto guardado. Una línea de código larguísima no ayuda a
         * reencontrarla más que sus primeros caracteres, y el fichero de tareas se
         * reescribe entero en cada guardado.
         */
        const val MAX_TEXT = 200

        /**
         * La ruta que se guarda para el fichero que está en [absolute]: relativa a [base]
         * si cae dentro, tal cual si no. Es la correspondencia de `CodeAnchors.pathOf`,
         * pero sobre texto: los eventos del sistema de ficheros traen rutas y no
         * ficheros, y el de un borrado ya no existe cuando se atiende (2.13.0).
         */
        fun pathOf(base: String?, absolute: String): String {
            val root = base?.trimEnd('/') ?: return absolute
            return if (absolute.startsWith("$root/")) absolute.substring(root.length + 1) else absolute
        }

        /** Normaliza lo que venga del editor: separadores, posición negativa y texto largo. */
        fun of(path: String, line: Int, column: Int = 0, text: String = "", span: Int = 0): CodeAnchor = CodeAnchor(
            path = path.replace('\\', '/'),
            line = line.coerceAtLeast(0),
            column = column.coerceAtLeast(0),
            text = text.trim().take(MAX_TEXT),
            span = span.coerceAtLeast(0),
        )
    }
}
