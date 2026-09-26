package com.tasklane.data.config

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.tasklane.domain.model.DateAnchor
import com.tasklane.domain.model.Grouping
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TagColor
import com.tasklane.domain.model.TaskPriority
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig

/**
 * Espejo mutable de [TasklaneConfig] para `com.intellij.util.xmlb`, que exige beans
 * con constructor sin argumentos y `var`.
 *
 * Aquí sí se usa xmlb —y no el mapeo a mano de `TasksCodec`— porque el compromiso es
 * el contrario: `tasklane.xml` lo escribe la plataforma como `PersistentStateComponent`
 * y es un fichero **versionable**, así que interesa que salga limpio y diffeable más
 * que preservar atributos de versiones futuras.
 *
 * Dos decisiones de formato, por lo mismo:
 *  - el **orden** es la posición en la lista, no un atributo `order`; no hay dos
 *    fuentes de verdad que puedan discrepar;
 *  - los colores van en **hexadecimal** (`6C8EBF`), que es lo que una persona
 *    reconoce al revisar el diff de un merge.
 */
@Tag("state")
class StateBean {
    @Attribute var id: String = ""
    @Attribute var name: String = ""
    @Attribute var grouping: String = Grouping.NONE.name
    @Attribute var anchor: String = DateAnchor.UPDATED.name
    @Attribute var terminal: Boolean = false
    @Attribute var default: Boolean = false
    @Attribute var manualOrder: Boolean = false
}

@Tag("priority")
class PriorityBean {
    @Attribute var id: String = ""
    @Attribute var name: String = ""
    @Attribute var colorLight: String = "808080"
    @Attribute var colorDark: String = "808080"
    @Attribute var trigger: String? = null
    @Attribute var default: Boolean = false
}

/** El color de una etiqueta (P30). Sólo las que lo tienen: ver `TasklaneConfig.tagColors`. */
@Tag("tag")
class TagBean {
    @Attribute var name: String = ""
    @Attribute var colorLight: String = "808080"
    @Attribute var colorDark: String = "808080"
}

class TasklaneConfigState {

    @XCollection(propertyElementName = "states", style = XCollection.Style.v2)
    var states: MutableList<StateBean> = mutableListOf()

    @XCollection(propertyElementName = "priorities", style = XCollection.Style.v2)
    var priorities: MutableList<PriorityBean> = mutableListOf()

    @Attribute
    var triggersEnabled: Boolean = true

    @Attribute
    var repoDepth: Int = TasklaneConfig.DEFAULT_REPO_DEPTH

    @Attribute
    var imageQuotaMegabytes: Int = TasklaneConfig.DEFAULT_IMAGE_QUOTA_MB

    /**
     * Vacía, no se escribe: un proyecto sin colores de etiqueta deja `tasklane.xml` como
     * estaba, y una versión anterior a la que llegue la lista la ignora sin más.
     */
    @XCollection(propertyElementName = "tags", style = XCollection.Style.v2)
    var tags: MutableList<TagBean> = mutableListOf()
}

// ------------------------------------------------------------------ mapeo

/**
 * @return la configuración del fichero, o `null` si no es utilizable. Devolver
 *   `null` en vez de una config a medias es lo que deja que quien llama decida
 *   sembrar desde la plantilla: un `tasklane.xml` vacío o mal resuelto en un merge
 *   no debe dejar el proyecto sin un solo estado.
 */
fun TasklaneConfigState.toDomain(): TasklaneConfig? {
    val states = states.mapIndexedNotNull { i, bean -> bean.toDomain(i) }
    val priorities = priorities.mapIndexedNotNull { i, bean -> bean.toDomain(i) }
    if (states.isEmpty() || priorities.isEmpty()) return null
    // Una fila sin nombre no es el color de ninguna etiqueta: se tira, como un estado sin id.
    val tagColors = tags
        .filter { it.name.isNotBlank() }
        .associate { it.name to TagColor(parseColor(it.colorLight), parseColor(it.colorDark)) }
    return TasklaneConfig(states, priorities, triggersEnabled, repoDepth, imageQuotaMegabytes, tagColors)
        .normalized()
}

private fun StateBean.toDomain(index: Int): TaskState? {
    val id = id.takeIf { it.isNotBlank() } ?: return null
    return TaskState(
        id = StateId(id),
        name = name.takeIf { it.isNotBlank() } ?: id,
        order = index,
        grouping = enumOrDefault(grouping, Grouping.NONE),
        anchor = enumOrDefault(anchor, DateAnchor.UPDATED),
        terminal = terminal,
        isDefault = default,
        manualOrder = manualOrder,
    )
}

private fun PriorityBean.toDomain(index: Int): TaskPriority? {
    val id = id.takeIf { it.isNotBlank() } ?: return null
    return TaskPriority(
        id = PriorityId(id),
        name = name.takeIf { it.isNotBlank() } ?: id,
        order = index,
        colorLight = parseColor(colorLight),
        colorDark = parseColor(colorDark),
        trigger = trigger?.trim()?.takeIf(String::isNotEmpty),
        isDefault = default,
    )
}

fun TasklaneConfig.toState(): TasklaneConfigState = TasklaneConfigState().also { state ->
    state.states = states.mapTo(mutableListOf()) { s ->
        StateBean().apply {
            id = s.id.value
            name = s.name
            grouping = s.grouping.name
            anchor = s.anchor.name
            terminal = s.terminal
            default = s.isDefault
            manualOrder = s.manualOrder
        }
    }
    state.priorities = priorities.mapTo(mutableListOf()) { p ->
        PriorityBean().apply {
            id = p.id.value
            name = p.name
            colorLight = formatColor(p.colorLight)
            colorDark = formatColor(p.colorDark)
            trigger = p.trigger
            default = p.isDefault
        }
    }
    state.triggersEnabled = triggersEnabled
    state.repoDepth = repoDepth
    state.imageQuotaMegabytes = imageQuotaMegabytes
    // Por nombre y no en el orden en que se eligieron: el diff de un merge tiene que ser el
    // de lo que cambió, no el de una lista que se baraja.
    state.tags = tagColors.entries.sortedBy { it.key }.mapTo(mutableListOf()) { (tag, color) ->
        TagBean().apply {
            name = tag
            colorLight = formatColor(color.light)
            colorDark = formatColor(color.dark)
        }
    }
}

/** Un enum desconocido —fichero de otra versión— cae al valor seguro, no revienta. */
private inline fun <reified E : Enum<E>> enumOrDefault(raw: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback

private fun parseColor(raw: String): Int =
    raw.removePrefix("#").toIntOrNull(16)?.and(0xFFFFFF) ?: 0x808080

private fun formatColor(rgb: Int): String = "%06X".format(rgb and 0xFFFFFF)
