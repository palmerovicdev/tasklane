package com.tasklane.hardening

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Otro proceso Java, con el mismo classpath y las mismas propiedades que el test, al que
 * se puede **matar**.
 *
 * Es la herramienta del §6.1 del plan de escala. Hasta la Fase 5 las caídas se
 * *simulaban*: copiar la base y su diario con las conexiones abiertas, que es lo que
 * quedaría en disco si el proceso muriera en ese instante. Sirve para comprobar la señal
 * del cierre sucio, pero no prueba nada sobre lo que importa —qué pasa si la muerte llega
 * **a mitad de escribir**—, porque la copia se hace entre dos sentencias, justo cuando no
 * hay nada a medias. Aquí el proceso muere de verdad (`SIGKILL` en Unix,
 * `TerminateProcess` en Windows): sin `finally`, sin *shutdown hooks* y sin que SQLite
 * pueda cerrar nada.
 *
 * **Mismas propiedades que el test, y no es un detalle.** La nativa de SQLite la busca la
 * plataforma a partir de `idea.home.path` y compañía, que pone el plugin de Gradle en la
 * JVM de test; el hijo hereda los `inputArguments` del padre y así la encuentra en local
 * y en CI igual. El classpath va en un *argfile*: son ~150 KB, y Windows no admite una
 * línea de órdenes de más de 32.
 *
 * El hijo habla por su salida estándar, una línea por hito, y el padre decide cuándo matar
 * esperando la línea que le interesa.
 */
internal class ChildJvm private constructor(private val process: Process, private val argfile: Path) : java.io.Closeable {

    private val queue = LinkedBlockingQueue<String>()

    /** Todo lo que el hijo escribió, para el mensaje de un fallo. */
    val output: MutableList<String> = Collections.synchronizedList(ArrayList())

    init {
        thread(isDaemon = true, name = "child-jvm-output") {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        output += line
                        queue.put(line)
                    }
                }
            }
            queue.put(END)
        }
    }

    /**
     * Espera a la primera línea que cumpla [predicate] y la devuelve. Falla —con todo lo que
     * el hijo dijo— si se acaba el tiempo o el hijo termina antes.
     */
    fun await(timeout: Duration = 60.seconds, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (true) {
            val left = deadline - System.nanoTime()
            val line = queue.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                ?: throw AssertionError("el hijo no dijo lo esperado en $timeout:\n${transcript()}")
            if (line === END) throw AssertionError("el hijo terminó sin decir lo esperado:\n${transcript()}")
            if (predicate(line)) return line
        }
    }

    /** Lo mata sin avisar y espera a que el sistema lo dé por muerto. */
    fun kill() {
        process.destroyForcibly()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "el hijo no murió" }
        // Lo que llegó a escribir antes de morir también cuenta: un «COMMIT» impreso es
        // una transacción que terminó.
        repeat(50) {
            if (queue.contains(END)) return
            Thread.sleep(10)
        }
    }

    /** Espera a que termine solo. */
    fun awaitExit(timeout: Duration = 120.seconds): Int {
        check(process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) { "el hijo no terminó:\n${transcript()}" }
        return process.exitValue()
    }

    fun transcript(): String = synchronized(output) { output.takeLast(TRANSCRIPT_LINES).joinToString("\n") }

    override fun close() {
        if (process.isAlive) process.destroyForcibly()
        runCatching { Files.deleteIfExists(argfile) }
    }

    companion object {
        private val END = "\u0000fin-del-hijo"
        private const val TRANSCRIPT_LINES = 60

        /** Opciones del padre que no tienen sentido en el hijo, o que hay que poner distintas. */
        private val DROPPED = listOf("-agentlib", "-javaagent", "-Xmx", "-Xms", "-XX:+HeapDumpOnOutOfMemoryError", "-Dorg.gradle.")

        fun start(main: String, vararg args: String, heap: String = "512m"): ChildJvm {
            val windows = System.getProperty("os.name").startsWith("Windows")
            val java = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
            val options = ManagementFactory.getRuntimeMXBean().inputArguments
                .filterNot { option -> DROPPED.any(option::startsWith) }
            val argfile = Files.createTempFile("tasklane-child", ".args")
            Files.writeString(
                argfile,
                (options + "-Xmx$heap" + "-cp" + System.getProperty("java.class.path"))
                    .joinToString("\n", transform = ::quote),
            )
            val process = ProcessBuilder(listOf(java.toString(), "@$argfile", main) + args)
                .redirectErrorStream(true)
                .start()
            return ChildJvm(process, argfile)
        }

        /** Una opción entre comillas, como la lee el lanzador de Java en un *argfile*. */
        private fun quote(option: String): String =
            "\"" + option.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
