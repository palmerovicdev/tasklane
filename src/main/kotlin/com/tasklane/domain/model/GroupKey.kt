package com.tasklane.domain.model

/**
 * Por qué una fila cayó en un grupo, ya resuelto.
 *
 * Vive en el dominio y no en la UI porque de él depende el **orden** de los grupos, y
 * ése es un criterio del modelo: las fechas van de lo reciente a lo viejo, las
 * prioridades de la más alta a la más baja y las etiquetas por orden alfabético con
 * el cajón de «sin etiqueta» al final. Lo que sí es de la UI es el texto de la
 * cabecera, que depende del locale y del bundle: ver `GroupLabels`.
 *
 * Es una clase sellada y no un `String` porque el grupo también se usa como
 * *identidad*: el panel recuerda por él qué grupos plegó el usuario, y dos
 * etiquetas distintas con el mismo nombre visible tienen que seguir siendo grupos
 * distintos.
 */
sealed interface GroupKey {

    @JvmInline
    value class OfDate(val group: DateGroup) : GroupKey

    @JvmInline
    value class OfPriority(val id: PriorityId) : GroupKey

    /** [name] nulo == la tarea no tiene ninguna etiqueta. */
    @JvmInline
    value class OfTag(val name: String?) : GroupKey
}
