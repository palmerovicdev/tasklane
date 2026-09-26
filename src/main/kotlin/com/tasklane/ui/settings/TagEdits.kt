package com.tasklane.ui.settings

import com.tasklane.domain.model.TagColor
import com.tasklane.domain.model.TagCount
import com.tasklane.domain.text.TagParser

/**
 * Copia **mutable** de una etiqueta del repositorio activo, para la tabla de los ajustes
 * (P30). Como [StateRow]: se edita esto y se convierte una sola vez, al pulsar Apply.
 *
 * [original] es `val` por lo mismo que el id de un estado: es la etiqueta tal cual está
 * hoy en las tareas, y es lo que se renombra. Una etiqueta no tiene más identidad que su
 * nombre, así que sin él no habría forma de saber qué había que cambiar.
 */
internal class TagRow(
    val original: String,
    var name: String,
    val tasks: Int,
    var color: TagColor?,
    /** Se quitó el color a mano: distinto de «no tenía», que no borra el de nadie. */
    var colorCleared: Boolean = false,
) {
    companion object {
        fun of(count: TagCount, color: TagColor?) = TagRow(count.tag, count.tag, count.tasks, color)
    }
}

/**
 * Lo que la tabla de etiquetas le hace al proyecto al aplicar, sin Swing ni base: qué
 * etiquetas cambian por cuáles en las tareas y cómo quedan los colores.
 */
internal object TagEdits {

    /** Por qué una fila no se puede aplicar. */
    enum class Problem { BLANK, SEPARATOR }

    /**
     * El nombre que se guardará, o `null` si no es una etiqueta. Pasa por [TagParser]
     * como lo que se escribe en el diálogo: sin la almohadilla de delante, y sin espacios
     * ni comas, que ahí separarían dos etiquetas.
     */
    fun clean(name: String): String? = TagParser.parse(name).singleOrNull()

    fun problemOf(row: TagRow): Problem? = when {
        row.name.isBlank() || TagParser.parse(row.name).isEmpty() -> Problem.BLANK
        clean(row.name) == null -> Problem.SEPARATOR
        else -> null
    }

    /**
     * De cada etiqueta que cambia a la que la sustituye, o a `null` si se quita. Lo que no
     * cambia no está. Es lo que se le pasa a `TaskService.retag`.
     *
     * [removed] son las filas borradas, de su [TagRow.original] al de la fila a la que se
     * mandaron sus tareas, o a `null` para sólo quitarla. Se sigue la **cadena**, como con
     * los estados: borrar `a` hacia `b` y después `b` hacia `c` deja en `c` lo de las dos,
     * y en `c` con el nombre que lleve al aplicar. Un ciclo, que la tabla no deja hacer,
     * acaba quitando la etiqueta en vez de dar vueltas.
     */
    fun renames(rows: List<TagRow>, removed: Map<String, String?>): Map<String, String?> {
        val byOriginal = rows.associateBy { it.original }
        fun destination(original: String): String? {
            var at = original
            val seen = mutableSetOf(at)
            while (true) {
                if (at !in removed) return byOriginal[at]?.let { clean(it.name) ?: it.original }
                val next = removed[at] ?: return null
                if (!seen.add(next)) return null
                at = next
            }
        }

        val out = LinkedHashMap<String, String?>()
        for (original in rows.map { it.original } + removed.keys) {
            val to = destination(original)
            if (to != original) out[original] = to
        }
        return out
    }

    /**
     * Los colores del proyecto después de aplicar.
     *
     * - El color va con el nombre: una fila que se renombra se lo lleva, y si se fusiona
     *   en otra que ya tenía el suyo, gana el de la otra —es la que se queda—.
     * - Una fila sin color no le quita el suyo a nadie: fusionar `apis`, gris, en `api`,
     *   azul, deja `api` azul. Sólo lo quita [TagRow.colorCleared].
     * - La etiqueta que se renombra o se borra deja atrás su color **si ya no se usa**:
     *   [keep] son las que siguen en otros repositorios, donde el color sigue haciendo
     *   falta. `null` es no quitar ninguno, que es lo que se pregunta mientras la página
     *   está abierta —saber qué hay fuera cuesta una consulta—.
     */
    fun colors(
        base: Map<String, TagColor>,
        rows: List<TagRow>,
        renames: Map<String, String?>,
        keep: Set<String>?,
    ): Map<String, TagColor> {
        val out = LinkedHashMap(base)
        if (keep != null) {
            val kept = keep.mapTo(HashSet(), TagColor::key)
            rows.mapNotNullTo(kept) { row -> clean(row.name)?.let(TagColor::key) }
            for (from in renames.keys) {
                val key = TagColor.key(from)
                if (key !in kept) out -= key
            }
        }
        // Las renombradas primero: si dos filas acaban en el mismo nombre, manda la que
        // ya se llamaba así.
        val (renamed, same) = rows.partition { clean(it.name) != it.original }
        for (row in renamed + same) {
            val key = clean(row.name)?.let(TagColor::key) ?: continue
            val color = row.color
            when {
                color != null -> out[key] = color
                row.colorCleared -> out -= key
            }
        }
        return out
    }
}
