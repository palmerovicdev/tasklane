package com.tasklane.globalquickadd

import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(name = "TasklaneGlobalQuickAdd", storages = [Storage("tasklane-global-quick-add.xml", roamingType = RoamingType.DISABLED)])
internal class GlobalQuickAddSettings : PersistentStateComponent<GlobalQuickAddSettings.Options> {
    class Options {
        var enabled = false
        var key = "R"
        var control = true
        var alt = true
        var shift = true
        var meta = false
        /** Empty means last active project. A fixed path never silently falls back to another project. */
        var projectPath = ""
        fun shortcut() = GlobalShortcut(key, control, alt, shift, meta)
        fun copy() = Options().also {
            it.enabled = enabled; it.key = key; it.control = control; it.alt = alt
            it.shift = shift; it.meta = meta; it.projectPath = projectPath
        }
    }
    private var options = Options()
    override fun getState() = options
    override fun loadState(state: Options) { options = state.copy() }
}
