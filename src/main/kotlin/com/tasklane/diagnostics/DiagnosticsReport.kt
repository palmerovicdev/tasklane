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
        appendLine("  Images on disk   ${report.blobCount}${if (report.truncated) "+ (scan truncated)" else ""}")
        appendLine("  Images size      ${humanBytes(report.blobBytes)}")
        appendLine("  Data on disk     ${humanBytes(report.totalBytes)}")
        appendLine(
            "  Heap             ${humanBytes(report.heapUsedBytes)} used of " +
                humanBytes(report.heapMaxBytes),
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
                    "    blobs          ${repo.blobCount}${if (repo.blobsTruncated) "+" else ""}, " +
                        humanBytes(repo.blobBytes) + unreferencedNote(repo),
                )
                appendLine(
                    "    tasks.xml      ${humanBytes(repo.tasksFileBytes)}" +
                        if (repo.backupBytes > 0) " (+ ${humanBytes(repo.backupBytes)} backup)" else "",
                )
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
}
