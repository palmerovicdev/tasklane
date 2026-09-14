package com.tasklane.domain.model

/**
 * Cómo se ve en el editor que una línea tiene una tarea colgada.
 *
 * Son dos formas de decir lo mismo con costes distintos, y por eso se elige en vez de
 * decidirse aquí: [GUTTER] no toca ni un píxel del código —vive en el margen, donde ya
 * viven los puntos de interrupción y los botones de *Run*—, mientras que [INLINE] es la
 * única que puede enseñar **en qué parte de la línea** estaba la nota, a cambio de
 * empujar el texto a la derecha. Quien trabaja con el margen lleno quiere lo segundo;
 * quien no soporta que el código se mueva, lo primero.
 *
 * El ajuste es de la persona y no del proyecto: no cambia lo que se guarda ni lo que ve
 * el equipo, sólo cómo se pinta esta máquina. Por eso va a `workspace.xml` y no a
 * `tasklane.xml`. Ver `TasklaneWorkspaceService`.
 */
enum class AnchorMarkerStyle {
    /** Un icono en el margen, a la altura de la línea. Lo de fábrica. */
    GUTTER,

    /** Una marca dentro del texto, en la columna exacta que se ancló. */
    INLINE,

    /** Nada en el editor. Las anclas siguen estando en la tarea y en su ficha. */
    OFF,
}
