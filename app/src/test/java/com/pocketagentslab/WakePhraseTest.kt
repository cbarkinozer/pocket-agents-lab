package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakePhraseTest {
    @Test
    fun extractsCommandWithoutBeingCaseSensitive() {
        assertEquals("pause the music", commandAfterWakePhrase("Hey Agent, pause the music"))
        assertEquals("open Spotify", commandAfterWakePhrase("HEY AGENT open Spotify"))
    }

    @Test
    fun distinguishesWakeOnlyFromUnrelatedSpeech() {
        assertEquals("", commandAfterWakePhrase("Hey Agent"))
        assertNull(commandAfterWakePhrase("please open Spotify"))
        assertNull(commandAfterWakePhrase("hey assistant open Spotify"))
    }
}
