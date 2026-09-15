package com.tasklane.data.store

import com.intellij.openapi.project.Project
import com.tasklane.domain.model.RepoKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Dónde vive cada cosa en disco.
 *
 *     <proyecto>/.idea/
 *     ├── tasklane.xml          config del proyecto (Fase 2) — VERSIONABLE
 *     └── tasklane/             datos — auto-ignorados
 *         ├── .gitignore        contiene "*"
 *         ├── layout.xml        registro repoKey -> ruta
 *         ├── tasklane.db       LAS TAREAS (Fase 3), uno por proyecto
 *         └── repos/<repoKey>/
 *             ├── tasks.xml.migrated   el formato anterior, conservado
 *             ├── tasks.xml.bak
 *             └── attachments/
 *
 * La separación es deliberada: la configuración se puede compartir con el equipo,
 * los datos no. Ver docs/architecture.html §4.
 */
class StorageLayout(val root: Path) {

    /** Registro `repoKey -> ruta` de la última detección. Ver [RepoLayoutCodec]. */
    fun layoutFile(): Path = root.resolve(LAYOUT_FILE)

    fun repoDir(repo: RepoKey): Path = root.resolve("repos").resolve(repo.value)
    fun tasksFile(repo: RepoKey): Path = repoDir(repo).resolve(TASKS_FILE)
    fun backupFile(repo: RepoKey): Path = repoDir(repo).resolve("$TASKS_FILE.bak")
    fun attachmentsDir(repo: RepoKey): Path = repoDir(repo).resolve("attachments")
    fun corruptFile(repo: RepoKey, stamp: Long): Path = repoDir(repo).resolve("tasks.corrupt-$stamp.xml")

    /**
     * ¿Este repositorio tiene tareas guardadas? Es la pregunta que decide si una
     * entrada que ya no se detecta sigue mereciendo un sitio en el selector.
     */
    fun hasTasks(repo: RepoKey): Boolean = Files.exists(tasksFile(repo))

    /**
     * Un `.gitignore` con `*` DENTRO de nuestro propio directorio: los datos quedan
     * fuera de VCS sin tocar ni una línea del `.gitignore` del usuario.
     */
    fun ensureIgnored() {
        val marker = root.resolve(".gitignore")
        if (Files.exists(marker)) return
        Files.createDirectories(root)
        Files.writeString(marker, "# Tasklane local data. Delete this file if you want to commit it.\n*\n")
    }

    /** El fichero de la base: uno por proyecto, con columna `repo`. Ver §2.3 del plan. */
    fun dbFile(): Path = root.resolve(DB_FILE)

    /**
     * Dónde queda el `tasks.xml` una vez importado.
     *
     * **No se borra nunca**: la migración lo renombra, y quitarle el sufijo es la vuelta
     * atrás completa. Es la promesa del §3.3 del plan de escala.
     */
    fun migratedFile(repo: RepoKey): Path =
        repoDir(repo).resolve("$TASKS_FILE$MIGRATED_SUFFIX")

    companion object {
        const val TASKS_FILE = "tasks.xml"

        /**
         * `tasklane.db` — la base de la Fase 3.
         *
         * **Uno por proyecto y no uno por repositorio**: buscar «en todos los
         * repositorios» pasa a ser una consulta en vez de treinta, y «Exportar y quitar»
         * pasa a ser un `DELETE … WHERE repo = ?`.
         */
        const val DB_FILE = "tasklane.db"

        /** Lo que se le añade al `tasks.xml` cuando ya está dentro de la base. */
        const val MIGRATED_SUFFIX = ".migrated"
        const val LAYOUT_FILE = "layout.xml"
        const val DIR_NAME = "tasklane"

        fun forProject(project: Project): StorageLayout? {
            val base = project.basePath ?: return null
            return StorageLayout(Paths.get(base).resolve(Project.DIRECTORY_STORE_FOLDER).resolve(DIR_NAME))
        }
    }
}
