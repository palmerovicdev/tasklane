package com.tasklane.domain.model

import java.util.Locale

/**
 * El color de una etiqueta (P30), que es opcional: sin él, la ficha sale gris como
 * siempre.
 *
 * Dos valores, claro y oscuro, por lo mismo que los de una prioridad: ningún cálculo
 * automático acierta con los dos contrastes a la vez. Es del **proyecto**, como los de
 * las prioridades —va a `tasklane.xml`—, y vale para la etiqueta en todos sus
 * repositorios: `#api` es la misma palabra en `backend` y en `frontend`.
 */
data class TagColor(val light: Int, val dark: Int) {

    companion object {
        /**
         * El nombre con el que se guarda: sin almohadilla y **sin mirar mayúsculas**, la
         * regla de `TagParser` —`api` y `API` son la misma etiqueta para quien las lee—.
         * Así el color no se pierde porque una tarea escribiera la etiqueta de otra forma.
         */
        fun key(tag: String): String = tag.trim().removePrefix("#").lowercase(Locale.ROOT)
    }
}
