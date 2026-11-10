package com.tasklane.globalquickadd

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.atomic.AtomicBoolean

/** An isolated XCB connection owns only this combination's passive grabs. */
internal class X11HotkeyBackend(
    private val displayName: String? = System.getenv("DISPLAY"),
    private val driverFactory: X11DriverFactory = NativeX11DriverFactory,
) : GlobalHotkeyBackend {
    override fun register(shortcut: GlobalShortcut, activated: (String?) -> Unit): GlobalHotkeyRegistration {
        if (displayName.isNullOrBlank()) throw GlobalHotkeyUnavailable("X11 display is unavailable.")
        shortcut.validate()
        val driver = try {
            driverFactory.open(displayName)
        } catch (failure: Exception) {
            throw GlobalHotkeyUnavailable("Could not connect to the X11 display.", failure)
        } catch (failure: LinkageError) {
            throw GlobalHotkeyUnavailable("The X11 shortcut requires libxcb.", failure)
        }
        try {
            driver.grab(shortcut)
            return X11Registration(driver, shortcut.display(), activated).also { it.start() }
        } catch (failure: GlobalHotkeyUnavailable) {
            driver.close()
            throw failure
        } catch (failure: Exception) {
            driver.close()
            throw GlobalHotkeyUnavailable("Could not register the X11 shortcut.", failure)
        } catch (failure: LinkageError) {
            driver.close()
            throw GlobalHotkeyUnavailable("The X11 shortcut requires a compatible libxcb.", failure)
        }
    }
}

internal fun interface X11DriverFactory { fun open(display: String): X11Driver }

internal interface X11Driver : AutoCloseable {
    fun grab(shortcut: GlobalShortcut)
    /** True for an activation, false for an ignored event, null when no event is ready. */
    fun pollActivation(): Boolean?
    override fun close()
}

private class X11Registration(
    private val driver: X11Driver,
    override val description: String,
    private val activated: (String?) -> Unit,
) : GlobalHotkeyRegistration {
    private val closed = AtomicBoolean()
    @Volatile override var failure: String? = null
        private set

    // After start, only this thread accesses or disconnects the native connection.
    private val thread = Thread({
        try {
            while (!closed.get()) {
                when (driver.pollActivation()) {
                    true -> if (!closed.get()) activated(null)
                    null -> Thread.sleep(20)
                    false -> Unit
                }
            }
        } catch (unavailable: GlobalHotkeyUnavailable) {
            if (!closed.get()) failure = unavailable.message
        } catch (_: Exception) {
            if (!closed.get()) failure = "The X11 shortcut is no longer active."
        } catch (_: LinkageError) {
            if (!closed.get()) failure = "The X11 shortcut requires a compatible libxcb."
        } finally {
            driver.close()
        }
    }, "tasklane-x11-global-shortcut").apply { isDaemon = true }

    fun start() = thread.start()

    override fun close() {
        closed.set(true)
        if (Thread.currentThread() != thread) thread.join()
    }
}

private object NativeX11DriverFactory : X11DriverFactory {
    override fun open(display: String): X11Driver {
        val api = Native.load("xcb", Xcb::class.java)
        val handle = api.xcb_connect(display, null)
        if (api.xcb_connection_has_error(handle) != 0) {
            api.xcb_disconnect(handle)
            throw GlobalHotkeyUnavailable("Could not connect to the X11 display.")
        }
        return NativeX11Driver(api, handle)
    }
}

