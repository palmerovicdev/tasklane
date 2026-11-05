package com.tasklane.globalquickadd

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GlobalQuickAddNativeTest {
    private val shortcut = GlobalShortcut("A", control = true, alt = false, shift = false, meta = false)

    @Test fun windowsRegistersOnDedicatedThreadAndUnregistersOnClose() {
        val native = FakeWindows()
        val called = AtomicInteger()
        val registration = WindowsHotkeyBackend(native).register(shortcut) { called.incrementAndGet() }

        assertTrue(native.registered.await(2, TimeUnit.SECONDS))
        assertTrue(native.registrationThread.startsWith("global-hotkey-windows"))
        native.activate()
        assertEquals(1, called.get())
        registration.close()
        assertTrue(native.unregistered.await(2, TimeUnit.SECONDS))
    }

    @Test fun windowsReportsConflictAndStopsItsThread() {
        val native = FakeWindows(registerResult = false)
        val error = assertThrows(GlobalHotkeyUnavailable::class.java) {
            WindowsHotkeyBackend(native).register(shortcut) {}
        }
        assertTrue(error.message!!.contains("already in use"))
        assertTrue(native.unregistered.await(2, TimeUnit.SECONDS))
    }

    @Test fun windowsSignalsStopWhenRegistrationFinishesAfterTheDeadline() {
        val release = CountDownLatch(1)
        val native = FakeWindows(beforeRegister = { release.await(2, TimeUnit.SECONDS) })
        try {
            val error = assertThrows(GlobalHotkeyUnavailable::class.java) {
                WindowsHotkeyBackend(native, registrationTimeoutMs = 25).register(shortcut) {}
            }
            assertTrue(error.message!!.contains("did not respond"))
        } finally {
            release.countDown()
        }
        assertTrue(native.unregistered.await(2, TimeUnit.SECONDS))
        assertTrue(native.stopRequested)
    }

    @Test fun windowsFiltersUnrelatedHotkeysAndReportsMessageWaitFailure() {
        val unregistered = CountDownLatch(1)
        val user32 = object : Win32HotkeyNative.User32 {
            private var owner: Thread? = null
            private var hotkeyId = 0
            private var queued = 2

            override fun RegisterHotKey(window: Pointer?, id: Int, modifiers: Int, key: Int): Boolean {
                assertEquals(0x4002, modifiers)
                assertEquals(0x41, key)
                owner = Thread.currentThread()
                hotkeyId = id
                return true
            }
            override fun UnregisterHotKey(window: Pointer?, id: Int): Boolean {
                assertEquals(owner, Thread.currentThread())
                assertEquals(hotkeyId, id)
                unregistered.countDown()
                return true
            }
            override fun PeekMessageW(msg: Win32HotkeyNative.Msg, window: Pointer?, min: Int, max: Int, remove: Int): Boolean {
                assertEquals(0x0312, min)
                assertEquals(0x0312, max)
                if (remove == 0 || queued == 0) return false
                msg.message = 0x0312
                msg.wParam = Pointer((if (queued-- == 2) hotkeyId + 1 else hotkeyId).toLong())
                return true
            }
            override fun MsgWaitForMultipleObjectsEx(count: Int, handles: Pointer?, timeout: Int, wakeMask: Int, flags: Int) = -1
        }
        val activations = AtomicInteger()
        val registration = WindowsHotkeyBackend(Win32HotkeyNative(user32)).register(shortcut) { activations.incrementAndGet() }
        assertTrue(unregistered.await(2, TimeUnit.SECONDS))
        registration.close()
        assertEquals(1, activations.get())
        assertEquals("Windows could not wait for shortcut input.", registration.failure)
    }

    @Test fun macRegistersAndUnregistersThroughMainQueueBridge() {
        val native = FakeMac()
        val activated = CountDownLatch(1)
        val registration = MacHotkeyBackend(native).register(shortcut) { activated.countDown() }
        assertTrue(native.registered.await(2, TimeUnit.SECONDS))
        assertEquals("mac-main-queue", native.threadName)
        native.activate()
        assertTrue(activated.await(2, TimeUnit.SECONDS))
        registration.close()
        assertTrue(native.unregistered.await(2, TimeUnit.SECONDS))
    }

    @Test fun macReportsRegistrationFailure() {
        val native = FakeMac(registerResult = false)
        val error = assertThrows(GlobalHotkeyUnavailable::class.java) {
            MacHotkeyBackend(native).register(shortcut) {}
        }
        assertTrue(error.message!!.contains("could not be registered"))
    }

    @Test fun macReleasesARegistrationThatFinishesAfterTheDeadline() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val native = FakeMac(
            dispatch = {
                worker.execute(it)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
            },
            beforeRegister = {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            },
        )
        try {
            val error = assertThrows(GlobalHotkeyUnavailable::class.java) {
                MacHotkeyBackend(native, registrationTimeoutMs = 25).register(shortcut) {}
            }
            assertTrue(error.message!!.contains("did not respond"))
            release.countDown()
            assertTrue(native.registered.await(2, TimeUnit.SECONDS))
            assertTrue(native.unregistered.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun nativeStructuresHaveTheExpectedLayout() {
        assertEquals(if (Native.POINTER_SIZE == 8) 48 else 32, Win32HotkeyNative.Msg().size())
        assertEquals(8, CarbonHotkeyNative.EventTypeSpec().size())
        assertEquals(8, CarbonHotkeyNative.EventHotKeyId().size())
        assertTrue(Structure.ByValue::class.java.isAssignableFrom(CarbonHotkeyNative.EventHotKeyId::class.java))
    }

    @Test fun carbonHandlesOnlyItsOwnHotkeyAndRemovesBothNativeHandles() {
        val carbon = FakeCarbon()
        val native = CarbonHotkeyNative(carbon)
        var activations = 0
        val handle = checkNotNull(native.register(1 shl 12, 0) { activations++ })
        val signature = carbon.registeredId.signature
        val id = carbon.registeredId.id

        assertEquals(-9874, carbon.send(signature xor 1, id))
        assertEquals(-9874, carbon.send(signature, id + 1))
        assertEquals(0, activations)
        assertEquals(0, carbon.send(signature, id))
        assertEquals(1, activations)

        native.unregister(handle)
        assertEquals(listOf("unregister", "remove handler"), carbon.cleanup)
    }

    private class FakeCarbon : CarbonHotkeyNative.Carbon {
        lateinit var registeredId: CarbonHotkeyNative.EventHotKeyId
        lateinit var callback: CarbonHotkeyNative.CarbonEventHandler
        private var incomingSignature = 0
        private var incomingId = 0
        val cleanup = mutableListOf<String>()

        override fun GetApplicationEventTarget() = Pointer(1)
        override fun InstallEventHandler(
            target: Pointer, handler: CarbonHotkeyNative.CarbonEventHandler, count: Int,
            types: CarbonHotkeyNative.EventTypeSpec, userData: Pointer?, out: PointerByReference,
        ): Int {
            callback = handler
            out.value = Pointer(2)
            return 0
        }
        override fun RegisterEventHotKey(
            key: Int, modifiers: Int, identifier: CarbonHotkeyNative.EventHotKeyId, target: Pointer,
            options: Int, out: PointerByReference,
        ): Int {
            registeredId = identifier
            assertEquals(1, options)
            out.value = Pointer(3)
            return 0
        }
        override fun GetEventParameter(
            event: Pointer?, name: Int, desiredType: Int, actualType: Pointer?, size: NativeLong,
            actualSize: Pointer?, data: Pointer,
        ): Int {
            assertEquals(0x2D2D2D2D, name)
            assertEquals(0x686B6964, desiredType)
            assertEquals(8L, size.toLong())
            data.setInt(0, incomingSignature)
            data.setInt(4, incomingId)
            return 0
        }
        override fun RemoveEventHandler(handler: Pointer?): Int {
            assertEquals(Pointer(2), handler)
            cleanup += "remove handler"
            return 0
        }
        override fun UnregisterEventHotKey(hotKey: Pointer?): Int {
            assertEquals(Pointer(3), hotKey)
            cleanup += "unregister"
            return 0
        }
        fun send(signature: Int, id: Int): Int {
            incomingSignature = signature
            incomingId = id
            return callback.invoke(null, null, null)
        }
    }

    private class FakeWindows(
        private val registerResult: Boolean = true,
        private val beforeRegister: () -> Unit = {},
    ) : WindowsHotkeyNative {
        val registered = CountDownLatch(1)
        val unregistered = CountDownLatch(1)
        @Volatile var registrationThread = ""
        @Volatile var stopRequested = false
        @Volatile private var listener: (() -> Unit)? = null
        override fun run(modifiers: Int, key: Int, onHotkey: () -> Unit, onReady: (Boolean) -> Unit, stop: CountDownLatch) {
            registrationThread = Thread.currentThread().name
            beforeRegister()
            listener = onHotkey
            onReady(registerResult)
            registered.countDown()
            if (registerResult) stopRequested = stop.await(2, TimeUnit.SECONDS)
            unregistered.countDown()
        }
        fun activate() = listener?.invoke()
    }

    private class FakeMac(
        private val registerResult: Boolean = true,
        private val dispatch: (() -> Unit) -> Unit = { it() },
        private val beforeRegister: () -> Unit = {},
    ) : MacHotkeyNative {
        val registered = CountDownLatch(1)
        val unregistered = CountDownLatch(1)
        @Volatile var threadName = ""
        @Volatile private var listener: (() -> Unit)? = null
        override fun onMainQueue(action: () -> Unit) = dispatch { threadName = "mac-main-queue"; action() }
        override fun register(modifiers: Int, key: Int, activated: () -> Unit): Any? {
            beforeRegister()
            listener = activated
            registered.countDown()
            return if (registerResult) Any() else null
        }
        override fun unregister(handle: Any) { listener = null; unregistered.countDown() }
        fun activate() = listener?.invoke()
    }
}
