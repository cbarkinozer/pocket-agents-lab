package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun suffixRememberCommandIsRecognized() {
        assertEquals(
            "experiment numebr is 842",
            parseNoteWriteRequest("experiment numebr is 842 remember it")?.content,
        )
    }

    @Test
    fun dontForgetCommandIsRecognized() {
        assertEquals(
            "the experiment number is 842",
            parseNoteWriteRequest("don't forget the experiment number is 842")?.content,
        )
        assertEquals(
            "the experiment number is 842",
            parseNoteWriteRequest("do not forget that the experiment number is 842")?.content,
        )
    }

    @Test
    fun adjacentLetterTypoStillMatchesRecall() {
        val notes = listOf(PocketNote("1", "experiment numebr", "experiment numebr is 842", 1))
        assertEquals("1", searchPocketNotes(notes, "experiment number").single().id)
    }

    @Test
    fun latestExperimentNumberFindsThePersistedNaturalNote() {
        val notes = listOf(
            PocketNote(
                "1",
                "make sure u remember the experiment no is 842",
                "make sure u remember the experiment no is 842",
                1,
            ),
        )

        val matches = searchPocketNotes(notes, "the latest experiment number")

        assertEquals("1", matches.single().id)
        assertTrue(matches.single().content.contains("842"))
    }
}
