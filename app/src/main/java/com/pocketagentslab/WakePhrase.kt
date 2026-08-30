package com.pocketagentslab

private val WAKE_PHRASE = Regex("^\\s*(?:okay|ok)[,.!]?\\s+pocket[,.!]?\\s*(.*)$", RegexOption.IGNORE_CASE)

/** Returns the command following "Okay Pocket", or null when the wake phrase was not spoken. */
internal fun commandAfterWakePhrase(transcript: String): String? =
    WAKE_PHRASE.matchEntire(transcript)?.groupValues?.get(1)?.trim()

internal fun isAcceptedWakePhrase(transcript: String, confidence: Float?): Boolean =
    commandAfterWakePhrase(transcript) != null && (confidence == null || confidence < 0f || confidence >= 0.55f)
