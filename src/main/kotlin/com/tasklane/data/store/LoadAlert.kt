package com.tasklane.data.store

import java.nio.file.Path

/**
 * Qué hay que contarle al usuario después de leer el fichero de un repositorio, y si
 * ese repositorio se abre en solo lectura.
 *
 * Existe separado de quien notifica porque es **el criterio de la Fase 7**: «corromper
 * `tasks.xml` y comprobar que el plugin recupera desde `.bak` y avisa». Recuperar ya
 * se probaba en `TaskFileStoreTest`; avisar no se probaba en ninguna parte, porque la
 * decisión vivía dentro de `TaskService`, que necesita un `Project` para instanciarse.
 * Sacada aquí, es una función pura sobre [TaskFileStore.ReadResult] y se prueba sin
 * arrancar un IDE, igual que el resto del dominio.
 *
 * No trae textos: los textos son del `TasklaneBundle` y se traducen. Esto dice **qué**
 * pasó y con qué números; quien notifica decide cómo se escribe.
 */
sealed interface LoadAlert {

    /**
     * Si el repositorio debe quedar sin escritura. Sólo lo pide [FutureFormat]: un
     * fichero corrupto del que se recuperó algo se sigue pudiendo editar y guardar —de
     * hecho interesa, porque así se consolida lo recuperado—, mientras que uno escrito
     * por una versión más nueva se degradaría al guardarlo con el esquema viejo.
     */
    val readOnly: Boolean get() = false

    /** El fichero lo escribió una versión posterior del plugin. */
    data class FutureFormat(val version: Int) : LoadAlert {
        override val readOnly: Boolean get() = true
    }

    /** No se pudo leer, pero la copia de seguridad sí tenía tareas. */
    data class Recovered(val tasks: Int, val quarantinedAt: Path?) : LoadAlert

    /** No se pudo leer y no había copia utilizable: el repositorio empieza en blanco. */
    data class Lost(val quarantinedAt: Path?) : LoadAlert
}

/** El aviso que merece este resultado, o `null` si la lectura fue normal. */
fun TaskFileStore.ReadResult.alert(): LoadAlert? = when (this) {
    is TaskFileStore.ReadResult.Empty, is TaskFileStore.ReadResult.Ok -> null
    is TaskFileStore.ReadResult.FutureVersion -> LoadAlert.FutureFormat(version)
    is TaskFileStore.ReadResult.Corrupt ->
        if (recoveredFromBackup) {
            LoadAlert.Recovered(recovered.size, quarantinedAt)
        } else {
            LoadAlert.Lost(quarantinedAt)
        }
}
