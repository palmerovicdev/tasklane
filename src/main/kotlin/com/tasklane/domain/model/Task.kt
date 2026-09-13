package com.tasklane.domain.model

import com.tasklane.domain.text.ImageRefParser
import java.time.Instant

/**
 * Una tarea. Inmutable: toda modificación produce una copia nueva, lo que hace que
 * el snapshot que consume la UI no pueda cambiar bajo sus pies.
 */
data class Task(
    val id: TaskId,
    val repo: RepoKey,
    /** Markdown-lite. Es la fuente de verdad; todo lo demás se deriva de aquí. */
    val body: String,
    val stateId: StateId,
    val priorityId: PriorityId,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Se fija al entrar en un estado terminal. Null mientras la tarea siga abierta. */
    val completedAt: Instant? = null,
    /** Orden manual disperso (1000, 2000, …) para poder insertar sin reescribir la lista. */
    val order: Long = 0,
    /** Derivado de [body] y cacheado: pintar una fila nunca debe ejecutar una regex. */
    val links: List<TaskLink> = emptyList(),
    /** Derivado de [body] y cacheado, igual que [links]. */
    val attachments: List<AttachmentRef> = emptyList(),
    val tags: List<String> = emptyList(),
    /**
     * Cuándo vence. Es lo único del modelo que mira al futuro: el resto de fechas
     * registran lo que ya pasó, así que es también lo único que puede estar
     * *vencido* y pintarse en rojo.
     */
    val dueDate: Instant? = null,
    /** Fijada por el usuario. Sube al principio de su grupo y sale marcada. */
    val bookmarked: Boolean = false,
    /**
     * Atributos que este plugin no conoce. Al leer un fichero escrito por una
     * versión más nueva se conservan aquí y se vuelven a escribir tal cual, para
     * que abrir el proyecto con una versión vieja no borre datos futuros.
     */
    val extra: Map<String, String> = emptyMap(),
) {
    /**
     * Dónde empieza y acaba el título dentro de [body]: la primera línea con texto,
     * ya sin los espacios de los extremos.
     *
     * Se expone el **rango** y no sólo el texto porque los rangos de [links] son del
     * cuerpo entero, y la fila sólo pinta esta parte: sin el desplazamiento no se
     * podría saber qué enlace cae dentro del título ni dónde.
     *
     * **Una línea que sólo es una imagen no es el título.** Pegar una captura y
     * escribir debajo es el orden natural —el pegado inserta la referencia donde esté
     * el cursor—, y sin esta regla la fila del árbol mostraría 64 caracteres de SHA en
     * vez de lo que el usuario escribió. Si no hay ninguna otra línea, se cae a la
     * primera no vacía: una tarea siempre tiene que enseñar algo.
     */
    val titleRange: IntRange by lazy(LazyThreadSafetyMode.PUBLICATION) {
        run {
            var fallback = IntRange.EMPTY
            var index = 0
            while (index < body.length) {
                val eol = body.indexOf('\n', index).takeIf { it >= 0 } ?: body.length
                var from = index
                var to = eol
                while (from < to && body[from].isWhitespace()) from++
                while (to > from && body[to - 1].isWhitespace()) to--
                if (from < to) {
                    val range = from until to
                    if (ImageRefParser.strip(body.substring(from, to)).isNotBlank()) return@run range
                    if (fallback.isEmpty()) fallback = range
                }
                index = eol + 1
            }
            return@run fallback
        }
    }

    /** Primera línea no vacía: lo que se muestra en la fila del árbol. */
    val title: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        titleRange.let { if (it.isEmpty()) "" else body.substring(it.first, it.last + 1) }
    }

    /**
     * ¿Hay algo más que el título? Es lo que decide el «…» de la fila.
     *
     * Se compara contra la línea del título y no contra la primera, porque desde la
     * Fase 6 no tienen por qué ser la misma. Las líneas que sólo son imágenes no
     * cuentan: de ésas ya avisa el indicador de la fila, y un «…» que al abrir la
     * tarea no enseña ni una palabra más es una promesa incumplida.
     */
    val hasDetail: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { detailLines.isNotEmpty() }

    /**
     * Las líneas del cuerpo que no son el título y dicen algo: ni vacías, ni
     * compuestas sólo por referencias a imágenes.
     *
     * Es la base de [hasDetail] y de [description], y se calcula una
     * sola vez por tarea. Antes cada uno barría el cuerpo por su cuenta y pintar una
     * fila lo recorría cuatro veces; con la tarjeta de tres líneas —que pide título,
     * descripción y recuento de la lista para **cada** fila visible— eso dejaba de
     * ser sostenible.
     */
    private val detailLines: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val title = titleRange
        if (title.isEmpty()) {
            emptyList()
        } else {
            buildList {
                var index = 0
                while (index < body.length) {
                    val eol = body.indexOf('\n', index).takeIf { it >= 0 } ?: body.length
                    val isTitleLine = title.first >= index && title.last < eol
                    if (!isTitleLine) {
                        val line = ImageRefParser.strip(body.substring(index, eol)).trim()
                        if (line.isNotBlank()) add(line)
                    }
                    index = eol + 1
                }
            }
        }
    }

    /** Una línea de resumen de lo que hay debajo del título, para la tarjeta. */
    val description: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        detailLines.firstOrNull().orEmpty()
    }

    /**
     * Los enlaces que caen dentro del título, con el rango ya trasladado a
     * coordenadas del título. Es lo que la fila puede pintar como enlace; los que
     * viven en el detalle sólo llegan al indicador.
     */
    val titleLinks: List<TaskLink> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val title = titleRange
        if (links.isEmpty() || title.isEmpty()) {
            emptyList()
        } else {
            links
                .filter { it.range.first >= title.first && it.range.last <= title.last }
                .map { it.copy(range = (it.range.first - title.first)..(it.range.last - title.first)) }
        }
    }

    /** Vencida: tiene fecha, ya pasó y la tarea sigue abierta. */
    fun isOverdue(now: Instant): Boolean = completedAt == null && dueDate?.isBefore(now) == true
}

/**
 * Un enlace ya extraído del cuerpo.
 *
 * [url] es lo que se abre —siempre la original, nunca la acortada—, [display] lo que
 * se pinta (el texto del enlace Markdown, o la URL acortada con el dominio delante)
 * y [range] dónde estaba dentro de `Task.body`, que es lo que permite sustituirlo en
 * la fila sin volver a parsear.
 */
data class TaskLink(val url: String, val display: String, val range: IntRange)

/**
 * Una imagen referenciada desde el cuerpo: `![](tasklane:<sha256>)`.
 *
 * Sólo el ID y dónde está. El diseño original llevaba además `ext`, `width` y
 * `height`, y la implementación lo invalidó: los tres son propiedades del **blob**,
 * no del texto, así que rellenarlos exigiría leer el fichero — IO dentro del
 * reducer, que es puro por definición. El ancho y el alto los necesita quien pinta,
 * y quien pinta ya tiene la imagen decodificada delante; la extensión es siempre
 * `png` porque el normalizador re-codifica todo lo que entra. Ver
 * `docs/architecture.html` §9.
 *
 * [range] es del cuerpo entero, como el de [TaskLink]: es lo que permite colocar el
 * inlay del editor y quitar la referencia sin volver a buscarla.
 */
data class AttachmentRef(val id: AttachmentId, val range: IntRange)
