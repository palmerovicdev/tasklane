package com.tasklane.paging

import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId

/**
 * De dónde saca la lista sus filas, **a trozos**.
 *
 * Es la costura de la Fase 2 (`docs/plan-escala.md` §3, Fase 2.1) y existe por el
 * mismo motivo que existe `TaskSearchIndex`: el panel tiene que poder dejar de pedir
 * «la lista» y pasar a pedir «una página» **antes** de que haya una base de datos
 * detrás. La costura cumplió en la Fase 3: entró
 * `com.tasklane.data.sqlite.SqlitePager` —índice y `LIMIT`— y no hubo que tocar una
 * línea de la UI.
 *
 * Hay **dos** implementaciones vivas, y el reparto es el mismo de siempre: SQL para lo
 * que no cabe —la lista de una pestaña—, Kotlin para lo que está acotado por su propia
 * naturaleza —los doscientos aciertos de una búsqueda, lo vencido—, que es lo que
 * pagina [MemoryPager].
 *
 * Lo que el contrato promete, y lo que hace que la ventana no se congele: **ninguna
 * llamada de aquí devuelve más de [PageQuery.limit] tareas**, y [counts] y [outline]
 * son agregados —`(clave, cuenta)`—, no contenido. Un grupo de 800.000 filas se
 * anuncia con su número y no se materializa hasta que alguien lo abre.
 *
 * Un pager describe **un instante**: se construye con el snapshot, la búsqueda y el
 * filtro de ese repintado y no cambia después. Por eso no hay invalidación ni
 * suscripción: cuando el modelo cambia, llega otro pager.
 */
internal interface TaskPager {

    /**
     * Cuántas tareas se ven en cada estado, para los contadores de las pestañas.
     *
     * Es un **agregado**: en la Fase 3 sale de la tabla `counter`, que se mantiene en
     * la misma transacción que la escritura. Aquí sale de la única pasada que ya hace
     * falta para repartir las tareas por estado, y no de un recorrido aparte.
     */
    fun counts(): Map<StateId, Int>

    /**
     * Las cabeceras de un estado, en el orden en que se pintan, con su cuenta.
     *
     * Vacío significa que la pestaña **no agrupa**: entonces la raíz se pagina
     * directamente con [page] y `group = null`.
     */
    fun outline(stateId: StateId): List<GroupOutline>

    /**
     * Una página. [from] es por dónde empieza la ventana; `null` == por el principio.
     *
     * Nunca `OFFSET`: [from] es un cursor opaco que la implementación resuelve en
     * tiempo constante —hoy una posición en una lista ya ordenada, en la Fase 3 la
     * tupla del orden con la que SQLite pagina por *keyset*—.
     */
    fun page(query: PageQuery, from: Cursor? = null): TaskPage

    /**
     * La ventana con la que se puede **enseñar** una tarea sin cargar todo lo que
     * tiene delante. `null` == esa tarea no se ve en ese estado ahora mismo.
     *
     * Existe porque «enséñame esta tarea» —pulsar una marca del editor— puede caer en
     * la fila 900.000 de su grupo, y llegar hasta ella página a página sería
     * exactamente lo que esta fase vino a quitar. La implementación decide si la tarea
     * está lo bastante arriba como para cargar desde el principio o si hay que abrir la
     * ventana a su altura, con lo que quede por encima anunciado en un centinela.
     */
    fun reveal(stateId: StateId, id: TaskId): Reveal?

    /**
     * Todo lo de un trozo de la lista, **a tandas**, en el orden de la lista.
     *
     * Lo pide **sólo la exportación**, que por definición es O(n) —exportar un millón de
     * tareas es leer un millón de tareas—. Hasta la Fase 5 esto era `all()` y devolvía
     * una lista: exportar un estado de 300.000 filas era tener 300.000 `Task` vivas antes
     * de escribir la primera línea. Ahora a [block] le llegan tandas de [chunk] y lo que
     * se retiene es la tanda.
     *
     * [PageQuery.limit] no cuenta aquí: sin tope es justamente lo que se pide. Para parar
     * a medias —cancelar— [block] lanza, y la excepción sale tal cual.
     */
    fun each(query: PageQuery, chunk: Int = EXPORT_CHUNK, block: (List<Task>) -> Unit)

    /**
     * Lo mismo que este paginador, pero **congelado** mientras dure [block].
     *
     * Exportar un millón de tareas son minutos, y durante esos minutos la ventana sigue
     * abierta y el usuario sigue editando. Leyendo por *keyset* sobre una base que cambia
     * por debajo, una tarea que se marca a mitad de camino salta de cubo y sale **dos
     * veces** —o ninguna—. Una exportación es una foto de la pestaña en el momento de
     * pulsar, y esto es lo que la hace serlo.
     *
     * Por defecto es el propio paginador: uno que ya describe un instante inmutable
     * —[MemoryPager], que ordena una lista que ya tiene— no tiene nada que congelar.
     */
    fun <T> snapshot(block: (TaskPager) -> T): T = block(this)

