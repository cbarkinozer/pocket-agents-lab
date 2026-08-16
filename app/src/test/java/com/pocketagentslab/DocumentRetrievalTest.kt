package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRetrievalTest {
    @Test
    fun relevantParagraphRanksFirst() {
        val chunks = retrieveDocumentChunks(
            "Battery temperature is 35 C.\n\nStorage has 20 GB free.\n\nAndroid is version 13.",
            "How much storage is free?",
        )
        assertTrue(chunks.first().text.contains("20 GB"))
    }

    @Test
    fun promptIncludesNumberedSourcesAndGroundingRule() {
        val prompt = buildDocumentAnswerPrompt(
            "notes.md",
            "What is the result?",
            listOf(DocumentChunk(2, "The result is 42.", 2)),
        )
        assertTrue(prompt.contains("only the source excerpts"))
        assertTrue(prompt.contains("[2] The result is 42."))
        assertEquals(1, Regex(Regex.escape("[2]")).findAll(prompt).count())
    }
}
