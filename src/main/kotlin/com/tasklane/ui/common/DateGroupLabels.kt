package com.tasklane.ui.common

import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.DateGroup
import java.time.format.DateTimeFormatter

/**
 * Texto de la cabecera de cada grupo de fecha.
 *
 * Vive en la UI y no en el dominio porque depende del locale y del bundle. Para los
 * grupos con fecha concreta se usa [DateTimeFormatter.ofLocalizedPattern] en vez de
 * `DateFormatUtil`: hace falta «10 de septiembre **sin año**» y «septiembre 2025»,
 * y `DateFormatUtil` sólo ofrece fechas completas. El patrón va como *skeleton*
 * CLDR, así que cada locale coloca los campos a su manera —`Sep 10`, `10 sept`,
 * `9月10日`— sin que aquí haya un formato cableado.
 *
 * El formateador se construye en cada llamada porque congela el locale al crearse
 * y el del IDE se puede cambiar en caliente; son unas pocas cabeceras por repintado.
 */
internal object DateGroupLabels {

    fun of(group: DateGroup): String = when (group) {
        DateGroup.Today -> TasklaneBundle.message("group.today")
        DateGroup.Yesterday -> TasklaneBundle.message("group.yesterday")
        DateGroup.ThisWeek -> TasklaneBundle.message("group.thisWeek")
        DateGroup.Undated -> TasklaneBundle.message("group.undated")
        is DateGroup.Day -> DateTimeFormatter.ofLocalizedPattern(DAY_SKELETON).format(group.date)
        is DateGroup.Month -> DateTimeFormatter.ofLocalizedPattern(MONTH_SKELETON).format(group.month)
    }

    private const val DAY_SKELETON = "MMMd"
    private const val MONTH_SKELETON = "yMMM"
}
