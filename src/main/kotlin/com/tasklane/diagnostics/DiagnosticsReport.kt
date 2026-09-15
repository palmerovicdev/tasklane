package com.tasklane.diagnostics

import com.tasklane.diagnostics.TasklaneDiagnostics.humanBytes

/**
 * El informe de diagnóstico, en texto.
 *
 * Aparte del diálogo y **puro**, que es lo que permite fijarlo con un test sin
 * arrancar un IDE. No es ceremonia: este texto es lo que un usuario va a pegar en un
 * issue cuando diga que el plugin va lento, así que tiene que decir lo mismo en la
 * máquina de quien informa y en la de quien lo lee.
 *
 * Texto plano monoespaciado y no una tabla de Swing por lo mismo: se copia con
 * `⌘C`, se pega en GitHub dentro de un bloque de código y se lee igual.
 */
object DiagnosticsReport {

    fun render(report: TasklaneDiagnostics.Report): String = buildString {
        appendLine("Tasklane diagnostics")
        appendLine("=".repeat(72))
        appendLine()

        appendLine("Totals")
        appendLine("  Tasks            ${report.tasks}")
        appendLine("  Images on disk   ${report.blobCount}${missingNote(report)}")
        appendLine("  Images size      ${humanBytes(report.blobBytes)}${quotaNote(report)}")
        appendLine("  Data on disk     ${humanBytes(report.totalBytes)}")
        appendLine(
            "  Heap             ${humanBytes(report.heapUsedBytes)} used of " +
                humanBytes(report.heapMaxBytes),
        )
        // Lo que ya estaba guardado cuando el tope de escalado bajó a 400 px. No se
        // reescala —cambiaría el SHA, que es el nombre del fichero, y con él todas las
        // referencias de los cuerpos—, así que lo único honesto es decir cuánto es.
        if (report.oversizedBlobs > 0) {
            appendLine(
                "  Oversized        ${report.oversizedBlobs} image(s) above the " +
                    "${report.imageMaxSize} px cap, ${humanBytes(report.oversizedBytes)}",
            )
        }
        // Un informe que dijera «0 imágenes» de un directorio lleno mentiría. Mientras la
        // reconciliación no haya pasado, lo que la tabla sabe está incompleto y se dice.
        if (report.pending) {
            appendLine("  NOTE: image figures are still being reconciled; they may be incomplete.")
        }
        // La copia y la comprobación de la base (Fase 5): lo que hay que saber antes de
        // tocar nada a mano en `.idea/tasklane`.
        val store = report.store
        appendLine(
            "  Backup           " + (store.backupAt?.let { "${stamp(it)}, ${humanBytes(store.backupBytes)}" } ?: "none yet"),
        )
        appendLine(
            "  Integrity        " + when {
                store.pending -> "check pending (the IDE did not close the database cleanly)"
                store.checkedAt == null -> "never needed a check"
                store.problems > 0 -> "DAMAGED: ${store.problems} problem(s) found on ${stamp(store.checkedAt)}"
                else -> "ok, checked on ${stamp(store.checkedAt)}"
            },
        )
        appendLine()

        appendLine("Repositories")
        if (report.repos.isEmpty()) {
            appendLine("  (none loaded yet)")
        } else {
            // Orden por peso: quien abre esto porque algo va lento quiere ver primero
            // el repositorio que lo explica, no el primero por orden alfabetico.
            for (repo in report.repos.sortedByDescending { it.totalBytes }) {
                appendLine("  ${repo.repo.value}")
                appendLine("    tasks          ${repo.tasks}" + orphanNote(repo))
                appendLine("    body text      ${humanBytes(repo.bodyChars)} of characters")
                appendLine("    anchors / tags ${repo.anchors} / ${repo.tags}")
                appendLine(
                    "    image refs     ${repo.imageRefs} " +
                        "(${repo.distinctImages} distinct${dedupNote(repo)})",
                )
                appendLine(
                    "    blobs          ${repo.blobCount}, " +
                        humanBytes(repo.blobBytes) + unreferencedNote(repo) + repoMissingNote(repo),
                )
                if (repo.blobs.oversized > 0) {
                    appendLine(
                        "    oversized      ${repo.blobs.oversized}, " +
                            humanBytes(repo.blobs.oversizedBytes),
                    )
                }
                // Desde la Fase 3 lo que pesa es la base, y es UNA por proyecto: se le
                // atribuye al primer repositorio del informe para que el total no la
                // cuente N veces. Los ficheros XML que queden —el `.migrated` y el
                // `.bak`— se dicen aparte, porque son espacio que el usuario puede
                // recuperar en cuanto se fíe de la migración.
                if (repo.tasksFileBytes > 0) {
                    appendLine("    database       ${humanBytes(repo.tasksFileBytes)} (whole project)")
                }
                if (repo.backupBytes > 0) {
                    appendLine("    old xml files  ${humanBytes(repo.backupBytes)}")
                }
            }
        }
        appendLine()

        appendLine("Latency, last hour")
        if (report.latencies.isEmpty()) {
            appendLine("  (nothing measured yet)")
        } else {
            appendLine("  %-10s %8s %10s %10s %10s %10s".format("op", "count", "mean", "p50", "p99", "max"))
            for (sample in report.latencies) {
                appendLine(
                    "  %-10s %8d %9.1fms %9.0fms %9.0fms %9.1fms".format(
                        sample.op.name.lowercase(),
                        sample.count,
                        sample.meanMillis,
                        sample.p50Millis,
                        sample.p99Millis,
                        sample.maxMillis,
                    ),
                )
            }
            appendLine()
            appendLine("  p50 and p99 are histogram bucket ceilings: \"p99 64ms\" means \"under 64ms\".")
            // El unico numero con un limite duro. El resto se juzga en contexto; esto no.
            val render = report.latencies.firstOrNull { it.op == TasklaneMetrics.Op.RENDER }
            if (render != null && render.p99Millis > EDT_BUDGET_MILLIS) {
                appendLine(
                    "  WARNING: render runs on the EDT and its p99 is over ${EDT_BUDGET_MILLIS.toInt()}ms. " +
                        "That is a visible freeze on every repaint.",
                )
            }
        }
    }

