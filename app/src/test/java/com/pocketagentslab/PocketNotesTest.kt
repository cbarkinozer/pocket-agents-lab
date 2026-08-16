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

    @Test
    fun naturalMemoryCommandsPersistAndRecallWithoutModelRouting() {
        assertEquals("the pass is 777", parseNoteWriteRequest("Remember that the pass is 777")?.content)
        assertEquals("launch code is 123", parseNoteWriteRequest("Save this: launch code is 123")?.content)
        assertEquals("milk is needed", parseNoteWriteRequest("Write this to my notes: milk is needed")?.content)
        assertEquals("the pass", parseNoteSearchRequest("What was the pass I told you before?"))
    }

    @Test
    fun recallIgnoresConversationalStopWords() {
        val notes = listOf(PocketNote("1", "the pass is 777", "the pass is 777", 1))
        assertEquals("1", searchPocketNotes(notes, "the pass I told you before").single().id)
    }
}
