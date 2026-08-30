package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakePhraseTest {
    @Test
    fun extractsCommandWithoutBeingCaseSensitive() {
        assertEquals("pause the music", commandAfterWakePhrase("Okay Pocket, pause the music"))
        assertEquals("open Spotify", commandAfterWakePhrase("OK POCKET open Spotify"))
    }

    @Test
    fun distinguishesWakeOnlyFromUnrelatedSpeech() {
        assertEquals("", commandAfterWakePhrase("Okay Pocket"))
        assertNull(commandAfterWakePhrase("please open Spotify"))
        assertNull(commandAfterWakePhrase("hey agent open Spotify"))
    }

    @Test
    fun rejectsLowConfidenceWakeRecognitionWhenConfidenceIsAvailable() {
        assertEquals(true, isAcceptedWakePhrase("okay pocket", null))
        assertEquals(true, isAcceptedWakePhrase("ok pocket", 0.82f))
        assertEquals(false, isAcceptedWakePhrase("okay pocket", 0.31f))
        assertEquals(false, isAcceptedWakePhrase("okay rocket", 0.99f))
    }
}
