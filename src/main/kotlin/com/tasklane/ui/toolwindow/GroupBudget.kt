package com.tasklane.ui.toolwindow

import com.tasklane.domain.model.GroupKey
import com.tasklane.paging.GroupOutline
import com.tasklane.paging.TaskPager

/**
 * Qué grupos salen abiertos cuando la lista se pinta.
 *
 * Es la regla de la Fase 2 que más se nota, así que está aquí y no dentro de
 * `TasklanePanel`: es una decisión, se puede leer sola y tiene su test.
 *
 * El problema que resuelve es el §1.4 del plan de escala. Hasta ahora el panel
 * desplegaba **todos** los grupos en cada repintado, y con `rowHeight = 0` desplegar es
 * medir: `JTree` obtiene la altura de cada fila invocando al renderer, ~0,064 ms cada
 * una. El presupuesto del hilo de interfaz son 16 ms, o sea unas 250 filas, y eso es
 * todo lo que hay — aunque la lista tenga un millón.
 *
 * Así que se reparte ese presupuesto de arriba abajo: los primeros grupos se abren
 * hasta agotarlo y los demás esperan a que alguien los pida. Nadie lo echa de menos,
 * porque nadie lee el fondo de una lista antes de haber leído el principio.
 *
 * Lo que el usuario haya dicho manda siempre: un grupo que abrió se queda abierto
 * aunque no quepa, y uno que cerró se queda cerrado aunque sobre sitio.
 */
internal object GroupBudget {

    /**
     * Filas que se deja medir un primer pintado.
     *
     * Es el único momento en el que sincronizar por diferencias no ayuda: no hay nada
     * puesto, así que cada fila es una medida nueva. A ~0,064 ms por fila son ~10 ms de
     * los 16 que hay, y son de sobra más filas de las que caben en una tool window.
     */
    const val FIRST_PAINT_ROWS = 150

    /**
     * Por encima de esto un grupo empieza plegado aunque sobre presupuesto.
     *
     * No es por coste —abrirlo sólo pondría su primera página— sino por lo que se ve:
     * un grupo con decenas de miles de tareas deja al resto fuera de la pantalla, y su
     * cabecera ya dice cuántas tiene.
     */
    const val BIG_GROUP = 500

    /**
     * Los grupos abiertos, en el orden del contorno.
     *
     * [headers] son las cabeceras que hay puestas: también son filas que hay que medir,
     * y salen del mismo presupuesto. Con muchas —agrupar por fecha reparte un año en
     * meses y días— lo que queda para las tareas es menos, y es lo correcto: lo que se
     * ve al abrir es la lista de grupos, no el fondo de ninguno.
     *
     * [window] dice cuántas filas tiene cargadas ya un grupo, que es lo que de verdad
     * va a costar volver a ponerlo: un grupo por el que el usuario ya se desplazó gasta
     * más presupuesto que uno recién abierto.
     */
    fun open(
        outline: List<GroupOutline>,
        opened: Set<GroupKey>,
        collapsed: Set<GroupKey>,
        headers: Int,
        window: (GroupKey) -> Int = { TaskPager.PAGE },
    ): Set<GroupKey> {
        var budget = FIRST_PAINT_ROWS - headers
        val result = LinkedHashSet<GroupKey>()
        for (group in outline) {
            val open = when {
                group.key in opened -> true
                group.key in collapsed -> false
                group.size > BIG_GROUP -> false
                else -> budget > 0
            }
            if (!open) continue
            result += group.key
            budget -= minOf(group.size, window(group.key))
        }
        return result
    }
}
