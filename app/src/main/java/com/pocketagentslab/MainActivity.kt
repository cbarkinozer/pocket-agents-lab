package com.pocketagentslab

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arm.aichat.AiChat
import com.arm.aichat.ConversationReset
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PocketAgentsScreen()
                }
            }
        }
    }
}

@Composable
private fun PocketAgentsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = remember {
        ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    }

    var selectedModels by remember { mutableStateOf<List<SelectedModel>>(emptyList()) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("No GGUF selected") }
    var modelStatus by remember { mutableStateOf("Select a GGUF model, then load it") }
    var prompt by remember { mutableStateOf("What is 2+2?") }
    var output by remember { mutableStateOf("") }
    var metrics by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var isBenchmarkRunning by remember { mutableStateOf(false) }
    var isAgentTestRunning by remember { mutableStateOf(false) }
    var benchmarkStatus by remember { mutableStateOf("Benchmark not run") }
    var agentTestStatus by remember { mutableStateOf("Agent tests not run") }
    var agentTestProgress by remember { mutableStateOf(0f) }
    var agentTestJob by remember { mutableStateOf<Job?>(null) }
    var agentProgress by remember { mutableStateOf(AgentProgress(0f, "Ready")) }
    var isModelLoaded by remember { mutableStateOf(false) }
    var loadedModelPath by remember { mutableStateOf<String?>(null) }
    val engine = remember { AiChat.getInferenceEngine(context.applicationContext) }
    val controlsBusy = isBusy || isBenchmarkRunning || isAgentTestRunning

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            selectedModels = uris.map { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (error: SecurityException) {
                    Log.w(TAG, "Provider did not grant persistable access", error)
                }
                SelectedModel(uri, displayName(context, uri))
            }
            selectedUri = selectedModels.first().uri
            selectedName = selectedModels.first().name
            modelStatus = "Selected ${selectedModels.size} model(s) in displayed order"
            isModelLoaded = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pocket Agents Lab", style = MaterialTheme.typography.headlineSmall)
        Text(
            "RAM: %.1f GB  •  ABI: %s  •  Android: %s".format(
                Locale.US,
                memoryInfo.totalMem / 1024.0 / 1024 / 1024,
                Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown",
                Build.VERSION.RELEASE,
            ),
        )

        Text("Device Capability", style = MaterialTheme.typography.titleLarge)
        Text("Run a short on-device benchmark to estimate this phone's local AI capability and a practical GGUF model-size range.")
        Button(
            onClick = {
                scope.launch {
                    isBenchmarkRunning = true
                    benchmarkStatus = "Running MobileNet V1 CPU benchmark for 60 seconds…"
                    benchmarkStatus = withContext(Dispatchers.Default) {
                        runMobileNetBenchmark(context)
                    }
                    isBenchmarkRunning = false
                }
            },
            enabled = !controlsBusy,
        ) {
            Text(if (isBenchmarkRunning) "Benchmark running…" else "Run Device Benchmark")
        }
        Text(benchmarkStatus)

        HorizontalDivider()
        Text("Agent Test", style = MaterialTheme.typography.titleLarge)
        Text("Select one or more GGUF models. The same 50-prompt tool-routing test runs once for a single model or sequentially for several models.")
        Text(
            if (selectedModels.isEmpty()) selectedName else selectedModels.mapIndexed { index, model ->
                "${index + 1}. ${model.name}"
            }.joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !controlsBusy,
            ) {
                Text("Select GGUFs")
            }
            Button(
                onClick = {
                    val uri = selectedUri ?: return@Button
                    scope.launch {
                        isBusy = true
                        isModelLoaded = false
                        modelStatus = "Copying model to app storage…"
                        try {
                            val modelFile = withContext(Dispatchers.IO) {
                                copyModelToPrivateStorage(context, uri, selectedName)
                            }
                            modelStatus = "Loading ${modelFile.name}…"
                            val initializedState = engine.state.first {
                                it is InferenceEngine.State.Initialized ||
                                    it is InferenceEngine.State.ModelReady ||
                                    it is InferenceEngine.State.Error
                            }
                            check(initializedState !is InferenceEngine.State.Error) {
                                "llama.cpp initialization failed"
                            }
                            if (initializedState is InferenceEngine.State.ModelReady) {
                                engine.cleanUp()
                                engine.state.first { it is InferenceEngine.State.Initialized }
                            }
                            val started = SystemClock.elapsedRealtime()
                            engine.loadModel(modelFile.absolutePath)
                            engine.setSystemPrompt(AGENT_SYSTEM_PROMPT)
                            loadedModelPath = modelFile.absolutePath
                            val loadMs = SystemClock.elapsedRealtime() - started
                            isModelLoaded = true
                            modelStatus = "Model loaded successfully in ${loadMs} ms"
                            metrics = "Load: ${loadMs} ms"
                            Log.i(TAG_METRICS, "model=${modelFile.name} load_ms=$loadMs context_tokens=1024 cpu_only=true")
                        } catch (error: Throwable) {
                            modelStatus = "Load failed: ${error.message ?: error.javaClass.simpleName}"
                            Log.e(TAG, "Model load failed", error)
                        } finally {
                            isBusy = false
                        }
                    }
                },
                enabled = selectedUri != null && !controlsBusy,
            ) {
                Text("Load for Tiny Agent")
            }
        }
        Button(
            onClick = {
                val queue = selectedModels
                agentTestJob = scope.launch {
                    isAgentTestRunning = true
                    isModelLoaded = false
                    agentTestProgress = 0f
                    val activity = context as? ComponentActivity
                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val runId = System.currentTimeMillis()
                    val outcomes = mutableListOf<JSONObject>()
                    writeQueueManifest(context, runId, queue, outcomes, state = "running")
                    try {
                        queue.forEachIndexed { modelIndex, selected ->
                            currentCoroutineContext().ensureActive()
                            awaitThermalCooldown(context) { temperature ->
                                agentTestStatus = "Model ${modelIndex + 1}/${queue.size} ${selected.name}: " +
                                    "cooling at ${formatMetric(temperature)} C; waiting for <= " +
                                    "$AGENT_EVAL_MAX_START_TEMPERATURE_C C"
                            }
                            try {
                                agentTestStatus = "Model ${modelIndex + 1}/${queue.size}: copying ${selected.name}"
                                val modelFile = withContext(Dispatchers.IO) {
                                    copyModelToPrivateStorage(context, selected.uri, selected.name)
                                }
                                agentTestStatus = "Model ${modelIndex + 1}/${queue.size}: loading ${selected.name}"
                                val loadMs = loadModelFile(engine, modelFile)
                                loadedModelPath = modelFile.absolutePath
                                isModelLoaded = true
                                val artifactStem = "agent-routing-$runId-${safeFileStem(selected.name)}"
                                runAgentTests(
                                    context,
                                    engine,
                                    modelFile.absolutePath,
                                    artifactStem,
                                ) { progress ->
                                    agentTestProgress = (
                                        modelIndex + progress.completed.toFloat() / progress.total
                                    ) / queue.size
                                    val promptNumber = (progress.completed + 1).coerceAtMost(progress.total)
                                    agentTestStatus = "Model ${modelIndex + 1}/${queue.size}: ${selected.name}\n" +
                                        "Prompt $promptNumber/${progress.total} • ${progress.correct} correct"
                                }
                                outcomes += JSONObject()
                                    .put("model", selected.name)
                                    .put("status", "completed")
                                    .put("loadMs", loadMs)
                                    .put("artifactStem", artifactStem)
                                modelStatus = "Loaded ${selected.name} in $loadMs ms"
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                Log.e(TAG_AGENT, "Queued model failed: ${selected.name}", error)
                                outcomes += JSONObject()
                                    .put("model", selected.name)
                                    .put("status", "failed")
                                    .put("error", error.message ?: error.javaClass.simpleName)
                                recoverInferenceEngine(engine)
                            }
                            writeQueueManifest(context, runId, queue, outcomes, state = "running")
                        }
                        agentTestProgress = 1f
                        writeQueueManifest(context, runId, queue, outcomes, state = "completed")
                        agentTestStatus = "Agent test complete (${queue.size} model(s))\n" +
                            outcomes.joinToString("\n") { formatQueueOutcome(it) }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        writeQueueManifest(context, runId, queue, outcomes, state = "cancelled")
                        agentTestStatus = "Agent test cancelled\n" +
                            outcomes.joinToString("\n") { formatQueueOutcome(it) }
                    } finally {
                        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        isAgentTestRunning = false
                        agentTestJob = null
                    }
                }
            },
            enabled = selectedModels.isNotEmpty() && !controlsBusy,
        ) {
            Text(if (isAgentTestRunning) "Running Agent Test…" else "Run Agent Test")
        }
        if (isAgentTestRunning) {
            LinearProgressIndicator(
                progress = { agentTestProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Overall progress: ${(agentTestProgress * 100).toInt()}%")
            Button(onClick = { agentTestJob?.cancel() }) {
                Text("Cancel Agent Test")
            }
        }
        Text(agentTestStatus)
        Text(modelStatus)

        HorizontalDivider()
        Text("Tiny Agent", style = MaterialTheme.typography.titleLarge)
        Text("Ask about this device, battery health, storage, or request a phone health check with practical suggestions. Everything runs locally.")
        Text("Active read-only tools: Device info • Battery info • Storage info")
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !controlsBusy,
        )
        Button(
            onClick = {
                scope.launch {
                    isBusy = true
                    output = ""
                    metrics = "Agent is deciding…"
                    agentProgress = AgentProgress(0.05f, "Preparing a fresh local-model context…")
                    try {
                        prepareFreshAgent(engine, requireNotNull(loadedModelPath))
                        val pssBeforeKb = Debug.getPss()
                        val started = SystemClock.elapsedRealtime()
                        val result = createAgentBackend(
                            context,
                            engine,
                        ) { progress ->
                            agentProgress = progress
                        }.run(prompt.trim())
                        val generationMs = SystemClock.elapsedRealtime() - started
                        val pssAfterKb = Debug.getPss()
                        val piecesPerSecond = if (generationMs > 0) {
                            result.generatedPieces * 1000.0 / generationMs
                        } else {
                            0.0
                        }
                        output = result.answer
                        metrics = "${result.route} | valid JSON: yes | ${generationMs} ms | " +
                            "%.2f exposed pieces/s | PSS %.1f MB".format(
                            Locale.US,
                            piecesPerSecond,
                            pssAfterKb / 1024.0,
                        )
                        Log.i(
                            TAG_METRICS,
                            "agent_route=${result.route} generation_ms=$generationMs " +
                                "generated_token_pieces=${result.generatedPieces} " +
                                "pss_before_kb=$pssBeforeKb pss_after_kb=$pssAfterKb " +
                                "tokens_per_second=${"%.3f".format(Locale.US, piecesPerSecond)}",
                        )
                        if (result.diagnosis != null) {
                            val report = JSONObject()
                                .put("workflow", PHONE_HEALTH_CHECK)
                                .put("diagnosis", JSONObject(result.diagnosis))
                                .put("latencyMs", generationMs)
                                .put("generatedPieces", result.generatedPieces)
                                .put("exposedPiecesPerSecond", piecesPerSecond)
                                .put("pssBeforeKb", pssBeforeKb)
                                .put("pssAfterKb", pssAfterKb)
                                .put("route", result.route)
                            context.openFileOutput("phone-health-check-result.json", Context.MODE_PRIVATE).use {
                                it.write(report.toString(2).toByteArray())
                            }
                            Log.i(TAG_HEALTH, report.toString())
                        }
                    } catch (error: Throwable) {
                        val cause = rootCauseDescription(error)
                        agentProgress = AgentProgress(0f, "Stopped: $cause")
                        output = "Generation failed: $cause"
                        Log.e(TAG, "Generation failed", error)
                    } finally {
                        isBusy = false
                    }
                }
            },
            enabled = isModelLoaded && prompt.isNotBlank() && !controlsBusy,
        ) {
            Text(if (isBusy && isModelLoaded) "Agent working…" else "Run Agent")
        }
        Text("Agent activity: ${agentProgress.message}")
        if (isBusy) {
            LinearProgressIndicator(
                progress = { agentProgress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Stage progress: ${(agentProgress.fraction * 100).toInt()}%")
        }
        Text(metrics)
        OutlinedTextField(
            value = output,
            onValueChange = {},
            readOnly = true,
            label = { Text("Agent answer") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp),
        )
    }
}

internal fun rootCauseDescription(error: Throwable): String {
    var deepest = error
    val visited = mutableSetOf<Throwable>()
    while (deepest.cause != null && visited.add(deepest)) {
        deepest = deepest.cause!!
    }
    return deepest.message?.takeIf { it.isNotBlank() } ?: deepest.javaClass.simpleName
}

private const val AGENT_SYSTEM_PROMPT = """You are an offline Android router. Output one bare JSON object, never markdown.
Allowed routes:
{"action":"answer","text":"..."}
{"action":"tool","name":"get_device_info","args":{}}
{"action":"tool","name":"get_battery_info","args":{}}
{"action":"tool","name":"get_storage_info","args":{}}
{"action":"workflow","name":"phone_health_check","args":{}}
Use a tool only for current phone facts. Use the workflow for overall health. Otherwise answer. Never invent names or arguments. After tool data, return action=answer."""

private data class SelectedModel(val uri: Uri, val name: String)

private data class AgentTestCase(
    val id: String,
    val prompt: String,
    val expectedTool: String? = null,
    val expectedWorkflow: String? = null,
)

private val AGENT_TEST_CASES = listOf(
    AgentTestCase("storage-01", "How much storage do I have?", "get_storage_info"),
    AgentTestCase("storage-02", "How much free space is left on this phone?", "get_storage_info"),
    AgentTestCase("storage-03", "Am I running out of disk space?", "get_storage_info"),
    AgentTestCase("storage-04", "Show my internal storage usage.", "get_storage_info"),
    AgentTestCase("storage-05", "How many gigabytes can I still save?", "get_storage_info"),
    AgentTestCase("storage-06", "Is there room for another large model?", "get_storage_info"),
    AgentTestCase("storage-07", "Check available space, not battery level.", "get_storage_info"),
    AgentTestCase("storage-08", "What fraction of the phone storage is free?", "get_storage_info"),
    AgentTestCase("storage-09", "Tell me the used and total storage.", "get_storage_info"),
    AgentTestCase("storage-10", "Could a 2 GB file fit on this device right now?", "get_storage_info"),
    AgentTestCase("device-01", "What Android version is this?", "get_device_info"),
    AgentTestCase("device-02", "What CPU ABI does this device use?", "get_device_info"),
    AgentTestCase("device-03", "Which phone model am I using?", "get_device_info"),
    AgentTestCase("device-04", "Who manufactured this handset?", "get_device_info"),
    AgentTestCase("device-05", "Is this device arm64-v8a?", "get_device_info"),
    AgentTestCase("device-06", "Report the model, Android release, and architecture.", "get_device_info"),
    AgentTestCase("device-07", "How much physical RAM does this phone expose?", "get_device_info"),
    AgentTestCase("device-08", "Identify the current device without discussing storage.", "get_device_info"),
    AgentTestCase("device-09", "Which Android SDK and OS version are running?", "get_device_info"),
    AgentTestCase("device-10", "Give me this phone's hardware identity.", "get_device_info"),
    AgentTestCase("battery-01", "Is my battery hot?", "get_battery_info"),
    AgentTestCase("battery-02", "What is my battery percentage?", "get_battery_info"),
    AgentTestCase("battery-03", "Is the phone charging right now?", "get_battery_info"),
    AgentTestCase("battery-04", "Tell me the current battery temperature.", "get_battery_info"),
    AgentTestCase("battery-05", "How much charge remains?", "get_battery_info"),
    AgentTestCase("battery-06", "Check battery heat, not free storage.", "get_battery_info"),
    AgentTestCase("battery-07", "Should I let the battery cool down?", "get_battery_info"),
    AgentTestCase("battery-08", "Read the live charging state.", "get_battery_info"),
    AgentTestCase("battery-09", "Is my current battery temperature above 40 C?", "get_battery_info"),
    AgentTestCase("battery-10", "Give me charge level and temperature.", "get_battery_info"),
    AgentTestCase("answer-01", "Tell me a joke."),
    AgentTestCase("answer-02", "What is 2+2?"),
    AgentTestCase("answer-03", "Write a five-word greeting."),
    AgentTestCase("answer-04", "What is the capital of France?"),
    AgentTestCase("answer-05", "Explain what an ABI is in one sentence."),
    AgentTestCase("answer-06", "Give me a short riddle."),
    AgentTestCase("answer-07", "Say hello without checking my device."),
    AgentTestCase("answer-08", "What does CPU stand for?"),
    AgentTestCase("answer-09", "Name a primary color."),
    AgentTestCase("answer-10", "Complete this sequence: 2, 4, 6, 8, ?"),
    AgentTestCase("health-01", "Run a phone health check.", expectedWorkflow = PHONE_HEALTH_CHECK),
    AgentTestCase("health-02", "Check whether my phone is healthy.", expectedWorkflow = PHONE_HEALTH_CHECK),
    AgentTestCase("health-03", "Inspect my phone and suggest health improvements.", expectedWorkflow = PHONE_HEALTH_CHECK),
    AgentTestCase("health-04", "Are storage and battery temperature okay?", expectedWorkflow = PHONE_HEALTH_CHECK),
    AgentTestCase("health-05", "Give this handset a complete health assessment.", expectedWorkflow = PHONE_HEALTH_CHECK),
    AgentTestCase("health-06", "Check both free space and battery heat together.", expectedWorkflow = PHONE_HEALTH_CHECK),
    AgentTestCase("health-07", "Diagnose the phone's overall condition.", expectedWorkflow = PHONE_HEALTH_CHECK),
    AgentTestCase("health-08", "Do a device wellness check and recommend actions.", expectedWorkflow = PHONE_HEALTH_CHECK),
    AgentTestCase("health-09", "Is this phone ready for a sustained AI workload?", expectedWorkflow = PHONE_HEALTH_CHECK),
    AgentTestCase("health-10", "Review device, battery, and storage health.", expectedWorkflow = PHONE_HEALTH_CHECK),
)

private data class TimedGeneration(
    val text: String,
    val pieces: Int,
    val latencyMs: Long,
    val ttftMs: Long?,
)

private suspend fun collectGeneration(flow: Flow<String>): Pair<String, Int> {
    val text = StringBuilder()
    var pieces = 0
    flow.collect {
        text.append(it)
        pieces++
    }
    return text.toString() to pieces
}

private suspend fun collectTimedGeneration(flow: Flow<String>): TimedGeneration {
    val started = SystemClock.elapsedRealtime()
    var firstPieceAt: Long? = null
    val text = StringBuilder()
    var pieces = 0
    flow.collect {
        if (firstPieceAt == null) firstPieceAt = SystemClock.elapsedRealtime()
        text.append(it)
        pieces++
    }
    val finished = SystemClock.elapsedRealtime()
    return TimedGeneration(text.toString(), pieces, finished - started, firstPieceAt?.minus(started))
}

private fun createAgentBackend(
    context: Context,
    engine: InferenceEngine,
    onProgress: (AgentProgress) -> Unit = {},
): AgentBackend =
    AgentBackend(
        generator = AgentGenerator { request, maxTokens ->
            val (raw, pieces) = collectGeneration(engine.sendUserPrompt(request, maxTokens))
            Log.i(TAG_AGENT, "model_json=$raw")
            GeneratedText(raw, pieces)
        },
        tools = ReadOnlyToolExecutor { name -> executeReadOnlyTool(context, name).toString() },
        beforeRepair = { withContext(Dispatchers.IO) { ConversationReset.reset() } },
        onProgress = onProgress,
    )

private suspend fun prepareFreshAgent(engine: InferenceEngine, modelPath: String) {
    when (val state = engine.state.value) {
        is InferenceEngine.State.ModelReady ->
            withContext(Dispatchers.IO) { ConversationReset.reset() }
        is InferenceEngine.State.Initialized -> {
            engine.loadModel(modelPath)
            engine.setSystemPrompt(AGENT_SYSTEM_PROMPT)
            withContext(Dispatchers.IO) { ConversationReset.reset() }
        }
        else -> error("Cannot reset agent while engine is ${state.javaClass.simpleName}")
    }
}

private suspend fun loadModelFile(engine: InferenceEngine, modelFile: File): Long {
    val readyState = engine.state.first {
        it is InferenceEngine.State.Initialized ||
            it is InferenceEngine.State.ModelReady ||
            it is InferenceEngine.State.Error
    }
    if (readyState is InferenceEngine.State.ModelReady || readyState is InferenceEngine.State.Error) {
        engine.cleanUp()
        engine.state.first { it is InferenceEngine.State.Initialized }
    }
    val started = SystemClock.elapsedRealtime()
    engine.loadModel(modelFile.absolutePath)
    engine.setSystemPrompt(AGENT_SYSTEM_PROMPT)
    val elapsed = SystemClock.elapsedRealtime() - started
    Log.i(
        TAG_METRICS,
        "model=${modelFile.name} load_ms=$elapsed context_tokens=1024 cpu_only=true",
    )
    return elapsed
}

private fun recoverInferenceEngine(engine: InferenceEngine) {
    when (engine.state.value) {
        is InferenceEngine.State.ModelReady,
        is InferenceEngine.State.Error,
        -> runCatching { engine.cleanUp() }.onFailure {
            Log.e(TAG, "Unable to recover inference engine", it)
        }
        else -> Log.w(TAG, "Engine recovery skipped in ${engine.state.value.javaClass.simpleName}")
    }
}

private suspend fun awaitThermalCooldown(
    context: Context,
    onWaiting: (Double) -> Unit,
) {
    while (true) {
        currentCoroutineContext().ensureActive()
        val temperature = getBatteryInfo(context).getDouble("temperatureC")
        if (temperature <= AGENT_EVAL_MAX_START_TEMPERATURE_C) return
        onWaiting(temperature)
        delay(AGENT_EVAL_COOLDOWN_POLL_MS)
    }
}

private data class AgentEvaluationProgress(
    val completed: Int,
    val total: Int,
    val correct: Int,
    val strictFirstPass: Int,
    val normalizedFirstPass: Int,
    val finalAccepted: Int,
    val repairAttempts: Int,
    val repaired: Int,
    val elapsedMs: Long,
    val currentCase: String? = null,
)

private suspend fun runAgentTests(
    context: Context,
    engine: InferenceEngine,
    modelPath: String,
    artifactStem: String = "agent",
    onProgress: (AgentEvaluationProgress) -> Unit,
): String {
    val initialTemperatureC = getBatteryInfo(context).getDouble("temperatureC")
    require(initialTemperatureC <= AGENT_EVAL_MAX_START_TEMPERATURE_C) {
        "Battery is ${formatMetric(initialTemperatureC)} C; cool to $AGENT_EVAL_MAX_START_TEMPERATURE_C C or below"
    }
    var strictFirstPass = 0
    var normalizedFirstPass = 0
    var finalAccepted = 0
    var repairAttempts = 0
    var repairedSelections = 0
    var correctRoutes = 0
    val details = JSONArray()
    val csv = StringBuilder(AGENT_EVAL_CSV_HEADER).append('\n')
    val jsonName = if (artifactStem == "agent") "agent-test-result.json" else "$artifactStem-result.json"
    val csvName = if (artifactStem == "agent") "agent-evaluation.csv" else "$artifactStem.csv"
    val report = JSONObject()
        .put("schemaVersion", 2)
        .put("suite", "tool-routing-3-tools-v2")
        .put("llamaCppCommit", LLAMA_CPP_COMMIT)
        .put("buildFlags", LLAMA_BUILD_FLAGS)
        .put("modelFile", File(modelPath).name)
        .put("modelBytes", File(modelPath).length())
        .put("quantizationFromFilename", inferQuantization(File(modelPath).name))
        .put("device", getDeviceInfo(context))
        .put("startTemperatureC", initialTemperatureC)
        .put("maxStartTemperatureC", AGENT_EVAL_MAX_START_TEMPERATURE_C)
        .put("tests", AGENT_TEST_CASES.size)
        .put("details", details)
    updateEvaluationReport(report, 0, 0, 0, 0, 0, 0, 0, complete = false)
    writeEvaluationArtifacts(context, jsonName, csvName, report, csv)
    val suiteStarted = SystemClock.elapsedRealtime()
    for (case in AGENT_TEST_CASES) {
        currentCoroutineContext().ensureActive()
        onProgress(
            AgentEvaluationProgress(
                completed = details.length(),
                total = AGENT_TEST_CASES.size,
                correct = correctRoutes,
                strictFirstPass = strictFirstPass,
                normalizedFirstPass = normalizedFirstPass,
                finalAccepted = finalAccepted,
                repairAttempts = repairAttempts,
                repaired = repairedSelections,
                elapsedMs = SystemClock.elapsedRealtime() - suiteStarted,
                currentCase = case.id,
            ),
        )
        prepareFreshAgent(engine, modelPath)
        val generations = mutableListOf<TimedGeneration>()
        val backend = AgentBackend(
            generator = AgentGenerator { request, maxTokens ->
                collectTimedGeneration(engine.sendUserPrompt(request, maxTokens)).also {
                    generations += it
                    Log.i(TAG_AGENT, "eval_model_json=${it.text}")
                }.let { GeneratedText(it.text, it.pieces) }
            },
            tools = ReadOnlyToolExecutor { error("Evaluation must not execute tools") },
            beforeRepair = { withContext(Dispatchers.IO) { ConversationReset.reset() } },
        )
        var actualTool: String? = null
        var actualWorkflow: String? = null
        var actualAction: String? = null
        var jsonValid = false
        var schemaNormalized = false
        var errorType: String? = null
        val pssBeforeKb = Debug.getPss()
        val temperatureBeforeC = getBatteryInfo(context).getDouble("temperatureC")
        try {
            val selection = withTimeout(AGENT_EVAL_CASE_TIMEOUT_MS) {
                backend.select(case.prompt)
            }
            val decision = selection.decision
            jsonValid = true
            schemaNormalized = decision.schemaRepaired
            finalAccepted++
            when {
                selection.repairAttempted -> repairedSelections++
                schemaNormalized -> normalizedFirstPass++
                else -> strictFirstPass++
            }
            actualAction = decision.action
            actualTool = decision.toolName
            actualWorkflow = decision.workflowName
            if (actualRoute(decision) == expectedRoute(case)) correctRoutes++
        } catch (error: Throwable) {
            errorType = classifyAgentEvaluationError(error)
            Log.w(TAG_AGENT, "Test failed for: ${case.prompt}", error)
        }
        val repairAttempted = generations.size > 1
        if (repairAttempted) repairAttempts++
        val pssAfterKb = Debug.getPss()
        val temperatureAfterC = getBatteryInfo(context).getDouble("temperatureC")
        val latencyMs = generations.sumOf(TimedGeneration::latencyMs)
        val pieces = generations.sumOf(TimedGeneration::pieces)
        val ttftMs = generations.firstOrNull()?.ttftMs
        val rate = if (latencyMs > 0) pieces * 1000.0 / latencyMs else 0.0
        val correct = jsonValid && when {
            actualWorkflow != null -> "workflow:$actualWorkflow"
            actualTool != null -> "tool:$actualTool"
            actualAction != null -> actualAction
            else -> "invalid"
        } == expectedRoute(case)
        details.put(
            JSONObject()
                .put("id", case.id)
                .put("prompt", case.prompt)
                .put("expectedRoute", expectedRoute(case))
                .put("actualRoute", when {
                    actualWorkflow != null -> "workflow:$actualWorkflow"
                    actualTool != null -> "tool:$actualTool"
                    actualAction != null -> actualAction
                    else -> JSONObject.NULL
                })
                .put("correct", correct)
                .put("expectedTool", case.expectedTool ?: JSONObject.NULL)
                .put("actualTool", actualTool ?: JSONObject.NULL)
                .put("expectedWorkflow", case.expectedWorkflow ?: JSONObject.NULL)
                .put("actualWorkflow", actualWorkflow ?: JSONObject.NULL)
                .put("finalSchemaAccepted", jsonValid)
                .put("strictSchemaOnFirstAttempt", jsonValid && !repairAttempted && !schemaNormalized)
                .put("schemaNormalized", schemaNormalized)
                .put("repairAttempted", repairAttempted)
                .put("generationAttempts", JSONArray().also { attempts ->
                    generations.forEachIndexed { index, generation ->
                        attempts.put(
                            JSONObject()
                                .put("attempt", index + 1)
                                .put("kind", if (index == 0) "route" else "repair")
                                .put("rawOutput", generation.text)
                                .put("latencyMs", generation.latencyMs)
                                .put("ttftMs", generation.ttftMs ?: JSONObject.NULL)
                                .put("generatedPieces", generation.pieces),
                        )
                    }
                })
                .put("errorType", errorType ?: JSONObject.NULL)
                .put("latencyMs", latencyMs)
                .put("ttftMs", ttftMs ?: JSONObject.NULL)
                .put("generatedPieces", pieces)
                .put("exposedPiecesPerSecond", rate)
                .put("pssBeforeKb", pssBeforeKb)
                .put("pssAfterKb", pssAfterKb)
                .put("temperatureBeforeC", temperatureBeforeC)
                .put("temperatureAfterC", temperatureAfterC),
        )
        csv.appendCsvRow(
            case.id, case.prompt, expectedRoute(case),
            if (jsonValid) when {
                actualWorkflow != null -> "workflow:$actualWorkflow"
                actualTool != null -> "tool:$actualTool"
                else -> actualAction.orEmpty()
            } else "invalid",
            correct, jsonValid && !repairAttempted && !schemaNormalized, schemaNormalized,
            repairAttempted, jsonValid, errorType.orEmpty(), latencyMs, ttftMs,
            pieces, formatMetric(rate), pssBeforeKb, pssAfterKb,
            formatMetric(temperatureBeforeC), formatMetric(temperatureAfterC),
        )
        onProgress(
            AgentEvaluationProgress(
                completed = details.length(),
                total = AGENT_TEST_CASES.size,
                correct = correctRoutes,
                strictFirstPass = strictFirstPass,
                normalizedFirstPass = normalizedFirstPass,
                finalAccepted = finalAccepted,
                repairAttempts = repairAttempts,
                repaired = repairedSelections,
                elapsedMs = SystemClock.elapsedRealtime() - suiteStarted,
            ),
        )
        updateEvaluationReport(
            report = report,
            completedTests = details.length(),
            strictFirstPass = strictFirstPass,
            normalizedFirstPass = normalizedFirstPass,
            repairAttempts = repairAttempts,
            repairedSelections = repairedSelections,
            finalAccepted = finalAccepted,
            correctRoutes = correctRoutes,
            complete = false,
        )
        writeEvaluationArtifacts(context, jsonName, csvName, report, csv)
    }
    updateEvaluationReport(
        report, details.length(), strictFirstPass, normalizedFirstPass, repairAttempts,
        repairedSelections, finalAccepted, correctRoutes, complete = true,
    )
    writeEvaluationArtifacts(context, jsonName, csvName, report, csv)
    Log.i(TAG_AGENT, report.toString())
    prepareFreshAgent(engine, modelPath)
    return "Completed: ${AGENT_TEST_CASES.size}/${AGENT_TEST_CASES.size}\n" +
        "Correct route: $correctRoutes/${AGENT_TEST_CASES.size}\n" +
        "Strict first-pass schema: $strictFirstPass/${AGENT_TEST_CASES.size} | " +
        "Normalized: $normalizedFirstPass | Repair success: $repairedSelections/$repairAttempts | " +
        "Final accepted: $finalAccepted/${AGENT_TEST_CASES.size}\n" +
        "Saved $jsonName and $csvName"
}

private fun updateEvaluationReport(
    report: JSONObject,
    completedTests: Int,
    strictFirstPass: Int,
    normalizedFirstPass: Int,
    repairAttempts: Int,
    repairedSelections: Int,
    finalAccepted: Int,
    correctRoutes: Int,
    complete: Boolean,
) {
    report
        .put("completedTests", completedTests)
        .put("complete", complete)
        .put("strictSchemaFirstPass", strictFirstPass)
        .put("normalizedFirstPass", normalizedFirstPass)
        .put("repairAttempts", repairAttempts)
        .put("repairedSelections", repairedSelections)
        .put("finalAcceptedSelections", finalAccepted)
        .put("correctRoutes", correctRoutes)
}

private fun writeEvaluationArtifacts(
    context: Context,
    jsonName: String,
    csvName: String,
    report: JSONObject,
    csv: StringBuilder,
) {
    context.openFileOutput(jsonName, Context.MODE_PRIVATE).use {
        it.write(report.toString(2).toByteArray())
    }
    context.openFileOutput(csvName, Context.MODE_PRIVATE).use {
        it.write(csv.toString().toByteArray())
    }
}

private fun expectedRoute(case: AgentTestCase): String = when {
    case.expectedWorkflow != null -> "workflow:${case.expectedWorkflow}"
    case.expectedTool != null -> "tool:${case.expectedTool}"
    else -> "answer"
}

private fun actualRoute(decision: AgentDecision): String = when (decision.action) {
    "workflow" -> "workflow:${decision.workflowName}"
    "tool" -> "tool:${decision.toolName}"
    else -> decision.action
}

private fun classifyAgentEvaluationError(error: Throwable): String = when {
    error.message.orEmpty().contains("bare JSON") -> "invalid_json"
    error.message.orEmpty().contains("schema") -> "invalid_schema"
    error.message.orEmpty().contains("Unknown") -> "unknown_action_or_tool"
    else -> "generation_or_runtime_error"
}

private fun StringBuilder.appendCsvRow(vararg fields: Any?) {
    append(fields.joinToString(",") { field ->
        val value = field?.toString().orEmpty()
        "\"${value.replace("\"", "\"\"")}\""
    }).append('\n')
}

