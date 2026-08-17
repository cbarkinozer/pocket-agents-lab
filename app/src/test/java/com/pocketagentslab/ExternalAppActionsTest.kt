package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalAppActionsTest {
    @Test
    fun spotifyAndYouTubeQueriesAreExtracted() {
        assertEquals(
            "Daft Punk Around the World",
            parseExternalAppProposal(SEARCH_SPOTIFY, "Play Daft Punk Around the World on Spotify")?.searchQuery,
        )
        assertEquals(
            "Kotlin coroutines tutorial",
            parseExternalAppProposal(SEARCH_YOUTUBE, "Open Kotlin coroutines tutorial on YouTube")?.searchQuery,
        )
        assertNull(parseExternalAppProposal(SEARCH_SPOTIFY, "Open a song from Spotify for me"))
    }

    @Test
    fun telegramDraftPreservesMessageAndRecipientHint() {
        val proposal = parseExternalAppProposal(
            DRAFT_TELEGRAM_MESSAGE,
            "Send this is a test and you are selected to Efe İncefikir on Telegram",
        )

        assertEquals("this is a test and you are selected", proposal?.messageText)
        assertEquals("Efe İncefikir", proposal?.recipientHint)
        assertEquals(
            "Efe İncefikir",
            parseExternalAppProposal(
                DRAFT_TELEGRAM_MESSAGE,
                "Say this is a test and you are selected to Efe İncefikir",
            )?.recipientHint,
        )
    }
}
