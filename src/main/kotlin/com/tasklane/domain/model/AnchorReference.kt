package com.tasklane.domain.model

/**
 * Un ancla **escrita a mano**: `plans/deploy.md:28`, como la nombra cualquier
 * compilador, cualquier traza y cualquier agente que acaba de escribir un plan.
 *
 * Hasta la 2.8.0 un ancla sólo nacía del editor, con el cursor encima. Eso deja fuera
 * justo el caso en que el sitio ya viene escrito: una línea que alguien pega en un
 * chat, la salida de un test, el fichero que un agente dice haber tocado. Teclear la
 * ruta ahí no es trabajo extra, es copiar lo que ya se tiene.
 *
 * Aquí sólo se **lee el texto**. Si el fichero existe, y qué hay en esa línea, lo
 * decide `com.tasklane.code.CodeAnchors`, que es quien habla con la plataforma.
 */
data class AnchorReference(
    /** Con `/` siempre y sin `./` delante; relativa o absoluta, tal cual se escribió. */
    val path: String,
    /** **0-based**, como [CodeAnchor.line]. Se escribe 1-based, como se cuentan. */
    val line: Int,
    /** **0-based**, como [CodeAnchor.column]. Sin columna escrita, el principio de la línea. */
    val column: Int,
    /** Si se escribió una línea. Sin ella el ancla apunta al fichero entero, por su principio. */
    val hasLine: Boolean,
) {
    companion object {

        /**
         * `ruta`, `ruta:línea` o `ruta:línea:columna`, y también `ruta#L28` —lo que se
         * copia de un enlace de GitHub o GitLab— y `ruta(28)` —lo que escriben algunas
         * trazas—. Se toleran las comillas o comillas invertidas de alrededor, que
         * vienen pegadas en cuanto la ruta se copia de un Markdown.
         *
         * Los números se leen **desde el final**: `C:\src\Main.kt:12` es una ruta de
         * Windows con su línea, no un fichero `C` en la línea `\src…`.
         *
         * `null` == no hay ruta. Una línea `0` se toma como la primera: nadie escribe
         * la línea cero a propósito, y rechazarla por eso sería pedantería.
         */
        fun parse(input: String): AnchorReference? {
            val text = input.trim().trim('`', '"', '\'').trim()
            if (text.isEmpty()) return null
            val match = HASH.matchEntire(text) ?: PAREN.matchEntire(text) ?: COLON.matchEntire(text) ?: return null

            val path = normalize(match.groupValues[1])
            if (path.isEmpty()) return null
            val line = match.groupValues.getOrNull(2)?.toIntOrNull()
            val column = match.groupValues.getOrNull(3)?.toIntOrNull()
            return AnchorReference(
                path = path,
                line = ((line ?: 1) - 1).coerceAtLeast(0),
                column = ((column ?: 1) - 1).coerceAtLeast(0),
                hasLine = line != null,
            )
        }

        private fun normalize(path: String): String {
            var result = path.trim().replace('\\', '/')
            while (result.startsWith("./")) result = result.substring(2)
            return result
        }

        /** `ruta#L28`, `ruta#L28C5` o un rango `ruta#L28-L40`, del que vale el principio. */
        private val HASH = Regex("""^(.+?)#L(\d+)(?:C(\d+))?(?:-L?\d+(?:C\d+)?)?$""")

        /** `ruta(28)` o `ruta(28,5)`. */
        private val PAREN = Regex("""^(.+?)\((\d+)(?:,\s*(\d+))?\)$""")

        /** `ruta`, `ruta:28` o `ruta:28:5`. El `.+?` perezoso deja los números al final. */
        private val COLON = Regex("""^(.+?)(?::(\d+)(?::(\d+))?)?:?$""")
    }
}
