package com.tasklane.globalquickadd

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test

class GlobalQuickAddSettingsTest {
    @Test fun `fresh installation is disabled and configuration survives serialization`() {
        val settings = GlobalQuickAddSettings()
        assertFalse(settings.state.enabled)
        val desired = GlobalQuickAddSettings.Options().apply {
            enabled = true; key = "F9"; control = false; alt = false; meta = true; shift = true
            projectPath = "/projects/example"
        }
        settings.loadState(desired)
        desired.key = "A"
        val xml = JDOMUtil.write(XmlSerializer.serialize(settings.state))
        val read = GlobalQuickAddSettings().apply {
            loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), GlobalQuickAddSettings.Options::class.java))
        }
        assertTrue(read.state.enabled)
        assertEquals(GlobalShortcut("F9", false, false, true, true), read.state.shortcut())
        assertEquals("/projects/example", read.state.projectPath)
    }
    @Test fun `fixed missing destination never falls back to another project`() {
        assertNull(selectGlobalQuickAddProject(listOf("/one"), "/closed", "/one") { it })
        assertEquals("/two", selectGlobalQuickAddProject(listOf("/one", "/two"), "/two", "/one") { it })
    }
    @Test fun `last active destination requires known focus when multiple projects are open`() {
        val projects = listOf("/one", "/two")
        assertEquals("/two", selectGlobalQuickAddProject(projects, "", "/two") { it })
        assertNull(selectGlobalQuickAddProject(projects, "", null) { it })
        assertEquals("/one", selectGlobalQuickAddProject(listOf("/one"), "", null) { it })
        assertNull(selectGlobalQuickAddProject(emptyList<String>(), "", null) { it })
    }
}
