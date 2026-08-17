package com.pocketagentslab

import java.util.Locale
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

internal const val SET_TIMER = "set_timer"
internal const val SET_ALARM = "set_alarm"
internal const val CREATE_CALENDAR_EVENT = "create_calendar_event"

internal data class DeviceActionProposal(
    val name: String,
    val hour: Int? = null,
    val minute: Int? = null,
    val durationSeconds: Int? = null,
    val label: String? = null,
    val repeatDays: List<Int> = emptyList(),
    val title: String? = null,
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
)

internal fun buildDeviceActionProposal(
    action: String,
    request: String,
    clock: Clock = Clock.systemDefaultZone(),
): DeviceActionProposal? = when (action) {
    OPEN_STORAGE_SETTINGS, OPEN_BATTERY_SETTINGS -> DeviceActionProposal(action)
    SET_TIMER -> parseTimerProposal(request)
    SET_ALARM -> parseAlarmProposal(request)
    CREATE_CALENDAR_EVENT -> parseCalendarEventProposal(request, clock)
    else -> null
}

internal fun parseCalendarEventProposal(
    request: String,
    clock: Clock = Clock.systemDefaultZone(),
): DeviceActionProposal? {
    val dayMatch = Regex("(?i)\\b(today|tomorrow)\\b").find(request) ?: return null
    val timeMatch = Regex(
        "(?i)\\b(?:at|for)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b",
    ).find(request, dayMatch.range.last + 1) ?: return null
    var hour = timeMatch.groupValues[1].toIntOrNull() ?: return null
    val minute = timeMatch.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
    val meridiem = timeMatch.groupValues[3].lowercase(Locale.ROOT)
    if (minute !in 0..59) return null
    if (meridiem.isNotBlank()) {
        if (hour !in 1..12) return null
        hour = when {
            meridiem == "am" && hour == 12 -> 0
            meridiem == "pm" && hour != 12 -> hour + 12
            else -> hour
        }
    } else if (hour !in 0..23) return null

    val rawTitle = request.substring(0, dayMatch.range.first)
        .replace(Regex("(?i)^(?:please\\s+)?(?:add|create|schedule|put)\\s+"), "")
        .replace(Regex("(?i)^(?:an?\\s+)?(?:calendar\\s+)?(?:event|appointment|meeting)\\s+(?:called\\s+)?"), "")
        .trim().trimEnd(',', '.', '!', '?')
    if (rawTitle.isBlank()) return null
    val date = LocalDate.now(clock).plusDays(if (dayMatch.value.equals("tomorrow", true)) 1 else 0)
    val zone = ZoneId.of(clock.zone.id)
    val start = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    return DeviceActionProposal(
        name = CREATE_CALENDAR_EVENT,
        title = rawTitle.take(100),
        startEpochMillis = start,
        endEpochMillis = start + 60 * 60 * 1000L,
    )
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
        "(?i)(?:at|for|to)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b",
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
        repeatDays = if (Regex("(?i)\\bevery\\s*day\\b").containsMatchIn(request)) {
            listOf(1, 2, 3, 4, 5, 6, 7)
        } else {
            emptyList()
        },
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
    ) + proposal.label?.let { " called ‘$it’" }.orEmpty() +
        if (proposal.repeatDays.isNotEmpty()) " every day" else ""
    CREATE_CALENDAR_EVENT -> "Create calendar event ‘${requireNotNull(proposal.title)}’ at " +
        java.time.Instant.ofEpochMilli(requireNotNull(proposal.startEpochMillis))
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .toString()
    else -> "Unknown action"
}