private fun inferQuantization(filename: String): String =
    Regex("(?i)(Q[0-9]+(?:_[A-Z0-9]+)*)").find(filename)?.value ?: "unknown"

private fun formatMetric(value: Double): String = "%.3f".format(Locale.US, value)

private fun safeFileStem(filename: String): String = filename
    .removeSuffix(".gguf")
    .replace(Regex("[^A-Za-z0-9._-]"), "_")
    .take(80)

private fun writeQueueManifest(
    context: Context,
    runId: Long,
    queue: List<SelectedModel>,
    outcomes: List<JSONObject>,
    state: String,
) {
    val selected = JSONArray().also { array -> queue.forEach { array.put(it.name) } }
    val results = JSONArray().also { array -> outcomes.forEach(array::put) }
    val report = JSONObject()
        .put("schemaVersion", 1)
        .put("runId", runId)
        .put("suite", "tool-routing-3-tools-v2")
        .put("state", state)
        .put("cancelled", state == "cancelled")
        .put("selectedModels", selected)
        .put("outcomes", results)
    context.openFileOutput("agent-routing-$runId-queue.json", Context.MODE_PRIVATE).use {
        it.write(report.toString(2).toByteArray())
    }
}

private fun formatQueueOutcome(outcome: JSONObject): String = when (outcome.getString("status")) {
    "completed" -> "${outcome.getString("model")}: completed"
    else -> "${outcome.getString("model")}: FAILED - ${outcome.optString("error", "unknown error")}"
}

