package com.tasklane.domain

import com.tasklane.domain.text.InlineTagCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class InlineTagCompletionTest {
    @Test
    fun `finds a tag prefix and the whole token at the caret`() {
        assertEquals(InlineTagCompletion.Prefix(7, 11, "ap"), InlineTagCompletion.at("Review #api now", 10))
        assertEquals(InlineTagCompletion.Prefix(0, 1, ""), InlineTagCompletion.at("#", 1))
        assertEquals(InlineTagCompletion.Prefix(0, 12, "front-"), InlineTagCompletion.at("#front-end_2", 7))
    }

    @Test
    fun `does not offer tags in code or invalid words`() {
        for (text in listOf("C#", "url#fragment", "a,#api", "##api", "`#api", "```\n#api", "#api.")) {
            assertNull(text, InlineTagCompletion.at(text, text.length))
        }
        assertNull(InlineTagCompletion.at("# Title", 2))
        assertEquals(InlineTagCompletion.Prefix(11, 15, "api"), InlineTagCompletion.at("`code` and #api", 15))
    }

    @Test
    fun `typed punctuation is not an explicit suggestion selection`() {
        for (char in listOf(' ', ',', '.', ':', '(', ';', '=')) {
            assertTrue(InlineTagCompletion.isTypedCharacter(char))
        }
        for (char in listOf('\n', '\t', '\u0000')) {
            assertFalse(InlineTagCompletion.isTypedCharacter(char))
        }
    }

    @Test
    fun `filters taken tags and orders prefix matches before other matches`() {
        val tags = listOf(
            com.tasklane.domain.model.TagCount("backend", 20),
            com.tasklane.domain.model.TagCount("endpoints", 2),
            com.tasklane.domain.model.TagCount("END-to-end", 4),
        )
        assertEquals(listOf("endpoints", "backend"), InlineTagCompletion.suggestions(tags, "end", listOf("end-TO-end")).map { it.tag })
    }
}
