package com.pocketagentslab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.RoutingGrammar
import com.arm.aichat.ConversationReset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray

/** Real A32 smoke, not a benchmark. Requires both test GGUFs in files/models. */
@RunWith(AndroidJUnit4::class)
class ReflexIntegrationTest {
    @Test
    fun clefRoutesThenSwapsToTextAndBackWithoutTwoResidentModels() = runBlocking {
        withTimeout(300_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val model = File(context.filesDir, "models/reflex-1-combined-Q4_K_M.gguf")
            val chat = File(context.filesDir, "models/Qwen3.5-0.8B-Q4_K_M.gguf")
            assertTrue("Install Reflex test artifact", model.isFile)
            assertTrue("Install Qwen test artifact", chat.isFile)
            val events = JSONArray()
            val started = SystemClock.elapsedRealtime()
            fun record(stage: String) { events.put(JSONObject().put("stage", stage).put("elapsedMs", SystemClock.elapsedRealtime() - started)) }
            val engine = ReflexInferenceEngine(context, AiChat.getInferenceEngine(context))
            try {
                engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady }
                engine.chooseTextModel(chat)
                engine.loadModel(model.absolutePath)
                engine.setSystemPrompt("You are a helpful assistant. Give a brief answer.")
                record("decision-model-loaded")
                assertTrue(engine.isDecisionModel)
                assertTrue(engine.decisionRuntimeRunning)
                assertFalse(engine.textModelReady)
                val first = requireNotNull(engine.selector()).select("How much free storage does my phone have?")
                record("storage-route-returned")
                assertEquals("get_storage_info", first.decision.toolName)
                assertEquals(0, first.generatedPieces)
                RoutingGrammar.setMode(RoutingGrammar.NONE)
                val answer = engine.sendUserPrompt("Say hello in one short sentence.", 32).toList().joinToString("")
                RoutingGrammar.setMode(RoutingGrammar.NONE)
                record("text-generated-with-decision-process-stopped")
                assertTrue("Text model should generate an answer", answer.isNotBlank())
                assertFalse(engine.decisionRuntimeRunning)
                assertTrue(engine.textModelReady)
                engine.prepareRouting()
                assertTrue(engine.decisionRuntimeRunning)
                assertFalse(engine.textModelReady)
                // Exact regression: AgentBackend's generator prepares grammar
                // after prepareRouting() has freed the previous JNI sampler.
                // This was a native double free before globals were cleared.
                RoutingGrammar.setMode(RoutingGrammar.NONE)
                ConversationReset.reset()
                record("grammar-and-reset-safe-after-jni-unload")
                val second = requireNotNull(engine.selector()).select("What is my battery percentage?")
                record("battery-route-after-text-unloaded")
                assertEquals("get_battery_info", second.decision.toolName)
                RoutingGrammar.setMode(RoutingGrammar.NONE)
                val secondAnswer = engine.sendUserPrompt("Say goodbye in one short sentence.", 32).toList().joinToString("")
                RoutingGrammar.setMode(RoutingGrammar.NONE)
                assertTrue(secondAnswer.isNotBlank())
                record("second-text-answer-generated")
                engine.cleanUp()
                RoutingGrammar.setMode(RoutingGrammar.NONE)
                ConversationReset.reset()
                record("grammar-and-reset-safe-after-full-cleanup")
                assertFalse(engine.decisionRuntimeRunning)
                assertFalse(engine.textModelReady)
                assertTrue(engine.state.value is InferenceEngine.State.Initialized)
                File(context.filesDir, "reflex-integration-smoke.json").writeText(
                    JSONObject().put("complete", true).put("purpose", "functional integration smoke; not an accuracy benchmark")
                        .put("events", events).put("firstRoute", first.decision.toolName)
                        .put("textAnswer", answer).put("secondTextAnswer", secondAnswer).put("secondRoute", second.decision.toolName)
                        .put("sequentialModelStateAssertionsPassed", true).toString(2),
                )
                context.getSharedPreferences("model_defaults", android.content.Context.MODE_PRIVATE).edit()
                    .putString("model_path", model.absolutePath).putString("model_name", model.name).commit()
                Unit
            } finally {
                engine.close()
            }
        }
    }
}
