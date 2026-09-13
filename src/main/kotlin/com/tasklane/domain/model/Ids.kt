package com.tasklane.domain.model

import java.util.UUID

/**
 * Identificadores como `value class`: coste cero en runtime, pero el compilador
 * impide pasar un [StateId] donde se espera un [PriorityId].
 *
 * Los IDs son estables y opacos. Los nombres visibles son mutables y viven en
 * [TaskState] / [TaskPriority]; por eso renombrar un estado no toca ninguna tarea.
 */
@JvmInline
value class TaskId(val value: String) {
    companion object {
        fun random() = TaskId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class StateId(val value: String)

@JvmInline
value class PriorityId(val value: String)

/**
 * Clave estable de un repositorio dentro del proyecto abierto. En la Fase 1 solo
 * existe [ROOT]; la detección real de repositorios Git llega en la Fase 3.
 */
@JvmInline
value class RepoKey(val value: String) {
    companion object {
        val ROOT = RepoKey("root")
    }
}

/** SHA-256 en hexadecimal del contenido del adjunto. Fase 6. */
@JvmInline
value class AttachmentId(val value: String)
