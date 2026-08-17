package com.pocketagentslab

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockActionsTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC)
    @Test
    fun timerDurationIsParsedAndBounded() {
        val proposal = parseTimerProposal("Set a timer for 15 minutes")

        assertEquals(SET_TIMER, proposal?.name)
        assertEquals(900, proposal?.durationSeconds)
        assertNull(parseTimerProposal("Set a timer"))
        assertNull(parseTimerProposal("Set a timer for 25 hours"))
    }

    @Test
    fun alarmTimeAndLabelAreNormalized() {
        val proposal = parseAlarmProposal("Set an alarm called take medicine for 9 PM")

        assertEquals(SET_ALARM, proposal?.name)
        assertEquals(21, proposal?.hour)
        assertEquals(0, proposal?.minute)
        assertEquals("take medicine", proposal?.label)
    }

    @Test
    fun tomorrowMorningShorthandIsAccepted() {
        val proposal = parseAlarmProposal("Wake me at 7 tomorrow")

        assertEquals(7, proposal?.hour)
        assertEquals(0, proposal?.minute)
    }

    @Test
    fun invalidClockValuesNeedClarification() {
        assertNull(parseAlarmProposal("Set an alarm"))
        assertNull(parseAlarmProposal("Set an alarm for 25:80"))
    }

    @Test
    fun dottedTimeWithToAndEverydayIsAccepted() {
        val proposal = parseAlarmProposal("Set alarm to 9.13 am everyday")

        assertEquals(9, proposal?.hour)
        assertEquals(13, proposal?.minute)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), proposal?.repeatDays)
        assertEquals("Set an alarm for 09:13 every day", deviceActionLabel(requireNotNull(proposal)))
    }

    @Test
    fun tomorrowCalendarEventProducesValidatedOneHourDraft() {
        val proposal = parseCalendarEventProposal(
            "Add dentist appointment tomorrow at 3 PM",
            fixedClock,
        )

        assertEquals(CREATE_CALENDAR_EVENT, proposal?.name)
        assertEquals("dentist appointment", proposal?.title)
        assertEquals(Instant.parse("2026-08-18T15:00:00Z").toEpochMilli(), proposal?.startEpochMillis)
        assertEquals(60 * 60 * 1000L, proposal?.endEpochMillis!! - proposal.startEpochMillis!!)
    }

    @Test
    fun incompleteCalendarEventCannotBeProposed() {
        assertNull(parseCalendarEventProposal("Add dentist appointment tomorrow", fixedClock))
        assertNull(parseCalendarEventProposal("Add an event tomorrow at 3 PM", fixedClock))
    }
}
