package com.tasklane.ui.settings

import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.AnchorMarkerStyle
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.Grouping

/**
 * Nombres visibles de los enums de configuración. Viven aquí y no en el dominio
 * porque son texto traducible, no comportamiento.
 */
internal fun Grouping.label(): String = when (this) {
    Grouping.NONE -> TasklaneBundle.message("settings.grouping.none")
    Grouping.BY_DATE -> TasklaneBundle.message("settings.grouping.byDate")
    Grouping.BY_PRIORITY -> TasklaneBundle.message("settings.grouping.byPriority")
    Grouping.BY_TAG -> TasklaneBundle.message("settings.grouping.byTag")
}

internal fun DateAnchor.label(): String = when (this) {
    DateAnchor.CREATED -> TasklaneBundle.message("settings.anchor.created")
    DateAnchor.UPDATED -> TasklaneBundle.message("settings.anchor.updated")
    DateAnchor.COMPLETED -> TasklaneBundle.message("settings.anchor.completed")
}

internal fun AnchorMarkerStyle.label(): String = when (this) {
    AnchorMarkerStyle.GUTTER -> TasklaneBundle.message("settings.anchors.marker.gutter")
    AnchorMarkerStyle.INLINE -> TasklaneBundle.message("settings.anchors.marker.inline")
    AnchorMarkerStyle.OFF -> TasklaneBundle.message("settings.anchors.marker.off")
}
