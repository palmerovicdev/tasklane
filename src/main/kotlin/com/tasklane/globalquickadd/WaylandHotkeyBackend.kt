package com.tasklane.globalquickadd

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.Variant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Uses only the compositor's GlobalShortcuts portal; it deliberately has no XWayland fallback. */
internal class WaylandHotkeyBackend(
    private val waylandDisplay: String? = System.getenv("WAYLAND_DISPLAY"),
    private val portalFactory: GlobalShortcutsPortalFactory = SystemGlobalShortcutsPortalFactory,
) : GlobalHotkeyBackend {
    override fun register(shortcut: GlobalShortcut, activated: (String?) -> Unit): GlobalHotkeyRegistration {
        if (waylandDisplay.isNullOrBlank()) throw GlobalHotkeyUnavailable("Wayland is not active.")
        shortcut.validate()
        val portal = try {
            portalFactory.connect()
        } catch (failure: Exception) {
            throw GlobalHotkeyUnavailable("The desktop Global Shortcuts portal is unavailable.", failure)
        } catch (failure: LinkageError) {
            throw GlobalHotkeyUnavailable("D-Bus support for the desktop Global Shortcuts portal is unavailable.", failure)
        }

        val closed = AtomicBoolean()
        try {
            val bound = portal.bind(shortcut.toPortalTrigger()) { token -> if (!closed.get()) activated(token) }
            if (bound.effectiveTrigger.isBlank()) {
                throw GlobalHotkeyUnavailable("The desktop did not return an effective shortcut trigger.")
            }
            return object : GlobalHotkeyRegistration {
                override val description = bound.effectiveTrigger
                override val failure: String? get() = portal.failure

                override fun close() {
                    if (closed.compareAndSet(false, true)) portal.close()
                }
            }
        } catch (failure: Exception) {
            closed.set(true)
            runCatching { portal.close() }
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is GlobalHotkeyUnavailable) throw failure
            throw GlobalHotkeyUnavailable("The desktop did not register the global shortcut.", failure)
        } catch (failure: LinkageError) {
            closed.set(true)
            runCatching { portal.close() }
            throw GlobalHotkeyUnavailable("D-Bus support for the desktop Global Shortcuts portal is unavailable.", failure)
        }
    }

    private fun GlobalShortcut.toPortalTrigger() = listOfNotNull(
        "CTRL".takeIf { control }, "ALT".takeIf { alt }, "SHIFT".takeIf { shift }, "LOGO".takeIf { meta },
        if (key.length == 1) key.lowercase() else key,
    ).joinToString("+")
}

/** One connection and portal session per registration. */
internal class DbusGlobalShortcutsPortal(
    private val bus: PortalBus,
    private val timeoutMillis: Long = 60_000,
) : GlobalShortcutsPortal {
    private val closed = AtomicBoolean()
    private val sessionClosed = AtomicBoolean()
    private var session: DBusPath? = null
    private val subscriptions = mutableListOf<AutoCloseable>()

    override val failure: String?
        get() = if (!closed.get() && (sessionClosed.get() || !bus.connected)) {
            "The desktop Global Shortcuts session is no longer active."
        } else null

    override fun bind(preferredTrigger: String, onActivated: (String?) -> Unit): BoundPortalShortcut {
        val sessionToken = token()
        val created = request { requestToken ->
            bus.createSession(mapOf("handle_token" to Variant(requestToken), "session_handle_token" to Variant(sessionToken)))
        }
        // The portal spec uses a string for session_handle for historical compatibility.
        val sessionPath = when (val handle = created["session_handle"]?.value) {
            is String -> handle
            is DBusPath -> handle.path
            else -> null
        }
        if (sessionPath != "$PORTAL_PATH/session/${bus.sender}/$sessionToken") {
            throw GlobalHotkeyUnavailable("The desktop portal returned an invalid shortcut session.")
        }
        val handle = DBusPath(sessionPath)
        session = handle
        subscriptions += bus.subscribeSessionClosed(handle) { sessionClosed.set(true) }
        val active = AtomicBoolean()
        subscriptions += bus.subscribeActivations { activation ->
            if (active.get() && !closed.get() && !sessionClosed.get() &&
                activation.sessionHandle.path == handle.path && activation.shortcutId == SHORTCUT_ID
            ) {
                onActivated((activation.options["activation_token"]?.value as? String)?.takeIf { it.isNotBlank() })
            }
        }
        val shortcuts = listOf(PortalShortcut(SHORTCUT_ID, mapOf(
            "description" to Variant("Open Tasklane Quick Add"),
            "preferred_trigger" to Variant(preferredTrigger),
        )))
        val bound = request { requestToken ->
            bus.bindShortcuts(handle, shortcuts, mapOf("handle_token" to Variant(requestToken)))
        }
        val trigger = effectiveTrigger(bound["shortcuts"]?.value)
        if (trigger.isNullOrBlank()) throw GlobalHotkeyUnavailable("The desktop did not grant a global shortcut.")
        if (failure != null) throw GlobalHotkeyUnavailable("The desktop closed the global shortcut session.")
        active.set(true)
        return BoundPortalShortcut(sessionPath, SHORTCUT_ID, trigger)
    }

    private fun request(begin: (String) -> DBusPath): Map<String, Variant<*>> {
        val requestToken = token()
        val expected = DBusPath("$PORTAL_PATH/request/${bus.sender}/$requestToken")
        val response = CompletableFuture<PortalRequest.Response>()
        var returned = expected
        var complete = false

        // Install the sender- and path-scoped match before calling: a portal may signal
        // before its method reply arrives. All GlobalShortcuts portals postdate handle_token.
        bus.subscribeResponses(expected) { response.complete(it) }.use {
            try {
                returned = begin(requestToken)
                if (returned.path != expected.path) {
                    throw GlobalHotkeyUnavailable("The desktop portal returned an unexpected shortcut request.")
                }
                val reply = response.get(timeoutMillis, TimeUnit.MILLISECONDS)
                complete = true
                when (reply.code.toInt()) {
                    0 -> return reply.results
                    1 -> throw GlobalHotkeyUnavailable("The global shortcut request was cancelled.")
                    else -> throw GlobalHotkeyUnavailable("The desktop did not grant a global shortcut.")
                }
            } catch (failure: TimeoutException) {
                throw GlobalHotkeyUnavailable("The desktop did not respond to the global shortcut request.", failure)
            } finally {
                if (!complete) runCatching { bus.closeRequest(returned) }
            }
        }
    }

    private fun effectiveTrigger(value: Any?): String? = (value as? List<*>)?.firstNotNullOfOrNull { entry ->
        // dbus-java decodes an untyped a(sa{sv}) inside Variant as rows of Object[].
        val row = entry as? Array<*> ?: return@firstNotNullOfOrNull null
        if (row.getOrNull(0) != SHORTCUT_ID) return@firstNotNullOfOrNull null
        val properties = row.getOrNull(1) as? Map<*, *>
        (properties?.get("trigger_description") as? Variant<*>)?.value as? String
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscriptions.asReversed().forEach { runCatching { it.close() } }
        try {
            if (!sessionClosed.get()) session?.let { runCatching { bus.closeSession(it) } }
        } finally {
            bus.close()
        }
    }

    private fun token() = "tasklane_" + UUID.randomUUID().toString().replace("-", "")

    private companion object { const val SHORTCUT_ID = "tasklane.quick-add" }
}
