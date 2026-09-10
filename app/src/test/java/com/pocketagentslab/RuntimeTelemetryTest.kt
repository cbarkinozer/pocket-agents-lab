package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeTelemetryTest {
    @Test
    fun `proc stat parser tolerates spaces in process name`() {
        val stat = "42 (pocket agents) S 1 2 3 4 5 6 101 8 303 9 10"
        assertEquals(101L to 303L, parseProcStat(stat))
    }

    @Test
    fun `key value parser reads proc units and raw counters`() {
        val values = parseKeyValueLines("Rss: 123 kB\nPss_File: 45 kB\nread_bytes: 9876\n")
        assertEquals(123L, values["Rss"])
        assertEquals(45L, values["Pss_File"])
        assertEquals(9876L, values["read_bytes"])
    }

    @Test
    fun `delta remains unavailable when either sample is unavailable`() {
        assertEquals(7L, nullableDelta(10L, 3L))
        assertEquals(null, nullableDelta(null, 3L))
    }
}
