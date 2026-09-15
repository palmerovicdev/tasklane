package com.tasklane.ui.common

import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.DateGroup
import com.tasklane.domain.model.GroupKey
import com.tasklane.domain.model.TasklaneConfig
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Texto de la cabecera de un grupo.
 *
 * Vive en la UI y no en el dominio porque depende del locale y del bundle. Para los
 * días se usa [DateTimeFormatter.ofLocalizedPattern] en vez de `DateFormatUtil`: hace
 * falta «10 de septiembre **sin año**» cuando el día es de este año, y
 * `DateFormatUtil` sólo ofrece fechas completas. El patrón va como *skeleton* CLDR, así
 * que cada locale coloca los campos a su manera —`Sep 10`, `10 sept`, `9月10日`— sin
 * que aquí haya un formato cableado.
 *
 * **El año sólo cuando no es el de ahora.** Desde la 2.3.0 los días de años anteriores
 * también son su propia cabecera —antes se juntaban por meses—, y «Sep 10» a secas
 * sería mentira sobre una tarea de 2025.
 *
 * El formateador se construye en cada llamada porque congela el locale al crearse
 * y el del IDE se puede cambiar en caliente; son unas pocas cabeceras por repintado.
 *
 * El nombre de la prioridad se resuelve contra la configuración **vigente** y no se
 * guarda en la clave: renombrar una prioridad en *Settings* tiene que cambiar la
 * cabecera sin reconstruir nada.
 */
internal object GroupLabels {

    fun of(key: GroupKey, config: TasklaneConfig, today: LocalDate = LocalDate.now()): String = when (key) {
        is GroupKey.OfDate -> ofDate(key.group, today)
        is GroupKey.OfPriority -> config.priorities.firstOrNull { it.id == key.id }?.name
            ?: TasklaneBundle.message("group.noPriority")

        is GroupKey.OfTag -> key.name?.let { "#$it" } ?: TasklaneBundle.message("group.noTag")
    }

    fun ofDate(group: DateGroup, today: LocalDate = LocalDate.now()): String = when (group) {
        DateGroup.Today -> TasklaneBundle.message("group.today")
        DateGroup.Undated -> TasklaneBundle.message("group.undated")
        is DateGroup.Day -> DateTimeFormatter
            .ofLocalizedPattern(if (group.date.year == today.year) DAY_SKELETON else DAY_YEAR_SKELETON)
            .format(group.date)
    }

    private const val DAY_SKELETON = "MMMd"
    private const val DAY_YEAR_SKELETON = "yMMMd"
}