internal interface Xcb : Library {
    fun xcb_connect(display: String, screen: Pointer?): Pointer
    fun xcb_disconnect(connection: Pointer)
    fun xcb_connection_has_error(connection: Pointer): Int
    fun xcb_get_setup(connection: Pointer): Pointer
    fun xcb_setup_roots_iterator(setup: Pointer): XcbScreenIterator.ByValue
    fun xcb_screen_next(iterator: Pointer)
    fun xcb_get_keyboard_mapping(connection: Pointer, first: Byte, count: Byte): XcbCookie
    fun xcb_get_keyboard_mapping_reply(connection: Pointer, cookie: XcbCookie, error: Pointer?): Pointer?
    fun xcb_get_modifier_mapping(connection: Pointer): XcbCookie
    fun xcb_get_modifier_mapping_reply(connection: Pointer, cookie: XcbCookie, error: Pointer?): Pointer?
    fun xcb_grab_key_checked(connection: Pointer, ownerEvents: Byte, window: Int, modifiers: Short, key: Byte, pointerMode: Byte, keyboardMode: Byte): XcbCookie
    fun xcb_ungrab_keyboard_checked(connection: Pointer, time: Int): XcbCookie
    fun xcb_query_keymap(connection: Pointer): XcbCookie
    fun xcb_query_keymap_reply(connection: Pointer, cookie: XcbCookie, error: Pointer?): Pointer?
    fun xcb_request_check(connection: Pointer, cookie: XcbCookie): Pointer?
    fun xcb_poll_for_event(connection: Pointer): Pointer?
}

@Structure.FieldOrder("sequence")
internal class XcbCookie : Structure(), Structure.ByValue {
    @JvmField var sequence = 0
}

@Structure.FieldOrder("data", "remaining", "index")
internal open class XcbScreenIterator : Structure() {
    @JvmField var data: Pointer? = null
    @JvmField var remaining = 0
    @JvmField var index = 0

    class ByValue : XcbScreenIterator(), Structure.ByValue
}

internal class NativeX11Driver(private val xcb: Xcb, private val connection: Pointer) : X11Driver {
    private var held = false
    private var keycode = 0

    override fun grab(shortcut: GlobalShortcut) {
        val setup = xcb.xcb_get_setup(connection)
        // xcb_setup_t stores min_keycode and max_keycode at offsets 34 and 35.
        val first = setup.getByte(34).unsigned()
        val last = setup.getByte(35).unsigned()
        val count = last - first + 1
        val mapping = xcb.xcb_get_keyboard_mapping_reply(
            connection, xcb.xcb_get_keyboard_mapping(connection, first.toByte(), count.toByte()), null,
        ).owned { reply ->
            val symbolsPerKey = reply.getByte(1).unsigned()
            // Core XCB keyboard mapping replies have a 32-byte header.
            List(count) { index -> reply.getIntArray(32L + index * symbolsPerKey * 4L, symbolsPerKey).toList() }
        } ?: throw GlobalHotkeyUnavailable("Could not read the X11 keyboard mapping.")

        val keysym = if (shortcut.key.length == 1) shortcut.key[0].lowercaseChar().code
            else 0xffbd + shortcut.key.drop(1).toInt() // XK_F1 = 0xffbe
        val index = mapping.indexOfFirst { it.firstOrNull() == keysym }.takeIf { it >= 0 }
            ?: mapping.indexOfFirst { keysym in it }
        if (index < 0) throw GlobalHotkeyUnavailable("The selected key is not available on this X11 keyboard.")
        keycode = first + index

        val modifiers = xcb.xcb_get_modifier_mapping_reply(connection, xcb.xcb_get_modifier_mapping(connection), null)
            .owned { reply ->
                val keysPerModifier = reply.getByte(1).unsigned()
                List(8) { modifier ->
                    List(keysPerModifier) { key -> reply.getByte(32L + modifier * keysPerModifier + key).unsigned() }
                        .flatMap { mapping.getOrNull(it - first).orEmpty() }
                        .toSet()
                }
            } ?: throw GlobalHotkeyUnavailable("Could not read the X11 modifier mapping.")

        val masks = x11GrabMasks(shortcut, modifiers)
        val roots = xcb.xcb_setup_roots_iterator(setup)
        if (roots.remaining <= 0) throw GlobalHotkeyUnavailable("The X11 display has no usable screen.")
        while (roots.remaining > 0) {
            val root = checkNotNull(roots.data).getInt(0)
            for (mask in masks) {
                // SYNC queues subsequent keys on the server until we release the active grab.
                val cookie = xcb.xcb_grab_key_checked(
                    connection, 0, root, mask.toShort(), keycode.toByte(),
                    GRAB_MODE_ASYNC, GRAB_MODE_SYNC,
                )
                val error = xcb.xcb_request_check(connection, cookie).owned { it.getByte(1).unsigned() }
                when (error) {
                    null -> Unit
                    10 -> throw GlobalHotkeyUnavailable("The shortcut is already in use by another application.")
                    else -> throw GlobalHotkeyUnavailable("The X11 server refused the shortcut.")
                }
                checkConnection()
            }
            xcb.xcb_screen_next(roots.pointer)
            roots.read()
        }
    }

