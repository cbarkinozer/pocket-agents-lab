package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileSearchTest {
    @Test
    fun naturalSearchDropsCommandWords() {
        assertEquals(setOf("qlora"), fileSearchTerms("Find the file about QLoRA"))
    }

    @Test
    fun filenameMatchesRankAboveContentMatches() {
        val terms = setOf("qwen")
        assertTrue(scoreLocalFile("qwen-results.csv", "other", terms) > scoreLocalFile("results.csv", "qwen", terms))
    }
}
