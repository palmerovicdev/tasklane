package com.tasklane.globalquickadd

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import com.tasklane.service.TaskService
import com.tasklane.ui.quickadd.QuickAddAction
import java.awt.Desktop
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.beans.PropertyChangeListener
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Service(Service.Level.APP)
internal class GlobalQuickAddService : Disposable {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Tasklane global shortcut").apply { isDaemon = true }
    }
    private val controller = GlobalQuickAddController(worker, ::backend, ::openDialog,
        Executor { ApplicationManager.getApplication().invokeLater(it, ModalityState.nonModal()) })
    private var started = false
    private var lastProjectPath: String? = null
    private var showingDialog = false
    private var notice = ""
    private val focusListener = PropertyChangeListener { event ->
        rememberWindow(event.newValue as? Window)
    }
    private fun rememberWindow(window: Window?) {
        if (window == null) return
        ProjectManager.getInstance().openProjects.firstOrNull { project ->
            val frame = WindowManager.getInstance().getFrame(project)
            frame != null && generateSequence(window) { it.owner }.any { it === frame }
        }?.let { lastProjectPath = it.basePath }
    }
    val status: String get() = controller.status + if (notice.isEmpty()) "" else " — $notice"

    fun start() {
        if (started) return
        started = true
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow", focusListener)
        rememberWindow(KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow)
        applySettings()
    }
    fun applySettings() {
        notice = ""
        val options = service<GlobalQuickAddSettings>().state.copy()
        controller.configure(if (options.enabled) options.shortcut() else null)
    }
    private fun backend(): GlobalHotkeyBackend {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.startsWith("windows") -> WindowsHotkeyBackend()
            os.contains("mac") -> MacHotkeyBackend()
            os.contains("linux") && (!System.getenv("WAYLAND_DISPLAY").isNullOrBlank() || System.getenv("XDG_SESSION_TYPE") == "wayland") -> WaylandHotkeyBackend()
            os.contains("linux") && !System.getenv("DISPLAY").isNullOrBlank() -> X11HotkeyBackend()
            else -> throw GlobalHotkeyUnavailable("This desktop does not support a global shortcut.")
        }
    }
    private fun openDialog(activationToken: String?) {
        if (showingDialog) return
        val options = service<GlobalQuickAddSettings>().state
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        val project = selectGlobalQuickAddProject(projects, options.projectPath, lastProjectPath) { it.basePath }
        if (project == null) { report("Open the selected project, or focus a project once before using the shortcut."); return }
        val service = TaskService.getInstance(project)
        if (service.isReadOnly(service.snapshot.value.activeRepo)) { report("The active Tasklane repository is read-only."); return }
        val frame = WindowManager.getInstance().getFrame(project)
        if (frame == null) { report("The project window is not ready."); return }
        // Wayland may refuse activation. Never claim an unverified native token was consumed by JBR.
        if (activationToken != null) notice = "The desktop controls foreground activation."
        showingDialog = true
        try {
            frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true)
            }
            frame.toFront()
            frame.requestFocus()
            QuickAddAction.createTask(project)
        } finally { showingDialog = false }
    }
    private fun report(message: String) {
        notice = message
        NotificationGroupManager.getInstance().getNotificationGroup("Tasklane")
            .createNotification("Global Quick Add", message, NotificationType.WARNING).notify(null)
    }
    override fun dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("activeWindow", focusListener)
        controller.close()
        worker.shutdown()
    }
}

internal class GlobalQuickAddActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) service<GlobalQuickAddService>().start()
        }
    }
}
