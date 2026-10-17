package com.tasklane.ui

import com.tasklane.ui.common.TagPill
import org.junit.Assert.assertEquals
import org.junit.Test

class TagPillTest {

    @Test
    fun `sobre un color oscuro la letra va blanca`() {
        assertEquals(TagPill.WHITE, TagPill.textOn(0x1565C0))
        assertEquals(TagPill.WHITE, TagPill.textOn(0xC62828))
        assertEquals(TagPill.WHITE, TagPill.textOn(0x000000))
    }

    @Test
    fun `sobre un color claro la letra va oscura, tambien en amarillos y verdes claros`() {
        assertEquals(TagPill.DARK, TagPill.textOn(0xFFEB3B))
        assertEquals(TagPill.DARK, TagPill.textOn(0x8BC34A))
        assertEquals(TagPill.DARK, TagPill.textOn(0xFFFFFF))
    }
}
