package com.pocketagentslab

internal const val SEARCH_SPOTIFY = "search_spotify"
internal const val SEARCH_YOUTUBE = "search_youtube"
internal const val DRAFT_TELEGRAM_MESSAGE = "draft_telegram_message"
internal const val MEDIA_PLAY_PAUSE = "media_play_pause"
internal const val MEDIA_NEXT = "media_next"
internal const val MEDIA_PREVIOUS = "media_previous"
internal const val OPEN_MEDIA_ACCESS = "open_media_access"

internal fun parseExternalAppProposal(action: String, request: String): DeviceActionProposal? = when (action) {
    SEARCH_SPOTIFY -> searchProposal(action, request, "spotify")
    SEARCH_YOUTUBE -> searchProposal(action, request, "youtube")
    DRAFT_TELEGRAM_MESSAGE -> telegramDraft(request)
    MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, OPEN_MEDIA_ACCESS -> DeviceActionProposal(action)
    else -> null
}

private fun searchProposal(action: String, request: String, appName: String): DeviceActionProposal? {
    val query = request
        .replace(Regex("(?i)^.*?\\b(?:play|open|find|search(?:\\s+for)?)\\s+"), "")
        .replace(Regex("(?i)\\s+(?:on|from|in)\\s+$appName\\b.*$"), "")
        .replace(Regex("(?i)\\s+(?:for|to)\\s+me[?.!]*$"), "")
        .trim().trim('"', '\'', '.', '?', '!')
    if (query.isBlank() || query.equals("a song", true) || query.equals("a video", true)) return null
    return DeviceActionProposal(name = action, searchQuery = query.take(200))
}

private fun telegramDraft(request: String): DeviceActionProposal? {
    val match = Regex(
        "(?i)^(?:please\\s+)?(?:send|text|message)\\s+(.+?)\\s+to\\s+(.+?)(?:\\s+(?:on|using)\\s+telegram)?[.!]?$",
    ).matchEntire(request.trim()) ?: Regex(
        "(?i)^(?:please\\s+)?(?:say|write)\\s+(.+?)\\s+to\\s+(.+?)(?:\\s+(?:on|using)\\s+telegram)?[.!]?$",
    ).matchEntire(request.trim()) ?: return null
    val message = match.groupValues[1].trim().trim('"', '\'')
    val recipient = match.groupValues[2].trim().trimEnd('.', '!', '?')
    if (message.isBlank() || recipient.isBlank()) return null
    return DeviceActionProposal(
        name = DRAFT_TELEGRAM_MESSAGE,
        messageText = message.take(2_000),
        recipientHint = recipient.take(100),
    )
}