    override fun pollActivation(): Boolean? {
        checkConnection()
        val event = xcb.xcb_poll_for_event(connection)
        if (event == null) {
            if (held) held = isShortcutKeyDown()
            return null
        }
        return event.owned {
            // Ignore synthetic events and never process any key other than the configured one.
            val type = event.getByte(0).unsigned()
            // MappingNotify contains the request kind after response_type, pad and sequence.
            if (type == 34 && event.getByte(4).unsigned() < 2) {
                throw GlobalHotkeyUnavailable("The X11 keyboard mapping changed. Reapply the global shortcut.")
            }
            if (type != 2 || event.getByte(1).unsigned() != keycode) return@owned false

            // This releases only the active keyboard grab, not the configured passive grabs.
            // Ungrab before any callback (even on repeat) so unrelated keys go to the focused app.
            val ungrab = xcb.xcb_ungrab_keyboard_checked(connection, event.getInt(4))
            val error = xcb.xcb_request_check(connection, ungrab).owned { it.getByte(1).unsigned() }
            if (error != null) throw GlobalHotkeyUnavailable("The X11 server could not release the active shortcut grab.")
            checkConnection()
            val activated = !held
            held = true
            activated
        }
    }

    private fun isShortcutKeyDown(): Boolean =
        xcb.xcb_query_keymap_reply(connection, xcb.xcb_query_keymap(connection), null).owned { reply ->
            // QueryKeymap returns 32 bytes from offset 8. Inspect only the configured key's bit.
            val slot = reply.getByte(8L + keycode / 8).unsigned()
            slot and (1 shl (keycode % 8)) != 0
        } ?: throw GlobalHotkeyUnavailable("Could not read the X11 shortcut key state.")

    private fun checkConnection() {
        if (xcb.xcb_connection_has_error(connection) != 0) throw IllegalStateException("X11 connection lost")
    }

    override fun close() = xcb.xcb_disconnect(connection) // Releases active and passive grabs, including partial setup.

    private companion object {
        const val GRAB_MODE_SYNC: Byte = 0
        const val GRAB_MODE_ASYNC: Byte = 1
    }
}

private fun Byte.unsigned() = toInt() and 0xff

private inline fun <T> Pointer?.owned(read: (Pointer) -> T): T? {
    if (this == null) return null
    try {
        return read(this)
    } finally {
        Native.free(Pointer.nativeValue(this))
    }
}

internal fun x11GrabMasks(shortcut: GlobalShortcut, modifiers: List<Set<Int>>): Set<Int> {
    fun maskFor(vararg symbols: Int): Int {
        val matching = modifiers.indices.filter { index -> symbols.any { it in modifiers[index] } }
        if (matching.size != 1) throw GlobalHotkeyUnavailable("An X11 shortcut modifier is missing or ambiguous.")
        return 1 shl matching.single()
    }
    val required = listOfNotNull(
        if (shortcut.shift) maskFor(0xffe1, 0xffe2) else null, // Shift_L/R
        if (shortcut.control) maskFor(0xffe3, 0xffe4) else null, // Control_L/R
        if (shortcut.alt) maskFor(0xffe9, 0xffea) else null, // Alt_L/R
        if (shortcut.meta) maskFor(0xffeb, 0xffec) else null, // Super_L/R
    )
    if (required.distinct().size != required.size) throw GlobalHotkeyUnavailable("X11 shortcut modifiers overlap.")
    val base = required.fold(0, Int::or)
    val locks = setOf(0xffe5, 0xffe6, 0xff7f, 0xff14) // Caps_Lock, Shift_Lock, Num_Lock, Scroll_Lock
    return modifiers.withIndex().fold(setOf(base)) { masks, (index, symbols) ->
        // A slot shared with a regular modifier cannot safely be ignored.
        if (symbols.any { it in locks } && symbols.all { it == 0 || it in locks }) {
            masks + masks.map { it or (1 shl index) }
        } else masks
    }
}
