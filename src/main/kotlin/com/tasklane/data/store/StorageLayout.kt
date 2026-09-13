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
 *         ├── layout.xml        registro repoKey -> ruta (Fase 3)
 *         └── repos/<repoKey>/
 *             ├── tasks.xml
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

    companion object {
        const val TASKS_FILE = "tasks.xml"
        const val LAYOUT_FILE = "layout.xml"
        const val DIR_NAME = "tasklane"

        fun forProject(project: Project): StorageLayout? {
            val base = project.basePath ?: return null
            return StorageLayout(Paths.get(base).resolve(Project.DIRECTORY_STORE_FOLDER).resolve(DIR_NAME))
        }
    }
}