    /**
     * Cuántas tareas de [stateId] esconde el archivo de lo terminado (2.10.0). Cero en
     * quien no archiva: buscando se ve todo, y las vencidas nunca están cerradas.
     */
    fun archived(stateId: StateId): Int = 0

    companion object {
        /**
         * Tareas por tanda al exportar. Lo bastante grande como para que el coste de cada
         * consulta se reparta, y lo bastante pequeño como para que lo retenido no se
         * note: dos mil tareas de la especificación son unos 25 MB en el peor caso.
         */
        const val EXPORT_CHUNK = 2_000

        /**
         * Filas por página.
         *
         * El plan decía cien (§2.5.2) y medirlo lo bajó a la mitad: traer una página de
         * cien filas sobre el corpus de la especificación cuesta **12,9 ms de p50 y
         * 14,9 de p99**, y el presupuesto del hilo de interfaz son 16. Pasar la puerta
         * por un 7 % no es pasarla: en una máquina más lenta que ésta, o con una
         * tarjeta desplegada de por medio, ese margen no existe. Con cincuenta son
         * ~6,5 ms, que deja el doble de sitio.
         *
         * Cincuenta filas siguen siendo tres pantallas largas de tarjetas, así que lo
         * que se paga por la mitad es llegar al centinela el doble de veces al
         * recorrer una lista entera — y eso ocurre fuera del gesto, encolado.
         */
        const val PAGE = 50
    }
}

/**
 * Por dónde continúa una página.
 *
 * Opaco a propósito: quien lo recibe sólo lo guarda y lo devuelve. En [MemoryPager] es
 * una posición dentro de una lista ya ordenada; en `SqlitePager` es la tupla del orden
 * —`(bookmarked, priority_rank, sort_date, seq)`, más cuántas filas quedaron detrás—
 * con la que la consulta pagina sin `OFFSET`. Que el panel no pueda mirar dentro es lo
 * que permitió cambiar una cosa por la otra sin tocar la UI.
 */
internal interface Cursor

/** Una cabecera: la clave del grupo y cuántas tareas hay debajo. */
internal data class GroupOutline(val key: GroupKey, val size: Int)

/**
 * Qué trozo de la lista se pide.
 *
 * [group] a `null` significa la raíz: o la pestaña no agrupa, o se está pidiendo la
 * lista entera de un estado sin cabeceras.
 */
internal data class PageQuery(
    val stateId: StateId,
    val group: GroupKey? = null,
    val limit: Int = TaskPager.PAGE,
)

/**
 * Cuántas filas quedan **por encima** de la ventana, y el cursor con el que empezaría
 * una ventana ampliada una página hacia arriba.
 *
 * Sólo aparece después de un [TaskPager.reveal] que haya tenido que saltar: una lista
 * que se abre por el principio no tiene nada por encima.
 */
internal data class Before(val remaining: Int, val cursor: Cursor)

/**
 * Una página de la lista y qué queda fuera por cada lado.
 *
 * [after] es una cuenta y no un cursor porque ampliar hacia abajo es pedir más
 * [PageQuery.limit] desde el mismo sitio: así la ventana se puede volver a pedir
 * **igual** en el siguiente repintado, que es lo que hace que el árbol se pueda
 * sincronizar por diferencias en vez de reconstruirse.
 */
internal data class TaskPage(
    val items: List<Task>,
    val before: Before? = null,
    val after: Int = 0,
)

/** La ventana que hay que abrir para que una tarea se vea. Ver [TaskPager.reveal]. */
internal data class Reveal(val group: GroupKey?, val from: Cursor?, val size: Int)

/**
 * Nada que paginar.
 *
 * Existe para que la pestaña tenga un pager desde que se construye: la ventana se
 * monta en el EDT y el primero de verdad llega desde una corrutina, así que sin esto
 * habría un `null` que comprobar en cada sitio que pregunta —incluida la exportación,
 * que puede dispararse por atajo antes del primer repintado—.
 */
internal object EmptyPager : TaskPager {
    override fun counts(): Map<StateId, Int> = emptyMap()
    override fun outline(stateId: StateId): List<GroupOutline> = emptyList()
    override fun page(query: PageQuery, from: Cursor?): TaskPage = TaskPage(emptyList())
    override fun reveal(stateId: StateId, id: TaskId): Reveal? = null
    override fun each(query: PageQuery, chunk: Int, block: (List<Task>) -> Unit) = Unit
}
