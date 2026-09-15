package com.tasklane.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Grupo de fecha al que cae una tarea dentro de un estado que agrupa.
 *
 * Es un **valor**, no un texto: el dominio decide la partición y la UI decide cómo
 * se escribe. Esa separación es la que permite formatear con el locale sin meter
 * Swing —ni el locale— dentro del dominio.
 *
 * **Un grupo por día, y sólo «hoy» sin fecha exacta** (2.3.0). Hasta la 2.2 había
 * cinco familias —hoy, ayer, esta semana, días sueltos del año y meses de años
 * anteriores—, y dos de ellas escondían días: «esta semana» metía cinco en una misma
 * cabecera y un mes de 2025 metía treinta. Lo pidió el usuario: cada día que tenga
 * alguna tarea es su propia cabecera, con su fecha. «Hoy» se queda con su nombre
 * porque es el único que no necesita fecha para saber de qué habla, y el único que se
 * enseña aunque esté vacío.
 *
 * El orden es el de lectura: lo más reciente arriba. Se compara por ([band],
 * [within]) en vez de por un único entero para que «hoy» y «sin fecha» no tengan que
 * reservarse un día imposible.
 */
sealed interface DateGroup : Comparable<DateGroup> {

    /** Familia del grupo. Ordena los bloques entre sí. */
    val band: Int

    /** Desempate dentro de la banda. Menor = más reciente. */
    val within: Long

    /**
     * El día del que habla el grupo, o `null` si no habla de uno.
     *
     * «Hoy» es un día concreto, pero cuál depende de cuándo se pregunte: por eso entra
     * [today] en vez de leerse el reloj aquí, igual que en [DateGrouper]. «Sin fecha»
     * no tiene día, y devolver uno inventado sería escribir en una exportación una fecha
     * que nadie eligió.
     *
     * Lo usa la exportación en Markdown, que encabeza cada grupo con su fecha en ISO.
     */
    fun dayOn(today: LocalDate): LocalDate?

    override fun compareTo(other: DateGroup): Int =
        compareValuesBy(this, other, { it.band }, { it.within })

    data object Today : DateGroup {
        override val band = 0
        override val within = 0L
        override fun dayOn(today: LocalDate): LocalDate = today
    }

    /** Cualquier día anterior a hoy que tenga alguna tarea: «Sep 14», «Sep 10, 2025». */
    data class Day(val date: LocalDate) : DateGroup {
        override val band = 1
        override val within get() = -date.toEpochDay()
        override fun dayOn(today: LocalDate): LocalDate = date
    }

    /**
     * La tarea no tiene la fecha que pide el anclaje del estado — típicamente un
     * estado que agrupa por `completedAt` y tareas que nunca se completaron.
     * Va al final en vez de desaparecer.
     */
    data object Undated : DateGroup {
        override val band = 2
        override val within = 0L
        override fun dayOn(today: LocalDate): LocalDate? = null
    }
}

/**
 * Reparte tareas en [DateGroup]s. Kotlin puro: se testea sin arrancar un IDE.
 *
 * Hasta la 2.2 recibía también el primer día de la semana, que era lo que delimitaba
 * «esta semana». Sin esa cabecera no queda nada que dependa de él, y se fue con ella.
 */
object DateGrouper {

    /** Fecha de la que cuelga la agrupación de un estado, según su [DateAnchor]. */
    fun anchorOf(task: Task, anchor: DateAnchor): Instant? = when (anchor) {
        DateAnchor.CREATED -> task.createdAt
        DateAnchor.UPDATED -> task.updatedAt
        DateAnchor.COMPLETED -> task.completedAt
    }

    /**
     * El intervalo `[desde, hasta]` que ocupa un grupo, en milisegundos de época.
     *
     * Es la inversa de [groupOf] y existe por la Fase 3: sin corpus en memoria, las
     * cabeceras no salen de repartir tareas sino de **contar un rango del índice**, y
     * para contar un rango hay que saber dónde empieza y dónde acaba. Que las dos
     * funciones vivan juntas es lo que impide que se separen: cualquier cambio en el
     * reparto obliga a tocar el intervalo en la línea de al lado.
     *
     * `hasta` de [DateGroup.Today] es [Long.MAX_VALUE] a propósito, por lo mismo que
     * [groupOf] manda ahí las fechas futuras: un reloj desajustado o un fichero editado
     * a mano no pueden crear un grupo por encima de «hoy».
     *
     * [DateGroup.Undated] no tiene intervalo —devuelve `null`—: no es un rango de
     * fechas, es la ausencia de una.
     */
    fun rangeOf(group: DateGroup, today: LocalDate, zone: ZoneId): LongRange? {
        fun startOf(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (group) {
            is DateGroup.Today -> startOf(today)..Long.MAX_VALUE
            // Por el principio de un día y del siguiente, y no sumando 24 horas: el día
            // en que cambia la hora mide 23 o 25.
            is DateGroup.Day -> startOf(group.date) until startOf(group.date.plusDays(1))
            is DateGroup.Undated -> null
        }
    }

    fun groupOf(instant: Instant?, today: LocalDate, zone: ZoneId): DateGroup {
        if (instant == null) return DateGroup.Undated
        val date = instant.atZone(zone).toLocalDate()
        // Una fecha futura sólo puede venir de un reloj desajustado o de un fichero
        // editado a mano. Se trata como «ahora» en vez de inventarle un grupo que
        // quedaría por encima de Today.
        return if (!date.isBefore(today)) DateGroup.Today else DateGroup.Day(date)
    }
}
