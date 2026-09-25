package com.tasklane.domain.text

/**
 * Los bloques de código con valla de un cuerpo: lo que va entre dos líneas ```` ``` ````.
 *
 * Hasta la 2.8.0 la tarjeta sólo entendía el código **en línea**, `así`. Un trozo de
 * varias líneas pegado entre vallas salía como párrafos sueltos, con las vallas a la
 * vista y el Markdown de dentro interpretado: un `*` de un puntero se comía media línea
 * como cursiva. Dentro de una valla nada es Markdown, y cada línea se pinta tal cual.
 *
 * Las reglas son las de CommonMark que importan aquí:
 * - abre una línea que empieza —sangrada o no— por tres o más `` ` ``, y lo que siga es
 *   el lenguaje; si ese resto lleva otra `` ` ``, no es una valla sino código en línea
 *   (```` ```x``` ````);
 * - cierra la primera línea que sea **sólo** una valla al menos igual de larga;
 * - una valla sin cerrar llega hasta el final del cuerpo. Es lo que pasa mientras se
 *   escribe, y cortar el bloque ahí haría saltar la tarjeta en cada tecla.
 *
 * Kotlin puro y **línea a línea**, con estado: quien recorre el cuerpo ya lo está
 * partiendo en líneas —ver `Task.detailBlocks`— y así no hace falta una segunda pasada.
 */
class CodeFence {

    /** Qué es una línea para quien la pinta. */
    enum class Role {
        /** Texto normal, con su Markdown en línea. */
        PROSE,

        /** Una línea de valla, la que abre o la que cierra. No se pinta. */
        FENCE,

        /** Una línea de dentro de un bloque. Se pinta literal. */
        CODE,
    }

    /** Cuántas `` ` `` lleva la valla abierta; 0 == fuera de un bloque. */
    private var open = 0

    /** El lenguaje de la valla abierta, o vacío. Sólo tiene sentido dentro de un bloque. */
    var language: String = ""
        private set

    val inBlock: Boolean get() = open > 0

    /** El papel de la siguiente línea, que hay que pasar en orden. */
    fun next(line: String): Role {
        val trimmed = line.trim()
        val ticks = trimmed.takeWhile { it == TICK }.length
        if (open > 0) {
            if (ticks >= open && trimmed.length == ticks) {
                open = 0
                language = ""
                return Role.FENCE
            }
            return Role.CODE
        }
        if (ticks < MIN) return Role.PROSE
        val info = trimmed.substring(ticks)
        if (TICK in info) return Role.PROSE
        open = ticks
        language = info.trim()
        return Role.FENCE
    }

    companion object {
        private const val TICK = '`'
        private const val MIN = 3

        /** El papel de cada línea de [lines], en orden. */
        fun roles(lines: List<String>): List<Role> {
            val fence = CodeFence()
            return lines.map(fence::next)
        }

        /**
         * Las líneas de un bloque sin la sangría que tienen todas en común y con los
         * tabuladores hechos espacios. La sangría común es la del sitio del Markdown
         * donde se pegó —una lista, un párrafo sangrado—, no la del código, y en una
         * tarjeta estrecha cada columna cuenta.
         */
        fun dedent(lines: List<String>): List<String> {
            val expanded = lines.map { it.replace("\t", TAB).trimEnd() }
            val common = expanded.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' }.length } ?: 0
            return expanded.map { if (it.length >= common) it.substring(common) else "" }
        }

        private const val TAB = "    "
    }
}
