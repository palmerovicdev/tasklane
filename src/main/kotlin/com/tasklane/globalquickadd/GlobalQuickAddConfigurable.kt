package com.tasklane.globalquickadd

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

/** System registration is deliberately separate from the IDE-local Keymap. Changes apply only on Apply. */
internal class GlobalQuickAddConfigurable : Configurable {
    private val enabled = JCheckBox("Enable system-wide Quick Add (IDE must be running)")
    private val key = JComboBox((('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() } + (1..24).map { "F$it" }).toTypedArray())
    private val control = JCheckBox("Control")
    private val alt = JCheckBox("Alt / Option")
    private val shift = JCheckBox("Shift")
    private val meta = JCheckBox("Meta / Command / Windows")
    private val fixed = JCheckBox("Use a fixed open project (otherwise use the last active project)")
    private val projectPath = JTextField()
    private val status = JLabel()
    private var timer: Timer? = null
    override fun getDisplayName() = "Tasklane Global Quick Add"
    override fun createComponent(): JComponent {
        val fields = JPanel(GridLayout(0, 1, 0, 8)).apply {
            add(enabled)
            add(JPanel().apply { add(control); add(alt); add(shift); add(meta); add(key) })
            add(fixed); add(JLabel("Fixed project directory (absolute path):")); add(projectPath)
            add(JLabel("Creates in that project's active Tasklane repository. The IDE Keymap is unchanged."))
            add(JLabel("Wayland: the desktop approves the effective shortcut and controls window focus."))
            add(status)
            add(JButton("Retry registration").apply { addActionListener { service<GlobalQuickAddService>().applySettings() } })
        }
        reset()
        timer = Timer(500) { status.text = service<GlobalQuickAddService>().status }.also { it.start() }
        return JPanel(BorderLayout()).apply { add(fields, BorderLayout.NORTH) }
    }
    private fun draft() = GlobalQuickAddSettings.Options().also {
        it.enabled = enabled.isSelected; it.key = key.selectedItem as String
        it.control = control.isSelected; it.alt = alt.isSelected; it.shift = shift.isSelected; it.meta = meta.isSelected
        it.projectPath = if (fixed.isSelected) projectPath.text.trim() else ""
    }
    override fun isModified(): Boolean {
        val old = service<GlobalQuickAddSettings>().state
        val new = draft()
        return old.enabled != new.enabled || old.shortcut() != new.shortcut() || old.projectPath != new.projectPath
    }
    override fun apply() {
        val options = draft()
        try {
            if (options.enabled) options.shortcut().validate()
            if (fixed.isSelected) require(options.projectPath.isNotEmpty() && java.nio.file.Path.of(options.projectPath).isAbsolute) {
                "Choose an absolute project directory, or use the last active project."
            }
        } catch (error: IllegalArgumentException) { throw ConfigurationException(error.message.orEmpty()) }
        service<GlobalQuickAddSettings>().loadState(options)
        service<GlobalQuickAddService>().let { it.start(); it.applySettings() }
    }
    override fun reset() {
        val options = service<GlobalQuickAddSettings>().state
        enabled.isSelected = options.enabled; key.selectedItem = options.key
        control.isSelected = options.control; alt.isSelected = options.alt; shift.isSelected = options.shift; meta.isSelected = options.meta
        fixed.isSelected = options.projectPath.isNotEmpty(); projectPath.text = options.projectPath
    }
    override fun disposeUIResources() { timer?.stop(); timer = null }
}
