package com.tasklane.domain.model

import com.tasklane.domain.text.CodeFence
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
     * Los puntos del código de los que habla la tarea. Campo del modelo, como las
     * etiquetas: no se derivan del cuerpo porque no hay forma de escribir «la línea 42
     * de este fichero» sin inventarse una sintaxis, y porque el sitio se captura desde
     * el editor, que es quien lo sabe. Ver [CodeAnchor].
     */
    val anchors: List<CodeAnchor> = emptyList(),
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
     *
     * **Tampoco lo es una valla de código ni lo que va dentro** (2.8.0): una tarea que
     * empieza por ```` ```kotlin ```` tendría ese texto por título. Se busca la primera
     * línea de prosa; si el cuerpo es sólo un bloque, su primera línea de código.
     */
    val titleRange: IntRange by lazy(LazyThreadSafetyMode.PUBLICATION) {
        run {
            var fallback = IntRange.EMPTY
            val fence = CodeFence()
            var index = 0
            while (index < body.length) {
                val eol = body.indexOf('\n', index).takeIf { it >= 0 } ?: body.length
                val role = fence.next(body.substring(index, eol))
                var from = index
                var to = eol
                while (from < to && body[from].isWhitespace()) from++
                while (to > from && body[to - 1].isWhitespace()) to--
                if (from < to && role != CodeFence.Role.FENCE) {
                    val range = from until to
                    if (role == CodeFence.Role.PROSE && ImageRefParser.strip(body.substring(from, to)).isNotBlank()) {
                        return@run range
                    }
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
    val hasDetail: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        detailBlocks.any { it !is DetailBlock.Image }
    }

    /**
     * El cuerpo bajo el título en el orden en que se lee: los párrafos de texto y las
     * imágenes, cada una detrás de la línea que la nombra.
     *
     * Es la base de [detailLines], [hasDetail] y [description], y se calcula una sola
     * vez por tarea. Antes cada uno barría el cuerpo por su cuenta y pintar una fila
     * lo recorría cuatro veces; con la tarjeta de tres líneas —que pide título,
     * descripción y recuento de la lista para **cada** fila visible— eso dejaba de ser
     * sostenible.
     *
     * **Por qué bloques y no dos listas.** La tarjeta desplegada pinta el cuerpo entero
     * igual que el diálogo, y en el diálogo cada vista previa cuelga de la línea que la
     * nombra. Con el texto por un lado y las imágenes por otro ese orden se perdería y
     * todas las capturas acabarían amontonadas al final, que es justo lo contrario de
     * lo que se escribió.
     *
     * Las imágenes de la **línea del título** también salen aquí. El título es esa
     * línea *sin* sus imágenes —ver [titleRange]—, así que si no se recogieran en este
     * punto no se pintarían en ningún sitio.
     *
     * Lo que va entre vallas ```` ``` ```` sale como **un** [DetailBlock.Code] con sus
     * líneas tal cual (2.8.0): ni Markdown, ni enlaces, ni capturas dentro.
     */
    val detailBlocks: List<DetailBlock> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val title = titleRange
        if (title.isEmpty()) {
            emptyList()
        } else {
            buildList {
                val fence = CodeFence()
                var code: MutableList<String>? = null
                var language = ""
                fun closeCode() {
                    val lines = code ?: return
                    code = null
                    val dedented = CodeFence.dedent(lines).dropWhile(String::isEmpty).dropLastWhile(String::isEmpty)
                    if (dedented.isNotEmpty()) add(DetailBlock.Code(dedented, language))
                }
                var index = 0
                while (index < body.length) {
                    val eol = body.indexOf('\n', index).takeIf { it >= 0 } ?: body.length
                    val raw = body.substring(index, eol)
                    val isTitleLine = title.first >= index && title.last < eol
                    when (fence.next(raw)) {
                        CodeFence.Role.FENCE -> {
                            if (code == null) {
                                code = mutableListOf()
                                language = fence.language
                            } else {
                                closeCode()
                            }
                            index = eol + 1
                            continue
                        }
                        CodeFence.Role.CODE -> {
                            // La línea del título ya se pinta arriba: sólo pasa cuando
                            // el cuerpo es un bloque y nada más. Ver [titleRange].
                            if (!isTitleLine) (code ?: mutableListOf<String>().also { code = it }) += raw
                            index = eol + 1
                            continue
                        }
                        CodeFence.Role.PROSE -> Unit
                    }
                    val refs = ImageRefParser.parse(raw)
                    if (!isTitleLine) {
                        val stripped = if (refs.isEmpty()) raw else ImageRefParser.strip(raw)
                        val line = stripped.trim()
                        if (line.isNotBlank()) add(DetailBlock.Text(line, linksOfLine(index, eol, refs, stripped, line)))
                    }
                    // Detrás del texto de su línea, como el inlay del diálogo.
                    for (ref in refs) add(DetailBlock.Image(ref.id))
                    index = eol + 1
                }
                closeCode()
            }
        }
    }

    /**
     * Las líneas del cuerpo que no son el título y dicen algo: ni vacías, ni
     * compuestas sólo por referencias a imágenes.
     *
     * Es pública porque la tarjeta desplegada las pinta **todas**: [description] es el
     * resumen de una línea, y desplegar es justamente pedir lo que ese resumen esconde.
     */
    val detailLines: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        detailTexts.map { it.text }
    }

    /** Lo mismo que [detailLines] con los enlaces de cada una. Es lo que pinta la tarjeta. */
    val detailTexts: List<DetailBlock.Text> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        detailBlocks.filterIsInstance<DetailBlock.Text>()
    }

    /**
     * Los enlaces de la línea `body[start, eol)`, con el rango trasladado a [line]: la
     * misma línea sin las referencias a imágenes —[stripped]— y sin los espacios de los
     * extremos.
     *
     * Existe desde la 2.3.0, cuando los enlaces del cuerpo pasaron a poderse pulsar desde
     * la tarjeta y no sólo desde el contador. Se trasladan los que ya extrajo
     * `LinkExtractor` al escribir, en vez de volver a buscarlos en cada línea: pintar una
     * fila nunca debe ejecutar una regex, y así además valen las mismas reglas —un enlace
     * dentro de un tramo de código no es un enlace—.
     */
    private fun linksOfLine(start: Int, eol: Int, refs: List<AttachmentRef>, stripped: String, line: String): List<TaskLink> {
        if (links.isEmpty()) return emptyList()
        val lead = stripped.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return links.mapNotNull { link ->
            if (link.range.first < start || link.range.last >= eol) return@mapNotNull null
            val from = link.range.first - start
            val to = link.range.last - start
            // Un enlace no se solapa con una referencia a imagen, pero si alguna vez lo
            // hiciera no habría dónde colocarlo: la referencia ya no está en la línea.
            if (refs.any { from <= it.range.last && to >= it.range.first }) return@mapNotNull null
            val removed = refs.filter { it.range.last < from }.sumOf { it.range.last - it.range.first + 1 }
            val range = (from - removed - lead)..(to - removed - lead)
            link.copy(range = range).takeIf { range.first >= 0 && range.last < line.length }
        }
    }

    /**
     * Una línea de resumen de lo que hay debajo del título, para la tarjeta plegada.
     * Desplegada se pintan todas: ver [detailLines].
     */
    val description: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        detailLines.firstOrNull().orEmpty()
    }

    /**
     * Los enlaces que caen dentro del título, con el rango ya trasladado a
     * coordenadas del título. Los del detalle van con su línea: ver [detailTexts].
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
 * Un trozo del cuerpo de una tarea por debajo del título: un párrafo de texto o una
 * imagen. Ver [Task.detailBlocks].
 */
sealed interface DetailBlock {
    /**
     * Ya sin las referencias a imágenes y sin los espacios de los extremos. [links] son
     * los enlaces de la línea con el rango en coordenadas de [text].
     */
    class Text(val text: String, val links: List<TaskLink> = emptyList()) : DetailBlock

    class Image(val id: AttachmentId) : DetailBlock

    /**
     * Un bloque de código entre vallas, con sus líneas **literales**: sin la sangría
     * común y con los tabuladores hechos espacios, pero sin tocar nada más. [language]
     * es lo que seguía a la valla de apertura, o vacío.
     */
    class Code(val lines: List<String>, val language: String = "") : DetailBlock
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