    /** Un cuadro de mando de 60 Hz: por encima de esto se ve el tirón. */
    const val EDT_BUDGET_MILLIS = 16.0

    /** `2026-09-15 10:02`: una fecha que se lee igual en la máquina de quien informa y en la de quien lee. */
    private fun stamp(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString().replace('T', ' ')

    private fun orphanNote(repo: TasklaneDiagnostics.RepoReport): String =
        if (repo.orphans > 0) "  (${repo.orphans} parked on missing config)" else ""

    /**
     * Cuánto ahorra el direccionamiento por contenido. Es la cifra con la que se
     * razona el §1.6 —«con deduplicación 10:1»— y hasta ahora no se podía mirar.
     */
    private fun dedupNote(repo: TasklaneDiagnostics.RepoReport): String {
        if (repo.distinctImages == 0 || repo.imageRefs <= repo.distinctImages) return ""
        return ", %.1f:1 dedup".format(repo.imageRefs.toDouble() / repo.distinctImages)
    }

    private fun unreferencedNote(repo: TasklaneDiagnostics.RepoReport): String =
        if (repo.unreferencedBlobs > 0) ", ${repo.unreferencedBlobs} unreferenced" else ""

    private fun repoMissingNote(repo: TasklaneDiagnostics.RepoReport): String =
        if (repo.blobs.missing > 0) ", ${repo.blobs.missing} missing" else ""

    private fun missingNote(report: TasklaneDiagnostics.Report): String =
        if (report.missingBlobs > 0) " (${report.missingBlobs} missing from disk)" else ""

    /**
     * El peso frente a la cuota del §4.5. Es la cifra que el aviso vigila, y enseñarla
     * aquí es la mitad de la política: la otra mitad es que el aviso no borra nada.
     */
    private fun quotaNote(report: TasklaneDiagnostics.Report): String = when {
        report.quotaBytes <= 0 -> ""
        report.overQuota -> " of ${humanBytes(report.quotaBytes)} quota — OVER"
        else -> " of ${humanBytes(report.quotaBytes)} quota"
    }
}
