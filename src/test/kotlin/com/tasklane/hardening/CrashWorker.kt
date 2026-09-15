package com.tasklane.hardening

import com.tasklane.bench.SyntheticCorpus
import com.tasklane.data.attachment.AttachmentStore
import com.tasklane.data.attachment.BlobRecord
import com.tasklane.data.attachment.BlobSweeper
import com.tasklane.data.sqlite.StoreRecovery
import com.tasklane.data.sqlite.TaskDb
import com.tasklane.data.sqlite.TaskImport
import com.tasklane.data.sqlite.TaskStore
import com.tasklane.data.store.StorageLayout
import com.tasklane.domain.command.Mutation
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import java.nio.file.Path
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Lo que hace el proceso al que se mata. Cada modo ejecuta **el bucle de producción** —el
 * almacén, la migración, el recolector, la copia— y va diciendo por su salida por dónde
 * va, para que el padre elija el momento.
 *
 * Nada de aquí imita a producción: si una prueba de caída pasa sobre una copia del bucle,
 * no dice nada del bucle.
 */
internal object CrashWorker {

    const val MAIN = "com.tasklane.hardening.CrashWorker"

    @JvmStatic
    fun main(args: Array<String>) {
        // Un hijo huérfano —el padre murió antes de matarlo— no puede quedarse escribiendo
        // en un temporal para siempre.
        thread(isDaemon = true) {
            Thread.sleep(MAX_LIFE_MILLIS)
            say("TIMEOUT")
            Runtime.getRuntime().halt(3)
        }
        try {
            when (args[0]) {
                "commands" -> commands(Path.of(args[1]))
                "migration" -> migration(Path.of(args[1]), Path.of(args[2]))
                "gc" -> gc(Path.of(args[1]), Path.of(args[2]), Instant.parse(args[3]))
                "backup" -> backup(Path.of(args[1]), Path.of(args[2]))
                "recover" -> recover(Path.of(args[1]), Path.of(args[2]))
                else -> error("modo desconocido: ${args[0]}")
            }
            say("EXIT")
        } catch (e: Throwable) {
            e.printStackTrace(System.out)
            say("ERROR $e")
            System.out.flush()
            exitProcess(2)
        }
        exitProcess(0)
    }

    fun say(line: String) {
        println(line)
        System.out.flush()
    }

    // ------------------------------------------------------------------- comandos

    private fun commands(dir: Path) {
        val db = TaskDb.open(dir) ?: error("no se pudo abrir la base en $dir")
        val store = TaskStore(db)
        var k = (store.chore(Commands.CHORE)?.n ?: 0L).toInt()
        while (true) {
            k++
            val mutations = Commands.mutations(k)
            say("BEGIN $k")
            val start = System.nanoTime()
            Commands.apply(store, k, mutations)
            say("COMMIT $k ${(System.nanoTime() - start) / 1_000_000}")
        }
    }

    // ------------------------------------------------------------------ migración

    private fun migration(dir: Path, xml: Path) {
        val db = TaskDb.open(dir) ?: error("no se pudo abrir la base en $dir")
        val store = TaskStore(db)
        val outcome = TaskImport.run(store, xml, Commands.REPO, Commands.CONFIG) { written -> say("CHUNK $written") }
        say("DONE ${outcome.result}")
    }

    // ------------------------------------------------------------------ recolector

    private fun gc(dir: Path, attachments: Path, now: Instant) {
        val db = TaskDb.open(dir) ?: error("no se pudo abrir la base en $dir")
        val store = TaskStore(db)
        var deleted = 0
        val swept = BlobSweeper(AttachmentStore(StorageLayout(attachments)), Ledger(store))
            .collect(Commands.REPO, now) { if (++deleted % 50 == 0) say("DELETED $deleted") }
        say("DONE ${swept.files}")
    }

    // ---------------------------------------------------------------------- copia

    private fun backup(dir: Path, target: Path) {
        val db = TaskDb.open(dir) ?: error("no se pudo abrir la base en $dir")
        say("START")
        val bytes = db.backupTo(target)
        say("DONE $bytes")
    }

    // ---------------------------------------------------------------- recuperación

    private fun recover(dir: Path, backup: Path) {
        val outcome = StoreRecovery.recover(dir, backup) { phase -> say("PHASE $phase") }
        say("DONE $outcome")
    }

    /** Veinte minutos: lo máximo que un escenario puede tardar antes de darlo por colgado. */
    private const val MAX_LIFE_MILLIS = 20L * 60 * 1000
}

/**
 * El escenario de comandos, compartido entre el hijo que lo ejecuta y el padre que lo
 * comprueba. Determinista: la transacción `k` es siempre la misma.
 *
 * Cada transacción **crea** una tanda nueva, **modifica** la anterior —la completa y la
 * marca— y **borra** la de antes de ésa, y apunta en `chore` que llegó a `k`. Así, mirando
 * sólo la base después de una muerte, se sabe exactamente qué tiene que haber: si la
 * última transacción confirmada es `K`, la tanda `K` abierta, la `K-1` completada y
 * marcada, y de las demás nada. Cualquier otra cosa es una transacción a medias.
 */
internal object Commands {

    val REPO = RepoKey("root")
    val CONFIG: TasklaneConfig = TasklaneConfig.DEFAULT.normalized()
    const val CHORE = "crash.commands"

    /** Tareas por tanda. Con el cuerpo de la especificación, ~60 ms de transacción. */
    const val PER_BATCH = 200

    fun id(k: Int, i: Int) = TaskId("k%05d-%03d".format(k, i))

    fun prefix(k: Int) = "k%05d-".format(k)

    fun created(k: Int): List<Task> = (0 until PER_BATCH).map { i ->
        SyntheticCorpus.task(k * 1_000 + i, REPO, config = CONFIG)
            .copy(id = id(k, i), stateId = TasklaneConfig.TODO, completedAt = null, bookmarked = false)
    }

    fun mutations(k: Int): List<Mutation> = buildList {
        add(Mutation.Upsert(created(k)))
        if (k >= 2) {
            add(
                Mutation.Upsert(
                    created(k - 1).map { it.copy(stateId = TasklaneConfig.DONE, completedAt = it.updatedAt, bookmarked = true) },
                ),
            )
        }
        if (k >= 3) add(Mutation.Delete(created(k - 2).map { it.id }))
    }

    fun apply(store: TaskStore, k: Int, mutations: List<Mutation> = mutations(k)) {
        store.write {
            store.apply(CONFIG, mutations)
            store.saveChore(CHORE, k.toLong(), k.toLong())
        }
    }
}

/** El libro del recolector sobre el almacén, sin servicio. El de producción es el mismo con `TaskService` delante. */
internal class Ledger(private val store: TaskStore) : BlobSweeper.Ledger {
    override fun collectible(repo: RepoKey, before: Instant, limit: Int): List<BlobRecord> =
        store.collectibleBlobs(repo, before.toEpochMilli(), limit)

    override fun referencedAmong(repo: RepoKey, ids: List<AttachmentId>): Set<AttachmentId> =
        store.referencedAmong(repo, ids).mapTo(HashSet(), ::AttachmentId)

    override fun forget(repo: RepoKey, ids: List<AttachmentId>) = store.write { store.forgetBlobs(repo, ids) }

    override fun forgetBatch(repo: RepoKey): Int = store.forgetBlobRows(repo)
}
