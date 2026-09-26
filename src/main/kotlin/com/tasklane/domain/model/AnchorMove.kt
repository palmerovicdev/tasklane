package com.tasklane.domain.model

/**
 * Un fichero o un directorio que cambió de sitio, con las rutas **como las guarda**
 * [CodeAnchor]: relativas al proyecto, o absolutas fuera de él.
 *
 * Existe porque [CodeAnchor] guarda la ruta como texto (2.13.0). [AnchorResolver] ya
 * reencontraba la línea dentro del fichero cuando el código se movía, pero renombrar el
 * fichero —o el paquete entero— dejaba el ancla apuntando a un sitio que ya no existía, y
 * nadie lo decía hasta que alguien la pulsaba.
 *
 * Un directorio se mueve **con todo lo que tiene dentro**: `src/auth → src/security`
 * lleva `src/auth/Login.kt` a `src/security/Login.kt`. Por eso se compara por
 * componentes y no por prefijo de texto: `src/auth` no es el padre de `src/authz/Api.kt`.
 *
 * Kotlin puro, como [AnchorResolver]: la plataforma sólo traduce sus eventos a esto.
 */
data class AnchorMove(val from: String, val to: String) {

    /** Dónde queda [path] después de este movimiento, o `null` si no le afecta. */
    fun apply(path: String): String? = when {
        path == from -> to
        path.startsWith("$from/") -> to + path.substring(from.length)
        else -> null
    }

    companion object {

        /**
         * Dónde queda [path] después de **todos** los movimientos, en su orden.
         *
         * En orden y no el primero que case: una refactorización mueve varias cosas de
         * una vez, y renombrar un fichero y después su directorio son dos pasos que se
         * encadenan —`a/X.kt → a/Y.kt`, luego `a → b`— hasta `b/Y.kt`.
         */
        fun rewrite(path: String, moves: List<AnchorMove>): String =
            moves.fold(path) { current, move -> move.apply(current) ?: current }
    }
}
