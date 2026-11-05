package com.tasklane.globalquickadd

/** A deliberately bounded, portable shortcut vocabulary; modifiers are explicit per machine. */
internal data class GlobalShortcut(val key: String, val control: Boolean, val alt: Boolean, val shift: Boolean, val meta: Boolean) {
    fun validate() {
        require(key.matches(Regex("[A-Z0-9]|F([1-9]|1[0-9]|2[0-4])"))) { "Choose A–Z, 0–9 or F1–F24." }
        require(control || alt || meta) { "Include Control, Alt/Option or Meta/Command." }
    }
    fun display(): String = listOfNotNull(
        "Ctrl".takeIf { control }, "Alt".takeIf { alt }, "Shift".takeIf { shift }, "Meta".takeIf { meta }, key,
    ).joinToString("+")
}

/** Registration runs off the EDT. Callbacks may arrive on any thread. Never monitor unrelated keys. */
internal interface GlobalHotkeyBackend {
    fun register(shortcut: GlobalShortcut, activated: (String?) -> Unit): GlobalHotkeyRegistration
}

internal interface GlobalHotkeyRegistration : AutoCloseable {
    val description: String
    val failure: String? get() = null
    override fun close()
}

/** User-facing registration failures, including conflicts, unsupported desktops and declined consent. */
internal class GlobalHotkeyUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
