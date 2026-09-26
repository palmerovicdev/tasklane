package com.tasklane.ui

import com.tasklane.domain.model.AnchorSnippet
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.ui.toolwindow.AnchorChipTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El tooltip de la ficha del ancla en la tarjeta (2.15.0): la ruta y el código de hoy. */
class AnchorChipTipTest {

    private val anchor = CodeAnchor.of("src/auth/Login.kt", 3, text = "if (a < b) {", span = 2)
    private val more = { n: Int -> "$n more lines" }

    @Test
    fun `sin codigo todavia es la ruta a secas`() {
        assertEquals("src/auth/Login.kt:4-6", AnchorChipTip.html(anchor, null, more))
    }

    @Test
    fun `con codigo lleva la ruta y el codigo escapado`() {
        val html = AnchorChipTip.html(anchor, AnchorSnippet(listOf("if (a < b) {", "    go()", "}"), 3), more)

        assertTrue(html, html.startsWith("<html><b>src/auth/Login.kt:4-6</b><pre>"))
        assertTrue(html, "if (a &lt; b) {\n    go()\n}" in html)
        assertFalse(html, "more lines" in html)
    }

    @Test
    fun `lo que no cabe se cuenta y las lineas largas se cortan`() {
        val lines = (1..20).map { "x".repeat(150) }
        val html = AnchorChipTip.html(anchor, AnchorSnippet(lines, 30), more)

        assertEquals(AnchorChipTip.MAX_LINES, html.substringAfter("<pre>").substringBefore("</pre>").lines().size)
        assertTrue(html, "18 more lines" in html)
        assertFalse(html, "x".repeat(AnchorChipTip.MAX_COLUMNS) in html)
    }
}
