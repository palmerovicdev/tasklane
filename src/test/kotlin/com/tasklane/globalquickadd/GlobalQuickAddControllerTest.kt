package com.tasklane.globalquickadd

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class GlobalQuickAddControllerTest {
    private val shortcut = GlobalShortcut("R", true, true, false, false)
    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
    private class Backend : GlobalHotkeyBackend {
        var opens = 0
        var closes = 0
        var fail = false
        var callback: ((String?) -> Unit)? = null
        override fun register(shortcut: GlobalShortcut, activated: (String?) -> Unit): GlobalHotkeyRegistration {
            opens++
            if (fail) throw GlobalHotkeyUnavailable("Already reserved")
            callback = activated
            return object : GlobalHotkeyRegistration {
                override val description = shortcut.display()
                override fun close() { closes++ }
            }
        }
    }
    @Test fun `disabled makes no native calls and enabling reports active`() {
        val queue = Queue(); val backend = Backend()
        val controller = GlobalQuickAddController(queue, { backend }, {})
        controller.configure(null); queue.drain()
        assertEquals(0, backend.opens)
        controller.configure(shortcut); queue.drain()
        assertEquals(1, backend.opens)
        assertTrue(controller.status.startsWith("Active:"))
    }
    @Test fun `disable rejects callbacks immediately and releases registration`() {
        val queue = Queue(); val backend = Backend(); var calls = 0
        val controller = GlobalQuickAddController(queue, { backend }, { calls++ })
        controller.configure(shortcut); queue.drain()
        val old = backend.callback!!
        old(null); assertEquals(1, calls)
        controller.configure(null)
        old(null); assertEquals(1, calls)
        queue.drain(); assertEquals(1, backend.closes)
        assertEquals("Disabled", controller.status)
    }
    @Test fun `rapid configuration skips stale registrations and close is terminal`() {
        val queue = Queue(); val backend = Backend()
        val controller = GlobalQuickAddController(queue, { backend }, {})
        controller.configure(shortcut); controller.configure(shortcut.copy(key = "T")); queue.drain()
        assertEquals(1, backend.opens)
        controller.close(); controller.configure(shortcut); queue.drain()
        assertEquals(1, backend.opens); assertEquals(1, backend.closes)
    }
    @Test fun `native rejection is visible and retry can succeed`() {
        val queue = Queue(); val backend = Backend().apply { fail = true }
        val controller = GlobalQuickAddController(queue, { backend }, {})
        controller.configure(shortcut); queue.drain()
        assertEquals("Unavailable: Already reserved", controller.status)
        backend.fail = false; controller.configure(shortcut); queue.drain()
        assertTrue(controller.status.startsWith("Active:"))
    }
    @Test fun `invalid shortcuts never reach backend`() {
        val queue = Queue(); val backend = Backend()
        val controller = GlobalQuickAddController(queue, { backend }, {})
        controller.configure(shortcut.copy(key = "ENTER")); queue.drain()
        assertEquals(0, backend.opens); assertTrue(controller.status.startsWith("Unavailable:"))
    }
    @Test fun `queued UI activation is discarded after reconfiguration`() {
        val worker = Queue(); val ui = Queue(); val backend = Backend(); var calls = 0
        val controller = GlobalQuickAddController(worker, { backend }, { calls++ }, ui)
        controller.configure(shortcut); worker.drain()
        backend.callback!!(null)
        controller.configure(null)
        ui.drain(); worker.drain()
        assertEquals(0, calls)
    }
    @Test fun `repeated native activations queue only one dialog`() {
        val worker = Queue(); val ui = Queue(); val backend = Backend(); var calls = 0
        val controller = GlobalQuickAddController(worker, { backend }, { calls++ }, ui)
        controller.configure(shortcut); worker.drain()
        repeat(5) { backend.callback!!(null) }
        ui.drain()
        assertEquals(1, calls)
        backend.callback!!(null); ui.drain()
        assertEquals(2, calls)
    }
}
