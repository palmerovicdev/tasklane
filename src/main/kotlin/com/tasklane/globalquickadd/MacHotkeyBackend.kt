package com.tasklane.globalquickadd

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class MacHotkeyBackend(
    private val native: MacHotkeyNative = CarbonHotkeyNative(),
    private val registrationTimeoutMs: Long = 5_000,
) : GlobalHotkeyBackend {
    override fun register(shortcut: GlobalShortcut, activated: (String?) -> Unit): GlobalHotkeyRegistration {
        shortcut.validate()
        val modifiers = modifiers(shortcut)
        val key = keyCode(shortcut.key)
        val listening = AtomicBoolean(true)
        val pending = submitToMainQueue {
            if (!listening.get()) null
            else native.register(modifiers, key) { if (listening.get()) activated(null) }
        }
        var handle: Any? = null
        try {
            handle = pending.get(registrationTimeoutMs, TimeUnit.MILLISECONDS)
            if (handle == null) {
                throw GlobalHotkeyUnavailable("This macOS shortcut could not be registered; it may already be in use.")
            }
        } catch (error: TimeoutException) {
            throw GlobalHotkeyUnavailable("macOS main queue did not respond to shortcut registration.", error)
        } catch (error: ExecutionException) {
            throw GlobalHotkeyUnavailable("macOS shortcut registration failed.", error.cause)
        } finally {
            if (handle == null) {
                listening.set(false)
                pending.thenAccept { lateHandle ->
                    if (lateHandle != null) native.onMainQueue { native.unregister(lateHandle) }
                }
            }
        }

        val registered = checkNotNull(handle)
        return object : GlobalHotkeyRegistration {
            private var cleanup: CompletableFuture<Unit>? = null
            override val description = shortcut.display()

            @Synchronized override fun close() {
                listening.set(false)
                val pendingRelease = cleanup ?: submitToMainQueue { native.unregister(registered) }.also { cleanup = it }
                try {
                    pendingRelease.get(5, TimeUnit.SECONDS)
                } catch (error: ExecutionException) {
                    cleanup = null
                    throw IllegalStateException("macOS could not release the shortcut.", error.cause)
                }
            }
        }
    }

    private fun <T> submitToMainQueue(action: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        native.onMainQueue {
            try {
                result.complete(action())
            } catch (error: Exception) {
                result.completeExceptionally(error)
            } catch (error: LinkageError) {
                result.completeExceptionally(error)
            }
        }
        return result
    }

    private fun modifiers(shortcut: GlobalShortcut) = (if (shortcut.control) 1 shl 12 else 0) or
        (if (shortcut.shift) 1 shl 9 else 0) or (if (shortcut.alt) 1 shl 11 else 0) or
        (if (shortcut.meta) 1 shl 8 else 0)

    private fun keyCode(key: String): Int = MAC_KEY_CODES[key]
        ?: throw GlobalHotkeyUnavailable("This key is not supported by the macOS native shortcut API.")

    companion object {
        private val MAC_KEY_CODES = mapOf(
            "A" to 0, "S" to 1, "D" to 2, "F" to 3, "H" to 4, "G" to 5, "Z" to 6, "X" to 7,
            "C" to 8, "V" to 9, "B" to 11, "Q" to 12, "W" to 13, "E" to 14, "R" to 15,
            "Y" to 16, "T" to 17, "1" to 18, "2" to 19, "3" to 20, "4" to 21, "6" to 22,
            "5" to 23, "=" to 24, "9" to 25, "7" to 26, "-" to 27, "8" to 28, "0" to 29,
            "]" to 30, "O" to 31, "U" to 32, "[" to 33, "I" to 34, "P" to 35, "L" to 37,
            "J" to 38, "'" to 39, "K" to 40, ";" to 41, "\\" to 42, "," to 43, "/" to 44,
            "N" to 45, "M" to 46, "." to 47,
            "F1" to 122, "F2" to 120, "F3" to 99, "F4" to 118, "F5" to 96, "F6" to 97,
            "F7" to 98, "F8" to 100, "F9" to 101, "F10" to 109, "F11" to 103, "F12" to 111,
            "F13" to 105, "F14" to 107, "F15" to 113, "F16" to 106, "F17" to 64, "F18" to 79,
            "F19" to 80, "F20" to 90,
        )
    }
}

internal interface MacHotkeyNative {
    fun onMainQueue(action: () -> Unit)
    fun register(modifiers: Int, key: Int, activated: () -> Unit): Any?
    fun unregister(handle: Any)
}

