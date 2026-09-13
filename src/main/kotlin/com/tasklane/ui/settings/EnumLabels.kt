package com.tasklane.ui.settings

import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.Grouping

/**
 * Nombres visibles de los enums de configuración. Viven aquí y no en el dominio
 * porque son texto traducible, no comportamiento.
 */
internal fun Grouping.label(): String = when (this) {
    Grouping.NONE -> TasklaneBundle.message("settings.grouping.none")
    Grouping.BY_DATE -> TasklaneBundle.message("settings.grouping.byDate")
}

internal fun DateAnchor.label(): String = when (this) {
    DateAnchor.CREATED -> TasklaneBundle.message("settings.anchor.created")
    DateAnchor.UPDATED -> TasklaneBundle.message("settings.anchor.updated")
    DateAnchor.COMPLETED -> TasklaneBundle.message("settings.anchor.completed")
}
