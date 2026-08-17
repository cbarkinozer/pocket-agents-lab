package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockActionsTest {
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
}
