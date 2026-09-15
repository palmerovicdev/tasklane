package com.tasklane.paging

import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.DateGrouper
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskFilter
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.service.SearchResults
import com.tasklane.ui.toolwindow.VisibleTasks
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * El [TaskPager] que ordena y agrupa **una lista que ya cabe en memoria**.
 *
 * Nació en la Fase 2 como la implementación provisional sobre el snapshot —era el
 * `TasklanePanel.buildSections` de siempre, movido detrás de la costura— y en la Fase 3
 * dejó de ser provisional para pasar a ser **la mitad del reparto**:
 *
 * - Lo que no cabe en memoria —la lista de una pestaña, que puede ser un millón de
 *   filas— lo sirve `SqlitePager`, con un índice y un `LIMIT`.
 * - Lo que **está acotado por su propia naturaleza** lo sirve éste: los doscientos
 *   aciertos de una búsqueda y las tareas vencidas de un repositorio. Ordenar
 *   doscientas filas en Kotlin cuesta microsegundos, y expresar `bm25` y el orden
 *   natural en la misma consulta SQL costaría mucho más de leer.
 *
 * Por eso ya no recibe un snapshot sino **la lista**: quien lo construye es quien sabe
 * de dónde salió, y el pager no tiene por qué saberlo. Es también lo que hace que se
 * pueda probar con un puñado de tareas y sin almacén, que es como está probado.
 *
 * **Se construye fuera del EDT y se consulta desde él.** El reparto por estado y las
 * secciones de la pestaña se calientan en la corrutina que lo crea; lo que el hilo de
 * interfaz pide después —una página, una ventana para enseñar una tarea— es una
 * sublista de algo ya ordenado. La caché es concurrente por eso mismo, y no porque se
 * espere contención: un pager describe un instante y se tira con él.
 */
