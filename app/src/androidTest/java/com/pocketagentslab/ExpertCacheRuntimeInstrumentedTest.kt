package com.pocketagentslab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.ExpertCacheRuntime
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.Thread.sleep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

    @Test
    fun prefetchReadsInBackground() {
        assertTrue(ExpertCacheRuntime.prefetch(layer = 2, expert = 3, offset = 0, length = 8))
        repeat(20) {
            if (JSONObject(ExpertCacheRuntime.statsJson()).getLong("bytesRead") >= 8) {
                return
            }
            sleep(10)
        }
        val stats = JSONObject(ExpertCacheRuntime.statsJson())
        assertTrue(stats.getLong("bytesRead") >= 8)
        assertTrue(ExpertCacheRuntime.load(layer = 2, expert = 3, offset = 0, length = 8))
    }

    @Test
    fun expertIndexIsSafeBeforeModelLoad() {
        assertEquals("{}", ExpertCacheRuntime.expertIndexJson())
    }

    @Test
    fun telemetryToggleIsSafeBeforeModelLoad() {
        ExpertCacheRuntime.setTelemetry(true)
        assertTrue(JSONObject(ExpertCacheRuntime.telemetryJson()).getBoolean("enabled"))
        ExpertCacheRuntime.setTelemetry(false)
        assertTrue(!JSONObject(ExpertCacheRuntime.telemetryJson()).getBoolean("enabled"))
    }

    @Test
    fun parsesLingGgufExpertOffsetsWithoutLoadingWeights() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "models/Ling-3.0-tiny-Q3_K_M.gguf")
        assumeTrue(model.isFile)
        val index = JSONObject(ExpertCacheRuntime.indexFileJson(model.absolutePath))
        println("LING_GGUF_INDEX architecture=${index.getString("architecture")} experts=${index.getInt("expertCount")} tensors=${index.getInt("tensorCount")}")
        assertEquals("bailingmoe3", index.getString("architecture"))
        assertEquals(128, index.getInt("expertCount"))
        assertTrue(index.getInt("tensorCount") >= 60)
        assertTrue(index.getJSONArray("tensors").getJSONObject(0).getLong("expertBytes") > 0)
    }

    @Test
    fun prefetchesLingExpertRangesFromStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "models/Ling-3.0-tiny-Q3_K_M.gguf")
        assumeTrue(model.isFile)
        assertTrue(ExpertCacheRuntime.open(model.absolutePath, 64L * 1024 * 1024))
        assertTrue(ExpertCacheRuntime.prefetchExpert(layer = 1, expert = 0))
        repeat(50) {
            if (JSONObject(ExpertCacheRuntime.statsJson()).getLong("bytesRead") > 0) {
                return
            }
            sleep(20)
        }
        assertTrue(JSONObject(ExpertCacheRuntime.statsJson()).getLong("bytesRead") > 0)
    }

    @Test
    fun lingInferenceProducesExpertTelemetryWhenExplicitlyRequested() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("runLing") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "models/Ling-3.0-tiny-Q3_K_M.gguf")
        assumeTrue(model.isFile)
        val engine = AiChat.getInferenceEngine(context)
        try {
            runBlocking {
                withTimeout(180_000) {
                    engine.state.first {
                        it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                    }
                }
                engine.loadModel(model.absolutePath)
                assertTrue(ExpertCacheRuntime.open(model.absolutePath, 64L * 1024 * 1024))
                ExpertCacheRuntime.setTelemetry(true)
                ExpertCacheRuntime.setPredictor(true)
                engine.setSystemPrompt("You are a concise local test assistant.")
                engine.sendUserPrompt("What is 2+2?", predictLength = 8).toList()
            }
            val telemetry = JSONObject(ExpertCacheRuntime.telemetryJson())
            println("LING_EXPERT_TELEMETRY=$telemetry")
            println("LING_EXPERT_INDEX=${ExpertCacheRuntime.expertIndexJson().take(240)}...")
            assertTrue(telemetry.getBoolean("enabled"))
            assertTrue(telemetry.getLong("routeEvents") > 0)
            assertTrue(telemetry.getLong("selectedExperts") > 0)
            assertTrue(telemetry.getLong("prefetchRequests") > 0)
            assertTrue(telemetry.getLong("predictorRequests") > 0)
            println("LING_CONNECTED_TELEMETRY=$telemetry")
        } finally {
            ExpertCacheRuntime.setPredictor(false)
            ExpertCacheRuntime.setTelemetry(false)
            engine.cleanUp()
        }
    }
}
