package com.pocketagentslab

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.ExpertCacheRuntime
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExpertCacheRuntimeInstrumentedTest {
    private lateinit var modelSliceFile: File

    @Before
    fun setUp() {
        System.loadLibrary("ai-chat")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        modelSliceFile = File(context.cacheDir, "expert-cache-fixture.bin")
        modelSliceFile.writeBytes(ByteArray(32) { it.toByte() })
        assertTrue(ExpertCacheRuntime.open(modelSliceFile.absolutePath, 16))
    }

    @After
    fun tearDown() {
        ExpertCacheRuntime.close()
        modelSliceFile.delete()
    }

    @Test
    fun readsHitsAndEvictsWithinBudget() {
        assertTrue(ExpertCacheRuntime.load(layer = 0, expert = 0, offset = 0, length = 8))
        assertTrue(ExpertCacheRuntime.load(layer = 0, expert = 0, offset = 0, length = 8))
        assertTrue(ExpertCacheRuntime.load(layer = 0, expert = 1, offset = 8, length = 8))
        assertTrue(ExpertCacheRuntime.load(layer = 1, expert = 0, offset = 16, length = 8))

        val stats = JSONObject(ExpertCacheRuntime.statsJson())
        assertEquals(1, stats.getLong("hits"))
        assertEquals(3, stats.getLong("misses"))
        assertTrue(stats.getLong("evictions") >= 1)
        assertEquals(24, stats.getLong("bytesRead"))
        assertEquals(16, stats.getLong("residentBytes"))
        assertEquals(2, stats.getLong("residentEntries"))
    }
}