internal class MemoryPager(
    /** El universo de esta ventana, ya acotado por quien lo construyó. */
    private val tasks: List<Task>,
    private val config: TasklaneConfig,
    private val found: SearchResults,
    private val filter: TaskFilter,
    private val now: Instant,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: LocalDate = LocalDate.now(zone),
    private val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
) : TaskPager {

    /** Un bloque de la lista. [key] nulo == este estado no agrupa. */
    private class Section(val key: GroupKey?, val tasks: List<Task>)

    private val visible: Map<StateId, List<Task>> by lazy {
        VisibleTasks.byState(tasks, found, filter, now)
    }

    private val sections = ConcurrentHashMap<StateId, List<Section>>()

    /** Una posición dentro de una lista ya ordenada. Ver [Cursor]. */
    private data class At(val index: Int) : Cursor

    override fun counts(): Map<StateId, Int> = visible.mapValues { it.value.size }

    override fun outline(stateId: StateId): List<GroupOutline> =
        sections(stateId).mapNotNull { section ->
            section.key?.let { GroupOutline(it, section.tasks.size) }
        }

    override fun page(query: PageQuery, from: Cursor?): TaskPage {
        val tasks = sectionOf(query)
        val start = ((from as? At)?.index ?: 0).coerceIn(0, tasks.size)
        val end = (start + query.limit).coerceIn(start, tasks.size)
        return TaskPage(
            items = tasks.subList(start, end),
            before = if (start == 0) null else Before(start, At((start - TaskPager.PAGE).coerceAtLeast(0))),
            after = tasks.size - end,
        )
    }

    /** Acotado por naturaleza —ver el KDoc de la clase—, así que las tandas son sublistas. */
    override fun each(query: PageQuery, chunk: Int, block: (List<Task>) -> Unit) {
        val tasks = sectionOf(query)
        for (from in tasks.indices step chunk) block(tasks.subList(from, minOf(from + chunk, tasks.size)))
    }

    private fun sectionOf(query: PageQuery): List<Task> =
        sections(query.stateId).firstOrNull { it.key == query.group }?.tasks.orEmpty()

    /**
     * La tarea se busca **dentro de su sección**, no en toda la pestaña: agrupar ya
     * dice en qué cajón cae, así que lo que queda es encontrar su altura dentro de él.
     * Sigue siendo un escaneo —la lista está ordenada por un comparador, no por id—
     * pero es el precio de un gesto suelto del usuario, no de un repintado.
     */
    override fun reveal(stateId: StateId, id: TaskId): Reveal? {
        for (section in sections(stateId)) {
            val index = section.tasks.indexOfFirst { it.id == id }
            if (index >= 0) return window(section.key, index)
        }
        return null
    }

    /**
     * Cerca del principio se carga **desde** el principio: así se ve el contexto de
     * arriba y la lista queda como si nunca hubiera saltado. Más abajo se abre la
     * ventana a la altura de la tarea y lo que queda por encima se anuncia con un
     * centinela; cargar las 900.000 filas que tiene delante es justamente lo que esta
     * fase vino a quitar.
     */
    private fun window(group: GroupKey?, index: Int): Reveal {
        if (index < NEAR) {
            val rows = ((index + TaskPager.PAGE) / TaskPager.PAGE) * TaskPager.PAGE
            return Reveal(group, null, rows)
        }
        return Reveal(group, At(index - TaskPager.PAGE / 2), TaskPager.PAGE)
    }

    private fun sections(stateId: StateId): List<Section> =
        sections.computeIfAbsent(stateId, ::build)

    // --------------------------------------------------------------- agrupación

    private fun build(stateId: StateId): List<Section> {
        val state = config.state(stateId) ?: return emptyList()
        val mine = visible[stateId].orEmpty()

        // Lo marcado va primero pase lo que pase: marcar es precisamente decir «que
        // no se me pierda esto». Después la prioridad, que es lo que se mira en una
        // lista de pendientes, y dentro de la misma prioridad lo más reciente arriba.
        val natural = compareByDescending<Task> { it.bookmarked }
            .thenByDescending { config.priorityOrDefault(it.priorityId).order }
            .thenByDescending { (DateGrouper.anchorOf(it, state.anchor) ?: it.updatedAt).toEpochMilli() }
            // El desempate, que desde la Fase 3 no es opcional: `SqlitePager` ordena
            // estas mismas tareas con un índice, y dos tareas con la misma fecha al
            // milisegundo tienen que quedar en el mismo sitio en los dos sitios. Sin
            // esto, buscar y no buscar darían dos listas distintas con las mismas filas.
            .thenByDescending { it.id.value }
        // Buscando, en cambio, lo que manda es lo que mejor casa: el orden normal
        // enterraría el resultado bueno bajo cualquier tarea de prioridad alta.
        val order =
            if (found.active) compareByDescending<Task> { found.scoreOf(it.id) }.then(natural) else natural

        return when (state.grouping) {
            Grouping.NONE -> listOf(Section(null, mine.sortedWith(order)))
            // «Hoy» se enseña aunque esté vacío, pero sólo cuando la lista habla de
            // todo: buscando o con un filtro puesto, un «no queda nada» diría que no
            // hay tareas hoy cuando lo que pasa es que no casan con lo que se pidió.
            Grouping.BY_DATE -> byDate(mine, state, order, keepToday = !found.active && filter == TaskFilter.ALL)
            Grouping.BY_PRIORITY -> byPriority(mine, order)
            Grouping.BY_TAG -> byTag(mine, order)
        }
    }

    /**
     * Agrupa por fecha y, con [keepToday], se asegura de que «hoy» exista.
     *
     * Es el único grupo que vale la pena vacío: que no haya nada hoy es justo lo que
     * se viene a mirar. Los demás —ayer, esta semana— sólo importan cuando tienen
     * algo. Y no se añade a una lista vacía del todo: ahí el árbol tiene su propio
     * «no hay tareas», que además dice cómo crear la primera.
     */
    private fun byDate(
        tasks: List<Task>,
        state: TaskState,
        order: Comparator<Task>,
        keepToday: Boolean,
    ): List<Section> {
        val sections = tasks
            .groupBy { DateGrouper.groupOf(DateGrouper.anchorOf(it, state.anchor), today, zone, firstDayOfWeek) }
            .toSortedMap()
            .map { (group, inGroup) -> Section(GroupKey.OfDate(group), inGroup.sortedWith(order)) }

        val key = GroupKey.OfDate(DateGroup.Today)
        if (!keepToday || sections.isEmpty() || sections.any { it.key == key }) return sections
        // Delante: `DateGroup.Today` es el primero del orden, así que es donde habría
        // caído de tener tareas.
        return listOf(Section(key, emptyList())) + sections
    }

    /**
     * De la prioridad más alta a la más baja, y sólo con las que tienen algo: una
     * cabecera vacía por cada prioridad configurada convertiría la lista en un índice.
     */
    private fun byPriority(tasks: List<Task>, order: Comparator<Task>): List<Section> = tasks
        .groupBy { config.priorityOrDefault(it.priorityId).id }
        .toList()
        .sortedByDescending { (id, _) -> config.priorities.firstOrNull { it.id == id }?.order ?: 0 }
        .map { (id, inGroup) -> Section(GroupKey.OfPriority(id), inGroup.sortedWith(order)) }

    /**
     * Una tarea con varias etiquetas sale bajo **todas** las suyas, no bajo la
     * primera: agrupar por etiqueta sirve para ver junto todo lo de una, y repartir
     * cada tarea en un único cajón haría que la mitad de ellas faltasen del suyo.
     *
     * Las que no tienen ninguna van a un grupo propio al final en vez de
     * desaparecer, que es lo que haría un `flatMap` sobre `tags` a secas.
     */
    private fun byTag(tasks: List<Task>, order: Comparator<Task>): List<Section> = tasks
        .flatMap { task -> task.tags.ifEmpty { listOf(null) }.map { it to task } }
        .groupBy({ it.first }, { it.second })
        .toList()
        .sortedWith(compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.first })
        .map { (tag, inGroup) -> Section(GroupKey.OfTag(tag), inGroup.sortedWith(order)) }

    private companion object {
        /**
         * Hasta dónde se considera que una tarea está «arriba» y se carga desde el
         * principio. Dos páginas: 200 filas medidas de cero son ~13 ms de EDT, justo
         * por debajo del presupuesto de 16.
         */
        const val NEAR = 2 * TaskPager.PAGE
    }
}
