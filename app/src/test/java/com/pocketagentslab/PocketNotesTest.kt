package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PocketNotesTest {
    @Test
    fun writeRequestCreatesEditableProposal() {
        val proposal = parseNoteWriteRequest("Write test Qwen Q4 tomorrow in my notes")
        assertEquals("test Qwen Q4 tomorrow", proposal?.content)
    }

    @Test
    fun unrelatedRequestDoesNotProposeWrite() {
        assertNull(parseNoteWriteRequest("What is my battery percentage?"))
    }

    @Test
    fun titleMatchesRankAboveContentOnlyMatches() {
        val notes = listOf(
            PocketNote("1", "General", "QLoRA experiment", 2),
            PocketNote("2", "QLoRA plan", "Tomorrow", 1),
        )
        assertEquals("2", searchPocketNotes(notes, "QLoRA").first().id)
    }

    @Test
    fun searchIntentExtractsQuery() {
        assertEquals("QLoRA", parseNoteSearchRequest("Search QLoRA in my notes"))
    }
}