internal class CarbonHotkeyNative(
    private val carbon: Carbon = Native.load("Carbon", Carbon::class.java),
) : MacHotkeyNative {
    private val dispatch by lazy { Native.load("System", Dispatch::class.java) }
    private val mainQueue by lazy {
        // dispatch_get_main_queue() is inline in Apple's SDK, not a callable symbol.
        NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q")
    }

    override fun onMainQueue(action: () -> Unit) {
        if (dispatch.pthread_main_np() != 0) {
            action()
            return
        }

        val callback = object : DispatchFunction {
            override fun invoke(context: Pointer?) {
                try {
                    action()
                } finally {
                    pendingDispatches.remove(this)
                }
            }
        }
        pendingDispatches.add(callback)
        try {
            dispatch.dispatch_async_f(mainQueue, null, callback)
        } catch (error: Throwable) {
            pendingDispatches.remove(callback)
            throw error
        }
    }

    override fun register(modifiers: Int, key: Int, activated: () -> Unit): Any? {
        val target = carbon.GetApplicationEventTarget()
        val eventTypes = EventTypeSpec().apply { eventClass = EVENT_CLASS_KEYBOARD; eventKind = EVENT_HOTKEY_PRESSED }
        val hotkeyId = nextHotkeyId.incrementAndGet()
        val identifier = EventHotKeyId().apply { signature = TASKLANE_SIGNATURE; id = hotkeyId }
        val handler = object : CarbonEventHandler {
            override fun invoke(next: Pointer?, event: Pointer?, userData: Pointer?): Int {
                val incoming = EventHotKeyId()
                val status = carbon.GetEventParameter(
                    event, EVENT_PARAM_DIRECT_OBJECT, TYPE_EVENT_HOTKEY_ID, null,
                    NativeLong(incoming.size().toLong()), null, incoming.pointer,
                )
                if (status != 0) return EVENT_NOT_HANDLED
                incoming.read()
                if (incoming.signature != TASKLANE_SIGNATURE || incoming.id != hotkeyId) return EVENT_NOT_HANDLED
                activated()
                return 0
            }
        }

        val handlerRef = PointerByReference()
        if (carbon.InstallEventHandler(target, handler, 1, eventTypes, null, handlerRef) != 0) return null
        installedHandlers.add(handler)
        val hotKeyRef = PointerByReference()
        var registered = false
        try {
            registered = carbon.RegisterEventHotKey(key, modifiers, identifier, target, HOTKEY_EXCLUSIVE, hotKeyRef) == 0
            if (!registered) return null
            return CarbonHandle(hotKeyRef.value, handlerRef.value, handler)
        } finally {
            if (!registered) removeHandler(handlerRef.value, handler)
        }
    }

    override fun unregister(handle: Any) {
        val registration = handle as CarbonHandle
        try {
            registration.hotKey?.let {
                check(carbon.UnregisterEventHotKey(it) == 0) { "Carbon did not release the registered shortcut." }
                registration.hotKey = null
            }
        } finally {
            registration.handlerRef?.let {
                removeHandler(it, registration.callback)
                registration.handlerRef = null
            }
        }
    }

    private fun removeHandler(ref: Pointer?, callback: CarbonEventHandler) {
        check(carbon.RemoveEventHandler(ref) == 0) { "Carbon did not remove the shortcut event handler." }
        installedHandlers.remove(callback)
    }

    private data class CarbonHandle(var hotKey: Pointer?, var handlerRef: Pointer?, val callback: CarbonEventHandler)

    internal interface Dispatch : Library {
        fun pthread_main_np(): Int
        fun dispatch_async_f(queue: Pointer, context: Pointer?, work: DispatchFunction)
    }
    internal interface DispatchFunction : Callback { fun invoke(context: Pointer?) }
    internal interface CarbonEventHandler : Callback { fun invoke(next: Pointer?, event: Pointer?, userData: Pointer?): Int }
    internal interface Carbon : Library {
        fun GetApplicationEventTarget(): Pointer
        fun InstallEventHandler(target: Pointer, handler: CarbonEventHandler, count: Int, types: EventTypeSpec, userData: Pointer?, out: PointerByReference): Int
        fun RemoveEventHandler(handler: Pointer?): Int
        fun RegisterEventHotKey(key: Int, modifiers: Int, identifier: EventHotKeyId, target: Pointer, options: Int, out: PointerByReference): Int
        fun UnregisterEventHotKey(hotKey: Pointer?): Int
        fun GetEventParameter(event: Pointer?, name: Int, desiredType: Int, actualType: Pointer?, size: NativeLong, actualSize: Pointer?, data: Pointer): Int
    }

    @Structure.FieldOrder("eventClass", "eventKind")
    internal class EventTypeSpec : Structure() {
        @JvmField var eventClass = 0
        @JvmField var eventKind = 0
    }

    @Structure.FieldOrder("signature", "id")
    internal class EventHotKeyId : Structure(), Structure.ByValue {
        @JvmField var signature = 0
        @JvmField var id = 0
    }

    companion object {
        private const val EVENT_CLASS_KEYBOARD = 0x6B657962
        private const val EVENT_HOTKEY_PRESSED = 5
        private const val EVENT_PARAM_DIRECT_OBJECT = 0x2D2D2D2D
        private const val TYPE_EVENT_HOTKEY_ID = 0x686B6964
        private const val EVENT_NOT_HANDLED = -9874
        private const val TASKLANE_SIGNATURE = 0x544C5141
        private const val HOTKEY_EXCLUSIVE = 1

        private val nextHotkeyId = AtomicInteger()
        // JNA does not retain callbacks merely because a C API holds their function pointers.
        private val pendingDispatches = ConcurrentHashMap.newKeySet<DispatchFunction>()
        private val installedHandlers = ConcurrentHashMap.newKeySet<CarbonEventHandler>()
    }
}