private fun executeReadOnlyTool(context: Context, name: String): JSONObject = when (name) {
    "get_device_info" -> getDeviceInfo(context)
    "get_battery_info" -> getBatteryInfo(context)
    "get_storage_info" -> getStorageInfo()
    else -> error("Unknown tool: $name")
}

private fun getDeviceInfo(context: Context): JSONObject {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
    return JSONObject()
        .put("model", Build.MODEL)
        .put("manufacturer", Build.MANUFACTURER)
        .put("androidVersion", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("cpuAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        .put("totalRamBytes", memory.totalMem)
        .put("availableRamBytes", memory.availMem)
}

private fun getBatteryInfo(context: Context): JSONObject {
    val battery = context.registerReceiver(
        null,
        android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    ) ?: error("Battery information unavailable")
    val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = battery.getIntExtra(
        BatteryManager.EXTRA_STATUS,
        BatteryManager.BATTERY_STATUS_UNKNOWN,
    )
    return JSONObject()
        .put(
            "levelPercent",
            if (level >= 0 && scale > 0) level * 100.0 / scale else JSONObject.NULL,
        )
        .put(
            "temperatureC",
            battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
        )
        .put(
            "isCharging",
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
        )
        .put("statusCode", status)
}

private fun getStorageInfo(): JSONObject {
    val stats = StatFs(Environment.getDataDirectory().absolutePath)
    val total = stats.totalBytes
    val available = stats.availableBytes
    return JSONObject()
        .put("totalBytes", total)
        .put("availableBytes", available)
        .put("usedBytes", total - available)
}

private fun batteryTemperatureC(context: Context): Double {
    val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return battery?.getIntExtra("temperature", 0)?.div(10.0) ?: 0.0
}

private fun approximateGgufTier(availableBytes: Long): String = when {
    availableBytes < 1L * GIB -> "up to about 0.5B parameters at Q4"
    availableBytes < 2L * GIB -> "about 0.5B–1B parameters at Q4"
    availableBytes < 3L * GIB -> "about 1B–1.5B parameters at Q4"
    availableBytes < 4L * GIB -> "about 1B–3B parameters at Q4"
    else -> "about 3B parameters at Q4; larger models require measurement"
}

private fun runMobileNetBenchmark(context: Context): String {
    return try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val beforeTemp = batteryTemperatureC(context)
        val loadStart = SystemClock.elapsedRealtimeNanos()
        val descriptor = context.assets.openFd("mobilenet_v1.tflite")
        val model = FileInputStream(descriptor.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY,
            descriptor.startOffset,
            descriptor.declaredLength,
        )
        val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStart) / 1_000_000.0
        val input = ByteBuffer.allocateDirect(224 * 224 * 3).order(ByteOrder.nativeOrder())
        val inferenceOutput = ByteBuffer.allocateDirect(1001).order(ByteOrder.nativeOrder())
        repeat(input.capacity()) { input.put((it % 256).toByte()) }
        input.rewind()

        val firstStart = SystemClock.elapsedRealtimeNanos()
        interpreter.run(input, inferenceOutput)
        val firstMs = (SystemClock.elapsedRealtimeNanos() - firstStart) / 1_000_000.0
        val start = SystemClock.elapsedRealtime()
        val cpuStart = android.os.Process.getElapsedCpuTime()
        val latencies = ArrayList<Double>()
        var peakPssKb = 0
        while (SystemClock.elapsedRealtime() - start < BENCHMARK_DURATION_MS) {
            input.rewind()
            inferenceOutput.rewind()
            val inferenceStart = SystemClock.elapsedRealtimeNanos()
            interpreter.run(input, inferenceOutput)
            latencies += (SystemClock.elapsedRealtimeNanos() - inferenceStart) / 1_000_000.0
            if (latencies.size % 10 == 0) {
                val processMemory = Debug.MemoryInfo()
                Debug.getMemoryInfo(processMemory)
                peakPssKb = maxOf(peakPssKb, processMemory.totalPss)
            }
        }
        val elapsedMs = SystemClock.elapsedRealtime() - start
        val cpuMs = android.os.Process.getElapsedCpuTime() - cpuStart
        interpreter.close()
        descriptor.close()
        val averageMs = latencies.average()
        val tailCount = maxOf(1, latencies.size / 6)
        val finalAverageMs = latencies.takeLast(tailCount).average()
        val afterTemp = batteryTemperatureC(context)
        val throughput = latencies.size * 1000.0 / elapsedMs
        val result = """{"model":"MobileNet_V1_1.0_224_uint8","modelBytes":4276352,"threads":4,"durationMs":$elapsedMs,"loadMs":${"%.3f".format(Locale.US, loadMs)},"firstInferenceMs":${"%.3f".format(Locale.US, firstMs)},"inferences":${latencies.size},"averageMs":${"%.3f".format(Locale.US, averageMs)},"throughputPerSec":${"%.3f".format(Locale.US, throughput)},"final10sAverageMs":${"%.3f".format(Locale.US, finalAverageMs)},"processCpuPercentOfOneCore":${"%.1f".format(Locale.US, cpuMs * 100.0 / elapsedMs)},"peakPssKb":$peakPssKb,"availableRamBytes":${memoryInfo.availMem},"temperatureBeforeC":$beforeTemp,"temperatureAfter60sC":$afterTemp}"""
        context.openFileOutput("benchmark-result.json", Context.MODE_PRIVATE).use {
            it.write(result.toByteArray())
        }
        Log.i(TAG_BENCHMARK, result)
        "Done: %.1f inf/s, %.1f ms average\nBattery: %.1f°C → %.1f°C\nApproximate GGUF tier: %s\nRAM-based estimate only; MobileNet speed does not predict LLM speed.".format(
            Locale.US,
            throughput,
            averageMs,
            beforeTemp,
            afterTemp,
            approximateGgufTier(memoryInfo.availMem),
        )
    } catch (error: Throwable) {
        Log.e(TAG_BENCHMARK, "Benchmark failed", error)
        "Benchmark failed: ${error.message ?: error.javaClass.simpleName}"
    }
}

