package com.tasklane.domain.model

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
    /** Derivado de [body] y cacheado: pintar una fila nunca debe ejecutar una regex. Fase 5. */
    val links: List<TaskLink> = emptyList(),
    /** Derivado de [body] y cacheado. Fase 6. */
    val attachments: List<AttachmentRef> = emptyList(),
    val tags: List<String> = emptyList(),
    /**
     * Atributos que este plugin no conoce. Al leer un fichero escrito por una
     * versión más nueva se conservan aquí y se vuelven a escribir tal cual, para
     * que abrir el proyecto con una versión vieja no borre datos futuros.
     */
    val extra: Map<String, String> = emptyMap(),
) {
    /** Primera línea no vacía: lo que se muestra en la fila del árbol. */
    val title: String
        get() = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    val hasDetail: Boolean
        get() = body.lineSequence().drop(1).any { it.isNotBlank() }
}

data class TaskLink(val url: String, val display: String)

data class AttachmentRef(val id: AttachmentId, val ext: String, val width: Int, val height: Int)
