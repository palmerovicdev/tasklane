package com.tasklane.globalquickadd

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant

internal interface GlobalShortcutsPortal : AutoCloseable {
    val failure: String? get() = null
    fun bind(preferredTrigger: String, onActivated: (String?) -> Unit): BoundPortalShortcut
    override fun close()
}

internal data class BoundPortalShortcut(val sessionHandle: String, val shortcutId: String, val effectiveTrigger: String)

internal fun interface GlobalShortcutsPortalFactory {
    fun connect(): GlobalShortcutsPortal
}

internal object SystemGlobalShortcutsPortalFactory : GlobalShortcutsPortalFactory {
    override fun connect() = DbusGlobalShortcutsPortal(DbusPortalBus.connect())
}

internal const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
private const val PORTAL_NAME = "org.freedesktop.portal.Desktop"
private const val DBUS_NAME = "org.freedesktop.DBus"
private const val DBUS_PATH = "/org/freedesktop/DBus"

@DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
@JvmSuppressWildcards
internal interface PortalGlobalShortcuts : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): DBusPath
    fun BindShortcuts(
        sessionHandle: DBusPath,
        shortcuts: List<PortalShortcut>,
        parentWindow: String,
        options: Map<String, Variant<*>>,
    ): DBusPath

    class Activated(
        path: String,
        val sessionHandle: DBusPath,
        val shortcutId: String,
        timestamp: UInt64,
        val options: Map<String, Variant<*>>,
    ) : DBusSignal(path, sessionHandle, shortcutId, timestamp, options)
}

@JvmSuppressWildcards
internal class PortalShortcut(
    @field:Position(0) val id: String,
    @field:Position(1) val properties: Map<String, Variant<*>>,
) : Struct()

@DBusInterfaceName("org.freedesktop.portal.Request")
@JvmSuppressWildcards
internal interface PortalRequest : DBusInterface {
    fun Close()

    class Response(path: String, val code: UInt32, val results: Map<String, Variant<*>>) :
        DBusSignal(path, code, results)
}

@DBusInterfaceName("org.freedesktop.portal.Session")
internal interface PortalSession : DBusInterface {
    fun Close()

    @JvmSuppressWildcards
    class Closed(path: String, details: Map<String, Variant<*>>) : DBusSignal(path, details)
}

/** Bus seam for testing early responses, cancellation and teardown without a desktop. */
internal interface PortalBus : AutoCloseable {
    val sender: String
    val connected: Boolean
    fun createSession(options: Map<String, Variant<*>>): DBusPath
    fun bindShortcuts(session: DBusPath, shortcuts: List<PortalShortcut>, options: Map<String, Variant<*>>): DBusPath
    fun subscribeResponses(path: DBusPath, response: (PortalRequest.Response) -> Unit): AutoCloseable
    fun subscribeActivations(activated: (PortalGlobalShortcuts.Activated) -> Unit): AutoCloseable
    fun subscribeSessionClosed(path: DBusPath, closed: () -> Unit): AutoCloseable
    fun closeRequest(path: DBusPath)
    fun closeSession(path: DBusPath)
    override fun close()
}

private class DbusPortalBus(
    private val connection: DBusConnection,
    private val owner: String,
) : PortalBus {
    private val shortcuts = proxy(PORTAL_PATH, PortalGlobalShortcuts::class.java)
    override val sender = connection.uniqueName.removePrefix(":").replace('.', '_')
    override val connected: Boolean get() = connection.isConnected

    override fun createSession(options: Map<String, Variant<*>>) = shortcuts.CreateSession(options)
    override fun bindShortcuts(session: DBusPath, shortcuts: List<PortalShortcut>, options: Map<String, Variant<*>>) =
        this.shortcuts.BindShortcuts(session, shortcuts, "", options)

    override fun subscribeResponses(path: DBusPath, response: (PortalRequest.Response) -> Unit) =
        subscribe(PortalRequest.Response::class.java, owner, path.path, response)

    override fun subscribeActivations(activated: (PortalGlobalShortcuts.Activated) -> Unit) =
        subscribe(PortalGlobalShortcuts.Activated::class.java, owner, PORTAL_PATH, activated)

    override fun subscribeSessionClosed(path: DBusPath, closed: () -> Unit): AutoCloseable {
        val session = subscribe(PortalSession.Closed::class.java, owner, path.path) { closed() }
        try {
            val service = subscribe(DBus.NameOwnerChanged::class.java, DBUS_NAME, DBUS_PATH) { signal ->
                if (signal.name == PORTAL_NAME && signal.oldOwner == owner && signal.newOwner != owner) closed()
            }
            return AutoCloseable { try { session.close() } finally { service.close() } }
        } catch (failure: Exception) {
            runCatching { session.close() }
            throw failure
        }
    }

    override fun closeRequest(path: DBusPath) = proxy(path.path, PortalRequest::class.java).Close()
    override fun closeSession(path: DBusPath) = proxy(path.path, PortalSession::class.java).Close()
    override fun close() = connection.close()

    private fun <T : DBusInterface> proxy(path: String, type: Class<T>): T =
        connection.getRemoteObject(owner, path, type)

    private fun <T : DBusSignal> subscribe(
        type: Class<T>, sender: String, path: String, handler: (T) -> Unit,
    ): AutoCloseable {
        val rule = DBusMatchRuleBuilder.create().withType(type).withSender(sender).withPath(path).build()
        return connection.addSigHandler<T>(rule) { signal ->
            if (signal.source == sender && signal.path == path) handler(signal)
        }
    }

    companion object {
        fun connect(): PortalBus {
            val loader = DbusPortalBus::class.java.classLoader
            val thread = Thread.currentThread()
            val originalLoader = thread.contextClassLoader
            // The IDE's pooled threads need not have the plugin loader as their context loader.
            thread.contextClassLoader = loader
            try {
                val connection = DBusConnectionBuilder.forSessionBus().withShared(false)
                    .transportConfig().withServiceLoaderClassLoader(loader).withTimeout(5_000).back().build()
                try {
                    val dbus = connection.getRemoteObject(DBUS_NAME, DBUS_PATH, DBus::class.java)
                    dbus.StartServiceByName(PORTAL_NAME, UInt32(0))
                    val owner = connection.getDBusOwnerName(PORTAL_NAME)
                    return DbusPortalBus(connection, owner)
                } catch (failure: Exception) {
                    runCatching { connection.close() }
                    throw failure
                } catch (failure: LinkageError) {
                    runCatching { connection.close() }
                    throw failure
                }
            } finally {
                thread.contextClassLoader = originalLoader
            }
        }
    }
}
