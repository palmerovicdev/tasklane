package com.tasklane.globalquickadd

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal class WindowsHotkeyBackend(
    private val native: WindowsHotkeyNative = Win32HotkeyNative(),
    private val registrationTimeoutMs: Long = 5_000,
) : GlobalHotkeyBackend {
    override fun register(shortcut: GlobalShortcut, activated: (String?) -> Unit): GlobalHotkeyRegistration {
        shortcut.validate()
        val modifiers = modifiers(shortcut)
        val key = virtualKey(shortcut.key)
        val ready = CompletableFuture<Boolean>()
        val stop = CountDownLatch(1)
        val loopFailure = AtomicReference<String?>()
        val thread = Thread({
            try {
                native.run(modifiers, key, {
                    if (stop.count != 0L) activated(null)
                }, { ready.complete(it) }, stop)
                if (stop.count != 0L && ready.getNow(false)) {
                    loopFailure.set("Windows stopped receiving shortcut events.")
                }
            } catch (error: Exception) {
                if (!ready.completeExceptionally(error)) loopFailure.set(error.message ?: "Windows shortcut events failed.")
            } catch (error: LinkageError) {
                if (!ready.completeExceptionally(error)) loopFailure.set("Windows shortcut events could not be loaded.")
            }
        }, "global-hotkey-windows").apply { isDaemon = true; start() }

        var registered = false
        try {
            registered = ready.get(registrationTimeoutMs, TimeUnit.MILLISECONDS)
            if (!registered) {
                throw GlobalHotkeyUnavailable("This Windows shortcut is already in use or could not be registered.")
            }
        } catch (error: TimeoutException) {
            throw GlobalHotkeyUnavailable("Windows did not respond to shortcut registration.", error)
        } catch (error: ExecutionException) {
            throw GlobalHotkeyUnavailable("Windows shortcut registration failed.", error.cause)
        } finally {
            if (!registered) stop.countDown()
        }

        return object : GlobalHotkeyRegistration {
            override val description = shortcut.display()
            override val failure: String? get() = loopFailure.get()
            override fun close() {
                stop.countDown()
                thread.join(2_000)
                check(!thread.isAlive) { "Windows hotkey thread did not stop." }
            }
        }
    }

    private fun modifiers(shortcut: GlobalShortcut) = (if (shortcut.alt) MOD_ALT else 0) or
        (if (shortcut.control) MOD_CONTROL else 0) or (if (shortcut.shift) MOD_SHIFT else 0) or
        (if (shortcut.meta) MOD_WIN else 0) or MOD_NOREPEAT

    private fun virtualKey(key: String): Int = when {
        key == "F12" -> throw GlobalHotkeyUnavailable("F12 is reserved for the Windows debugger.")
        key.length == 1 -> key[0].code
        else -> 0x70 + key.drop(1).toInt() - 1
    }

    companion object {
        private const val MOD_ALT = 0x0001
        private const val MOD_CONTROL = 0x0002
        private const val MOD_SHIFT = 0x0004
        private const val MOD_WIN = 0x0008
        private const val MOD_NOREPEAT = 0x4000
    }
}

internal interface WindowsHotkeyNative {
    fun run(modifiers: Int, key: Int, onHotkey: () -> Unit, onReady: (Boolean) -> Unit, stop: CountDownLatch)
}

internal class Win32HotkeyNative(
    private val user32: User32 = Native.load("user32", User32::class.java),
) : WindowsHotkeyNative {
    override fun run(modifiers: Int, key: Int, onHotkey: () -> Unit, onReady: (Boolean) -> Unit, stop: CountDownLatch) {
        val msg = Msg()
        user32.PeekMessageW(msg, null, WM_HOTKEY, WM_HOTKEY, PM_NOREMOVE)
        if (stop.count == 0L) return
        val success = user32.RegisterHotKey(null, HOTKEY_ID, modifiers, key)
        try {
            onReady(success)
            if (!success) return
            while (stop.count != 0L) {
                if (user32.PeekMessageW(msg, null, WM_HOTKEY, WM_HOTKEY, PM_REMOVE)) {
                    if (msg.message == WM_QUIT) break
                    if (msg.wParam?.let(Pointer::nativeValue) == HOTKEY_ID.toLong()) onHotkey()
                } else {
                    // Wait for hotkey input without sharing a native thread ID with the closing thread.
                    val result = user32.MsgWaitForMultipleObjectsEx(0, null, 250, QS_HOTKEY, MWMO_INPUTAVAILABLE)
                    check(result != WAIT_FAILED) { "Windows could not wait for shortcut input." }
                }
            }
        } finally {
            if (success) check(user32.UnregisterHotKey(null, HOTKEY_ID)) { "Windows could not unregister the shortcut." }
        }
    }

    internal interface User32 : StdCallLibrary {
        fun RegisterHotKey(window: Pointer?, id: Int, modifiers: Int, key: Int): Boolean
        fun UnregisterHotKey(window: Pointer?, id: Int): Boolean
        fun PeekMessageW(msg: Msg, window: Pointer?, min: Int, max: Int, remove: Int): Boolean
        fun MsgWaitForMultipleObjectsEx(count: Int, handles: Pointer?, timeout: Int, wakeMask: Int, flags: Int): Int
    }

    @Structure.FieldOrder("window", "message", "wParam", "lParam", "time", "x", "y", "privateValue")
    internal class Msg : Structure() {
        @JvmField var window: Pointer? = null
        @JvmField var message = 0
        @JvmField var wParam: Pointer? = null
        @JvmField var lParam: Pointer? = null
        @JvmField var time = 0
        @JvmField var x = 0
        @JvmField var y = 0
        @JvmField var privateValue = 0
    }

    companion object {
        private const val HOTKEY_ID = 0x6A31
        private const val WM_HOTKEY = 0x0312
        private const val WM_QUIT = 0x0012
        private const val PM_NOREMOVE = 0x0000
        private const val PM_REMOVE = 0x0001
        private const val QS_HOTKEY = 0x0080
        private const val MWMO_INPUTAVAILABLE = 0x0004
        private const val WAIT_FAILED = -1
    }
}