private const val GIB = 1024L * 1024L * 1024L
private const val BENCHMARK_DURATION_MS = 60_000L
private const val TAG_BENCHMARK = "PocketBenchmark"

private fun displayName(context: Context, uri: Uri): String {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, null, null, null, null)
        val index = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
        if (cursor?.moveToFirst() == true && index >= 0) cursor.getString(index) else "model.gguf"
    } finally {
        cursor?.close()
    }
}

private fun copyModelToPrivateStorage(context: Context, uri: Uri, displayName: String): File {
    require(displayName.endsWith(".gguf", ignoreCase = true)) { "Select a .gguf file" }
    val modelsDirectory = File(context.filesDir, "models").apply { mkdirs() }
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val destination = File(modelsDirectory, safeName)
    val temporary = File(modelsDirectory, "$safeName.copying")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Unable to open selected model" }
        FileOutputStream(temporary).use { output -> input.copyTo(output, 1024 * 1024) }
    }
    check(temporary.length() > 0) { "Selected model is empty" }
    if (destination.exists()) destination.delete()
    check(temporary.renameTo(destination)) { "Unable to finalize private model copy" }
    return destination
}

private const val TAG = "PocketLlama"
private const val TAG_METRICS = "PocketLlamaMetrics"
private const val TAG_AGENT = "PocketAgent"
private const val LLAMA_CPP_COMMIT = "a94d563ed801d1da1b8c2432946de07d0231bb3d"
private const val LLAMA_BUILD_FLAGS = "arm64-v8a;GGML_SYSTEM_ARCH=ARM;GGML_CPU_KLEIDIAI=OFF;GGML_OPENMP=OFF;ctx=1024;cpu-only"
private const val AGENT_EVAL_MAX_START_TEMPERATURE_C = 38.0
private const val AGENT_EVAL_CASE_TIMEOUT_MS = 120_000L
private const val AGENT_EVAL_COOLDOWN_POLL_MS = 30_000L
private const val AGENT_EVAL_CSV_HEADER = "id,prompt,expected_route,actual_route,correct,strict_schema_first_attempt,schema_normalized,repair_attempted,final_schema_accepted,error_type,latency_ms,ttft_ms,generated_pieces,exposed_pieces_per_second,pss_before_kb,pss_after_kb,temperature_before_c,temperature_after_c"
private const val TAG_HEALTH = "PocketHealth"
