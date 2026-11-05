package com.tasklane.globalquickadd

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** All handle operations use one serial executor; generation checks also guard queued UI delivery. */
internal class GlobalQuickAddController(
    private val worker: Executor,
    private val backend: () -> GlobalHotkeyBackend,
    private val activated: (String?) -> Unit,
    private val delivery: Executor = Executor { it.run() },
) : AutoCloseable {
    private val generation = AtomicLong()
    private val pendingDelivery = AtomicLong()
    @Volatile private var disposed = false
    @Volatile private var currentStatus = "Disabled"
    val status: String get() = registration?.failure?.let { "Unavailable: $it" } ?: currentStatus
    @Volatile private var registration: GlobalHotkeyRegistration? = null

    @Synchronized fun configure(shortcut: GlobalShortcut?) {
        if (disposed) return
        val version = generation.incrementAndGet()
        pendingDelivery.set(0)
        currentStatus = if (shortcut == null) "Disabled" else "Registering…"
        worker.execute {
            if (version != generation.get()) return@execute
            try {
                registration?.close()
                registration = null
                if (shortcut == null || disposed) return@execute
                shortcut.validate()
                val handle = backend().register(shortcut) { token ->
                    if (!disposed && version == generation.get() && pendingDelivery.compareAndSet(0, version)) {
                        delivery.execute {
                            try {
                                if (!disposed && version == generation.get()) activated(token)
                            } finally {
                                pendingDelivery.compareAndSet(version, 0)
                            }
                        }
                    }
                }
                if (disposed || version != generation.get()) handle.close()
                else {
                    registration = handle
                    currentStatus = "Active: ${handle.description}"
                }
            } catch (failure: Exception) {
                if (version == generation.get()) currentStatus = "Unavailable: ${failure.message ?: failure.javaClass.simpleName}"
            } catch (failure: LinkageError) {
                if (version == generation.get()) currentStatus = "Unavailable: native integration could not be loaded (${failure.javaClass.simpleName})"
            }
        }
    }

    @Synchronized override fun close() {
        if (disposed) return
        disposed = true
        generation.incrementAndGet()
        currentStatus = "Disabled"
        worker.execute {
            try { registration?.close() } finally { registration = null }
        }
    }
}
