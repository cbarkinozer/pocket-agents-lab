package com.pocketagentslab

import java.util.Locale

internal const val SET_TIMER = "set_timer"
internal const val SET_ALARM = "set_alarm"

internal data class DeviceActionProposal(
    val name: String,
    val hour: Int? = null,
    val minute: Int? = null,
    val durationSeconds: Int? = null,
    val label: String? = null,
)

internal fun buildDeviceActionProposal(action: String, request: String): DeviceActionProposal? = when (action) {
    OPEN_STORAGE_SETTINGS, OPEN_BATTERY_SETTINGS -> DeviceActionProposal(action)
    SET_TIMER -> parseTimerProposal(request)
    SET_ALARM -> parseAlarmProposal(request)
    else -> null
}

internal fun parseTimerProposal(request: String): DeviceActionProposal? {
    val match = Regex(
        "(?i)(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)",
    ).find(request) ?: return null
    val amount = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT).take(1)) {
        "s" -> 1L
        "m" -> 60L
        "h" -> 3600L
        else -> return null
    }
    val seconds = amount * multiplier
    if (seconds > 86_400L) return null
    return DeviceActionProposal(
        name = SET_TIMER,
        durationSeconds = seconds.toInt(),
        label = extractClockLabel(request),
    )
}

internal fun parseAlarmProposal(request: String): DeviceActionProposal? {
    val match = Regex(
        "(?i)(?:at|for)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b",
    ).find(request) ?: return null
    var hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
    val meridiem = match.groupValues[3].lowercase(Locale.ROOT)
    if (minute !in 0..59) return null
    if (meridiem.isNotBlank()) {
        if (hour !in 1..12) return null
        hour = when {
            meridiem == "am" && hour == 12 -> 0
            meridiem == "pm" && hour != 12 -> hour + 12
            else -> hour
        }
    } else if (hour !in 0..23) {
        return null
    } else if (hour in 1..7 && request.contains("tomorrow", ignoreCase = true)) {
        // Natural "wake me at 7 tomorrow" means morning unless the user says PM.
        hour = hour
    }
    return DeviceActionProposal(
        name = SET_ALARM,
        hour = hour,
        minute = minute,
        label = extractClockLabel(request),
    )
}

private fun extractClockLabel(request: String): String? = Regex(
    "(?i)\\b(?:called|named|label(?:led)?)\\s+(.+?)(?:\\s+(?:at|for)\\s+\\d|$)",
).find(request)?.groupValues?.get(1)?.trim()?.trimEnd('.', '!', '?')?.take(80)?.takeIf { it.isNotBlank() }

internal fun deviceActionLabel(proposal: DeviceActionProposal): String = when (proposal.name) {
    OPEN_STORAGE_SETTINGS -> "Open Storage Settings"
    OPEN_BATTERY_SETTINGS -> "Open Battery Settings"
    SET_TIMER -> "Set a ${requireNotNull(proposal.durationSeconds)}-second timer" +
        proposal.label?.let { " called ‘$it’" }.orEmpty()
    SET_ALARM -> "Set an alarm for %02d:%02d".format(
        requireNotNull(proposal.hour),
        requireNotNull(proposal.minute),
    ) + proposal.label?.let { " called ‘$it’" }.orEmpty()
    else -> "Unknown action"
}
