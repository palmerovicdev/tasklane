package com.tasklane.globalquickadd

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Marshalling
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GlobalQuickAddLinuxTest {
    private val shortcut = GlobalShortcut("K", control = true, alt = true, shift = false, meta = false)

    @Test fun `X11 registration owns a passive grab and closes it exactly once`() {
        val driver = FakeX11Driver()
        val backend = X11HotkeyBackend(":test", X11DriverFactory { driver })
        val activated = CountDownLatch(1)
        val registration = backend.register(shortcut) { if (it == null) activated.countDown() }
        try {
            assertEquals("Ctrl+Alt+K", registration.description)
            assertTrue(activated.await(1, TimeUnit.SECONDS))
        } finally {
            registration.close()
        }
        registration.close()
        assertEquals(1, driver.closed)
        assertEquals(driver.pollThread, driver.closeThread)
    }

    @Test fun `X11 reports conflicts as unavailable and releases its display`() {
        val driver = FakeX11Driver(failGrab = true)
        val backend = X11HotkeyBackend(":test", X11DriverFactory { driver })
        assertThrows(GlobalHotkeyUnavailable::class.java) { backend.register(shortcut) {} }
        assertEquals(1, driver.closed)
    }

    @Test fun `X11 does not silently register without an X display`() {
        assertThrows(GlobalHotkeyUnavailable::class.java) {
            X11HotkeyBackend(null, X11DriverFactory { error("must not connect") }).register(shortcut) {}
        }
    }

    @Test fun `Wayland binds portal trigger and forwards activation token`() {
        val portal = FakePortal()
        val backend = WaylandHotkeyBackend("wayland-0", GlobalShortcutsPortalFactory { portal })
        var activation: String? = null
        val registration = backend.register(shortcut) { activation = it }
        assertEquals("CTRL+ALT+k", portal.preferred)
        assertEquals("Ctrl+Alt+K", registration.description)
        portal.activate("activation-token")
        assertEquals("activation-token", activation)
        registration.close()
        registration.close()
        portal.activate("after-close")
        assertEquals(1, portal.closed)
        assertEquals("activation-token", activation)
    }

    @Test fun `Wayland does not fall back to X11 when portal is unavailable`() {
        val backend = WaylandHotkeyBackend("wayland-0", GlobalShortcutsPortalFactory {
            throw IllegalStateException("portal absent")
        })
        val failure = assertThrows(GlobalHotkeyUnavailable::class.java) { backend.register(shortcut) {} }
        assertTrue(failure.message!!.contains("portal"))
    }

    @Test fun `Wayland unavailable when not running in a Wayland session`() {
        assertThrows(GlobalHotkeyUnavailable::class.java) {
            WaylandHotkeyBackend(null, GlobalShortcutsPortalFactory { error("must not connect") }).register(shortcut) {}
        }
    }

    @Test fun `Wayland portal cancellation is unavailable and closes the session`() {
        val portal = FakePortal(failBind = true)
        assertThrows(GlobalHotkeyUnavailable::class.java) {
            WaylandHotkeyBackend("wayland-0", GlobalShortcutsPortalFactory { portal }).register(shortcut) {}
        }
        assertEquals(1, portal.closed)
    }

    @Test fun `Wayland refuses an empty effective trigger`() {
        val portal = FakePortal(effective = "")
        assertThrows(GlobalHotkeyUnavailable::class.java) {
            WaylandHotkeyBackend("wayland-0", GlobalShortcutsPortalFactory { portal }).register(shortcut) {}
        }
        assertEquals(1, portal.closed)
    }

    @Test fun `X11 handles mapped lock slots without grabbing unrelated modifiers`() {
        val symbols = listOf(
            setOf(0xffe1), setOf(0xffe5), setOf(0xffe3), setOf(0xffe9),
            setOf(0xff7f), setOf(0xff14, 0xffeb), emptySet(), emptySet(),
        )
        assertEquals(setOf(12, 14, 28, 30), x11GrabMasks(shortcut, symbols))
    }

    @Test fun `X11 releases each active grab without taking other keys from the focused app`() {
        FakeXcb().use { server ->
            NativeX11Driver(server.api, Pointer(1)).use { driver ->
                driver.grab(shortcut)
                server.pressShortcut(100)
                server.typeUnrelatedKey()
                assertEquals(true, driver.pollActivation())
                assertEquals(listOf(100), server.releasedAt)
                assertEquals(1, server.foregroundKeyPresses)
                assertNull(driver.pollActivation())

                server.pressShortcut(200) // An auto-repeat can activate another passive grab.
                assertEquals(false, driver.pollActivation())
                assertEquals(listOf(100, 200), server.releasedAt)

                server.releaseShortcut()
                assertNull(driver.pollActivation())
                assertNull(driver.pollActivation())
                assertEquals(2, server.keymapReads) // No keyboard polling while the shortcut is idle.

                server.pressShortcut(300)
                assertEquals(true, driver.pollActivation())
                assertEquals(listOf(100, 200, 300), server.releasedAt)
            }
            assertEquals(1, server.disconnects)
        }
    }

    @Test fun `portal wire types match the published D-Bus interface`() {
        val methods = PortalGlobalShortcuts::class.java.declaredMethods.associateBy { it.name }
        assertEquals("a{sv}", Marshalling.getDBusType(methods.getValue("CreateSession").genericParameterTypes))
        assertEquals("oa(sa{sv})sa{sv}", Marshalling.getDBusType(methods.getValue("BindShortcuts").genericParameterTypes))
        assertEquals("o", Marshalling.getDBusType(methods.getValue("BindShortcuts").genericReturnType).single())
        val response = PortalRequest.Response("/response", UInt32(0), emptyMap())
        val activated = PortalGlobalShortcuts.Activated(PORTAL_PATH, DBusPath("/session"), "id", UInt64(0), emptyMap())
        val closed = PortalSession.Closed("/session", emptyMap())
        assertEquals("org.freedesktop.portal.Request", response.getInterface())
        assertEquals("ua{sv}", response.sig)
        assertEquals("org.freedesktop.portal.GlobalShortcuts", activated.getInterface())
        assertEquals("osta{sv}", activated.sig)
        assertEquals("org.freedesktop.portal.Session", closed.getInterface())
        assertEquals("a{sv}", closed.sig)
    }

    @Test fun `portal correlates early responses and accepts only its shortcut activations`() {
        val bus = FakePortalBus()
        val seen = mutableListOf<String?>()
        val registration = waylandOver(bus).register(shortcut) { seen += it }
        try {
            assertEquals("Ctrl+Alt+M", registration.description)
            assertEquals("CTRL+ALT+k", bus.preferredTrigger)
            assertEquals(2, bus.responseSubscriptionsClosed)
            bus.activate("/another_session", "tasklane.quick-add", "wrong-session")
            bus.activate(bus.sessionPath, "other-action", "wrong-action")
            bus.activate(bus.sessionPath, "tasklane.quick-add", "right-token")
            assertEquals(listOf("right-token"), seen)
            bus.closeByCompositor()
            assertTrue(registration.failure!!.contains("no longer active"))
            bus.activate(bus.sessionPath, "tasklane.quick-add", "session-closed")
            assertEquals(listOf("right-token"), seen)
        } finally {
            registration.close()
        }
        assertEquals(1, bus.closed)
        assertEquals(0, bus.sessionsClosed) // The compositor already closed it.
    }

    @Test fun `portal cancellation and empty grants close the session and connection`() {
        for ((code, grants) in listOf(1 to true, 2 to true, 0 to false)) {
            val bus = FakePortalBus(bindCode = code, grants = grants)
            val failure = assertThrows(GlobalHotkeyUnavailable::class.java) { waylandOver(bus).register(shortcut) {} }
            assertTrue(failure.message!!.contains(if (code == 1) "cancelled" else "did not grant"))
            assertEquals(2, bus.responseSubscriptionsClosed)
            assertEquals(1, bus.sessionsClosed)
            assertEquals(1, bus.closed)
        }
    }

    @Test fun `portal times out a silent bind and closes its outstanding request`() {
        val bus = FakePortalBus(silentBind = true)
        val failure = assertThrows(GlobalHotkeyUnavailable::class.java) { waylandOver(bus).register(shortcut) {} }
        assertTrue(failure.message!!.contains("did not respond"))
        assertEquals(bus.bindRequest, bus.requestsClosed.single())
        assertEquals(2, bus.responseSubscriptionsClosed)
        assertEquals(1, bus.sessionsClosed)
        assertEquals(1, bus.closed)
    }

    @Test fun `portal rejects a method reply with an unexpected request handle`() {
        val bus = FakePortalBus(mismatch = true)
        val failure = assertThrows(GlobalHotkeyUnavailable::class.java) { waylandOver(bus).register(shortcut) {} }
        assertTrue(failure.message!!.contains("unexpected"))
        assertEquals("/unexpected", bus.requestsClosed.single())
        assertEquals(1, bus.sessionsClosed)
        assertEquals(1, bus.closed)
    }

    private fun waylandOver(bus: PortalBus) =
        WaylandHotkeyBackend("wayland-0", GlobalShortcutsPortalFactory { DbusGlobalShortcutsPortal(bus, timeoutMillis = 10) })

    /** Models X11's sync freeze and the active grab's routing, without a real server. */
    private class FakeXcb : AutoCloseable {
        private val setup = Memory(40).apply { setByte(34, 20); setByte(35, 22) }
        private val root = Memory(4).apply { setInt(0, 42) }
        private val events = ArrayDeque<Pair<Int, Int>>()
        private var keyboardMode: Byte? = null
        private var active = false
        private var held = false
        private var queued = 0
        val releasedAt = mutableListOf<Int>()
        var foregroundKeyPresses = 0
        var keymapReads = 0
        var disconnects = 0

        val api = Proxy.newProxyInstance(Xcb::class.java.classLoader, arrayOf(Xcb::class.java)) { _, method, args ->
            when (method.name) {
                "xcb_get_setup" -> setup
                "xcb_get_keyboard_mapping", "xcb_get_modifier_mapping" -> XcbCookie()
                "xcb_get_keyboard_mapping_reply" -> reply(44) {
                    setByte(1, 1)
                    setInt(32, 'k'.code)
                    setInt(36, 0xffe3)
                    setInt(40, 0xffe9)
                }
                "xcb_get_modifier_mapping_reply" -> reply(40) {
                    setByte(1, 1)
                    setByte(34, 21)
                    setByte(35, 22)
                }
                "xcb_setup_roots_iterator" -> XcbScreenIterator.ByValue().apply {
                    data = root
                    remaining = 1
                    write()
                }
                "xcb_screen_next" -> { (args[0] as Pointer).setInt(Native.POINTER_SIZE.toLong(), 0); null }
                "xcb_grab_key_checked" -> {
                    assertEquals(1.toByte(), args[5]) // Pointer stays asynchronous.
                    keyboardMode = args[6] as Byte
                    XcbCookie()
                }
                "xcb_request_check" -> null
                "xcb_connection_has_error" -> 0
                "xcb_poll_for_event" -> events.removeFirstOrNull()?.let { (key, time) ->
                    reply(32) { setByte(0, 2); setByte(1, key.toByte()); setInt(4, time) }
                }
                "xcb_ungrab_keyboard_checked" -> {
                    assertTrue(active)
                    active = false
                    releasedAt += args[1] as Int
                    foregroundKeyPresses += queued
                    queued = 0
                    XcbCookie()
                }
                "xcb_query_keymap" -> { keymapReads++; XcbCookie() }
                "xcb_query_keymap_reply" -> reply(40) {
                    setByte(10, if (held) 0b00010000 else 0) // keycode 20 is bit 4 of keys[2].
                }
                "xcb_disconnect" -> { disconnects++; null }
                else -> error("Unexpected XCB call: ${method.name}")
            }
        } as Xcb

        fun pressShortcut(time: Int) {
            assertFalse(active)
            assertEquals(0.toByte(), keyboardMode)
            active = true
            held = true
            events += 20 to time
        }

        fun typeUnrelatedKey() {
            when {
                !active -> foregroundKeyPresses++
                keyboardMode == 0.toByte() -> queued++
                else -> events += 30 to 150
            }
        }

        fun releaseShortcut() { held = false }

        private fun reply(size: Long, fill: Pointer.() -> Unit) =
            Pointer(Native.malloc(size)).apply { clear(size); fill() }

        override fun close() { setup.close(); root.close() }
    }

    private class FakeX11Driver(private val failGrab: Boolean = false) : X11Driver {
        var closed = 0
        var pollThread: Thread? = null
        var closeThread: Thread? = null

        override fun grab(shortcut: GlobalShortcut) {
            if (failGrab) throw GlobalHotkeyUnavailable("conflict")
        }

        override fun pollActivation(): Boolean? {
            if (pollThread != null) return null
            pollThread = Thread.currentThread()
            return true
        }

        override fun close() {
            closeThread = Thread.currentThread()
            closed++
        }
    }

    private class FakePortalBus(
        private val bindCode: Int = 0,
        private val grants: Boolean = true,
        private val silentBind: Boolean = false,
        private val mismatch: Boolean = false,
    ) : PortalBus {
        override val sender = "1_321"
        override val connected: Boolean get() = closed == 0
        private val responses = mutableMapOf<String, (PortalRequest.Response) -> Unit>()
        private var activation: ((PortalGlobalShortcuts.Activated) -> Unit)? = null
        private var sessionClosed: (() -> Unit)? = null
        lateinit var sessionPath: String
        lateinit var bindRequest: String
        var preferredTrigger: Any? = null
        var responseSubscriptionsClosed = 0
        var sessionsClosed = 0
        var closed = 0
        val requestsClosed = mutableListOf<String>()

        override fun createSession(options: Map<String, Variant<*>>): DBusPath {
            sessionPath = "$PORTAL_PATH/session/$sender/${options.getValue("session_handle_token").value}"
            val request = requestPath(options)
            responses.getValue(request)(PortalRequest.Response(request, UInt32(0), mapOf("session_handle" to Variant(sessionPath))))
            return DBusPath(request)
        }

        override fun bindShortcuts(session: DBusPath, shortcuts: List<PortalShortcut>, options: Map<String, Variant<*>>): DBusPath {
            assertEquals(sessionPath, session.path)
            val shortcut = shortcuts.single()
            preferredTrigger = shortcut.properties.getValue("preferred_trigger").value
            bindRequest = requestPath(options)
            if (!silentBind) {
                val properties = mapOf("trigger_description" to Variant("Ctrl+Alt+M"))
                val rows = if (grants) listOf(arrayOf(shortcut.id, properties)) else emptyList()
                val results = mapOf("shortcuts" to Variant(rows, "a(sa{sv})"))
                responses.getValue(bindRequest)(PortalRequest.Response(bindRequest, UInt32(bindCode.toLong()), results))
            }
            return DBusPath(if (mismatch) "/unexpected" else bindRequest)
        }

        override fun subscribeResponses(path: DBusPath, response: (PortalRequest.Response) -> Unit): AutoCloseable {
            assertNull(responses.put(path.path, response))
            return AutoCloseable { responses.remove(path.path); responseSubscriptionsClosed++ }
        }

        override fun subscribeActivations(activated: (PortalGlobalShortcuts.Activated) -> Unit): AutoCloseable {
            activation = activated
            return AutoCloseable { activation = null }
        }

        override fun subscribeSessionClosed(path: DBusPath, closed: () -> Unit): AutoCloseable {
            assertEquals(sessionPath, path.path)
            sessionClosed = closed
            return AutoCloseable { sessionClosed = null }
        }

        fun activate(session: String, id: String, token: String) {
            activation?.invoke(PortalGlobalShortcuts.Activated(PORTAL_PATH, DBusPath(session), id, UInt64(1), mapOf("activation_token" to Variant(token))))
        }

        fun closeByCompositor() { sessionClosed?.invoke() }
        override fun closeRequest(path: DBusPath) { requestsClosed += path.path }
        override fun closeSession(path: DBusPath) { assertEquals(sessionPath, path.path); sessionsClosed++ }
        override fun close() { closed++ }
        private fun requestPath(options: Map<String, Variant<*>>) =
            "$PORTAL_PATH/request/$sender/${options.getValue("handle_token").value}"
    }

    private class FakePortal(
        private val failBind: Boolean = false,
        private val effective: String = "Ctrl+Alt+K",
    ) : GlobalShortcutsPortal {
        var preferred: String? = null
        var closed = 0
        private var activation: ((String?) -> Unit)? = null
        override fun bind(preferredTrigger: String, onActivated: (String?) -> Unit): BoundPortalShortcut {
            preferred = preferredTrigger
            if (failBind) throw IllegalStateException("request cancelled")
            activation = onActivated
            return BoundPortalShortcut("/session/test", "quick-add", effective)
        }
        fun activate(token: String?) { activation?.invoke(token) }
        override fun close() { closed++ }
    }
}
