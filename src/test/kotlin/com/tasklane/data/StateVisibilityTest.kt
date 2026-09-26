package com.tasklane.data

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.tasklane.data.config.TasklaneWorkspaceService
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TasklaneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Qué estados salen en la tool window y en el tablero (2.17.1), tal como se guardan en
 * `workspace.xml`: los que se **quitan**, para que un estado nuevo salga en los dos sitios
 * sin que nadie tenga que marcarlo.
 */
class StateVisibilityTest {

    private fun roundTrip(write: TasklaneWorkspaceService.() -> Unit): Pair<String, TasklaneWorkspaceService> {
        val xml = JDOMUtil.write(XmlSerializer.serialize(TasklaneWorkspaceService().apply(write).state))
        val read = TasklaneWorkspaceService().apply {
            loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), TasklaneWorkspaceService.WorkspaceState::class.java))
        }
        return xml to read
    }

    @Test
    fun `lo quitado de cada sitio vuelve igual, y cada sitio por su lado`() {
        val gone = linkedSetOf(TasklaneConfig.DONE, StateId("s-1234"))
        val (xml, read) = roundTrip {
            boardHidden = gone
            windowHidden = setOf(TasklaneConfig.DOING)
        }

        assertEquals(xml, gone, read.boardHidden)
        assertEquals(xml, setOf(TasklaneConfig.DOING), read.windowHidden)
    }

    @Test
    fun `sin nada quitado no se escribe nada, y todo se ve`() {
        val (xml, read) = roundTrip {
            boardHidden = emptySet()
            windowHidden = emptySet()
        }

        assertFalse(xml, "boardHidden" in xml || "windowHidden" in xml)
        assertEquals(emptySet<StateId>(), read.boardHidden)
        assertEquals(emptySet<StateId>(), read.windowHidden)
    }

    @Test
    fun `un workspace xml de antes lo enseña todo en los dos sitios`() {
        val read = TasklaneWorkspaceService().apply {
            loadState(XmlSerializer.deserialize(JDOMUtil.load("<TasklaneWorkspace/>"), TasklaneWorkspaceService.WorkspaceState::class.java))
        }

        assertEquals(emptySet<StateId>(), read.boardHidden)
        assertEquals(emptySet<StateId>(), read.windowHidden)
    }
}
