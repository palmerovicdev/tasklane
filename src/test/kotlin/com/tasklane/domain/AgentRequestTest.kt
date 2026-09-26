package com.tasklane.domain

import com.tasklane.domain.agent.AgentRequest
import com.tasklane.domain.agent.Shell
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TaskState
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class AgentRequestTest {

    private fun task(body: String, state: StateId = TasklaneConfig.TODO, id: String = "t-1") = Task(
        id = TaskId(id),
        repo = RepoKey.ROOT,
        body = body,
        stateId = state,
        priorityId = TasklaneConfig.NORMAL,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    // ------------------------------------------------------------- petición

    @Test
    fun `la peticion de fabrica lleva el id y el titulo`() {
        val prompt = AgentRequest.prompt("", task("Arreglar el login\n\nEl detalle no va"))

        assertTrue(prompt, prompt.startsWith("Work on the Tasklane task t-1: \"Arreglar el login\"."))
        assertTrue(prompt, "tasklane_get_task" in prompt)
        assertTrue("el cuerpo no va en la peticion", "detalle" !in prompt)
    }

    @Test
    fun `la peticion propia sustituye sus marcadores`() {
        assertEquals(
            "Haz la tarea t-1 (Arreglar el login) de Tasklane",
            AgentRequest.prompt("Haz la tarea {id} ({title}) de Tasklane", task("Arreglar el login")),
        )
    }

    @Test
    fun `la peticion es una linea y sin caracteres de control`() {
        // Sin el ESC, lo que queda de la secuencia es texto: la terminal ya no lo interpreta.
        assertEquals(
            "Mira esto: uno[2J dos",
            AgentRequest.prompt("Mira\nesto:\t{title}", task("uno\u0003\u001b[2J dos")),
        )
    }

    @Test
    fun `un titulo con marcadores no se vuelve a sustituir`() {
        assertEquals(
            "t-1: Quitar {id} y {title} del correo",
            AgentRequest.prompt("{id}: {title}", task("Quitar {id} y {title} del correo")),
        )
    }

    // ---------------------------------------------------------------- orden

    @Test
    fun `la orden de fabrica entrecomilla la peticion`() {
        val t = task("Arreglar el login")

        assertEquals(
            "claude 'Hola, t-1'",
            AgentRequest.command(AgentRequest.DEFAULT_COMMAND, "Hola, t-1", t, Shell.POSIX),
        )
    }

    @Test
    fun `sin marcador la peticion va al final`() {
        assertEquals("codex --full-auto 'haz t-1'", AgentRequest.command("codex --full-auto", "haz t-1", task("x"), Shell.POSIX))
    }

    @Test
    fun `la orden tambien acepta id y titulo entrecomillados`() {
        assertEquals(
            "agent --name 'it'\\''s' --id 't-1' 'p'",
            AgentRequest.command("agent --name {title} --id {id} {prompt}", "p", task("it's"), Shell.POSIX),
        )
    }

    @Test
    fun `una orden en blanco no abre nada`() {
        assertNull(AgentRequest.command("   ", "p", task("x"), Shell.POSIX))
    }

    @Test
    fun `un titulo con el marcador no rompe las comillas`() {
        assumeTrue(File("/bin/sh").exists())
        val t = task("Quitar {title} y \$(echo PWNED)")
        val prompt = AgentRequest.prompt("{title}: {title}", t)

        val line = AgentRequest.command("printf %s {prompt}", prompt, t, Shell.POSIX)!!

        assertEquals("Quitar {title} y \$(echo PWNED): Quitar {title} y \$(echo PWNED)", run("/bin/sh", line))
    }

    // --------------------------------------------------------- comillas

    @Test
    fun `posix deja pasar cualquier texto literal`() {
        assumeTrue(File("/bin/sh").exists())
        val nasty = "it's \$HOME `id` \$(date) !! \\n \"dobles\" ; & | > *"
        val line = "printf %s ${Shell.POSIX.quote(nasty)}"

        assertEquals(nasty, run("/bin/sh", line))
        if (File("/bin/zsh").exists()) assertEquals(nasty, run("/bin/zsh", line))
        if (File("/bin/bash").exists()) assertEquals(nasty, run("/bin/bash", line))
    }

    @Test
    fun `fish escapa la barra y la comilla`() {
        assertEquals("'a\\\\b \\'c\\''", Shell.FISH.quote("a\\b 'c'"))
    }

    @Test
    fun `powershell dobla tambien las comillas tipograficas`() {
        assertEquals("'it''s \u2019\u2019ok\u2019\u2019 \$HOME'", Shell.POWERSHELL.quote("it's \u2019ok\u2019 \$HOME"))
    }

    @Test
    fun `cmd no deja una comilla doble dentro`() {
        assertEquals("\"di 'hola' & adios\"", Shell.CMD.quote("di \"hola\" & adios"))
    }

    @Test
    fun `la shell sale del ejecutable`() {
        assertEquals(Shell.POSIX, Shell.of("/bin/zsh"))
        assertEquals(Shell.POSIX, Shell.of("C:\\Program Files\\Git\\bin\\bash.exe"))
        assertEquals(Shell.FISH, Shell.of("/opt/homebrew/bin/fish"))
        assertEquals(Shell.POWERSHELL, Shell.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe"))
        assertEquals(Shell.POWERSHELL, Shell.of("powershell.exe"))
        assertEquals(Shell.CMD, Shell.of("C:\\Windows\\System32\\cmd.exe"))
        assertEquals(Shell.POSIX, Shell.of("nu"))
    }

    // ------------------------------------------------------------- pestaña

    @Test
    fun `la pestana lleva el programa y el titulo`() {
        assertEquals("claude \u00b7 Arreglar el login", AgentRequest.tabName("claude {prompt}", task("Arreglar el login")))
        assertEquals("gemini \u00b7 x", AgentRequest.tabName("FOO=1 /usr/local/bin/gemini -i {prompt}", task("x")))
        assertEquals("agent \u00b7 x", AgentRequest.tabName("{prompt}", task("x")))
    }

    @Test
    fun `un titulo largo se corta en la pestana`() {
        val name = AgentRequest.tabName("claude", task("a".repeat(100)))

        assertEquals("claude \u00b7 " + "a".repeat(39) + "\u2026", name)
    }

    // ----------------------------------------------------------- en curso

    @Test
    fun `de fabrica pasa de ToDo a Doing`() {
        assertEquals(TasklaneConfig.DOING, AgentRequest.workingState(TasklaneConfig.DEFAULT, TasklaneConfig.TODO))
    }

    @Test
    fun `lo que ya esta en curso se queda`() {
        assertNull(AgentRequest.workingState(TasklaneConfig.DEFAULT, TasklaneConfig.DOING))
    }

    @Test
    fun `lo cerrado vuelve a en curso`() {
        assertEquals(TasklaneConfig.DOING, AgentRequest.workingState(TasklaneConfig.DEFAULT, TasklaneConfig.DONE))
    }

    @Test
    fun `lo que va mas alla de en curso se queda`() {
        val review = StateId("s-review")
        val config = TasklaneConfig.DEFAULT.copy(
            states = listOf(
                TaskState(TasklaneConfig.TODO, "ToDo", 0, isDefault = true),
                TaskState(TasklaneConfig.DOING, "Doing", 1),
                TaskState(review, "Review", 2),
                TaskState(TasklaneConfig.DONE, "Done", 3, terminal = true),
            ),
        )

        assertNull(AgentRequest.workingState(config, review))
    }

    @Test
    fun `en curso es el primero abierto despues del de por defecto`() {
        val backlog = StateId("s-backlog")
        val config = TasklaneConfig.DEFAULT.copy(
            states = listOf(
                TaskState(backlog, "Backlog", 0),
                TaskState(TasklaneConfig.TODO, "ToDo", 1, isDefault = true),
                TaskState(TasklaneConfig.DOING, "Doing", 2),
                TaskState(TasklaneConfig.DONE, "Done", 3, terminal = true),
            ),
        )

        assertEquals(TasklaneConfig.DOING, AgentRequest.workingState(config, backlog))
        assertEquals(TasklaneConfig.DOING, AgentRequest.workingState(config, StateId("borrado")))
    }

    @Test
    fun `sin estado abierto detras del de por defecto no se mueve`() {
        val config = TasklaneConfig.DEFAULT.copy(
            states = listOf(
                TaskState(TasklaneConfig.TODO, "ToDo", 0, isDefault = true),
                TaskState(TasklaneConfig.DONE, "Done", 1, terminal = true),
            ),
        )

        assertNull(AgentRequest.workingState(config, TasklaneConfig.TODO))
    }

    // ------------------------------------------------------------ ayudas

    /**
     * Lo que imprime [line] ejecutada por [shell] de verdad: es la única forma de saber que la
     * shell lee lo entrecomillado como un solo argumento literal. En Windows no hay `/bin/sh` y
     * estos tests se saltan.
     */
    private fun run(shell: String, line: String): String {
        val process = ProcessBuilder(shell, "-c", line).redirectErrorStream(true).start()
        process.waitFor(10, TimeUnit.SECONDS)
        return process.inputStream.bufferedReader().readText()
    }
}
