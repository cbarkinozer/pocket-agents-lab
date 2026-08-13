package com.pocketagentslab

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
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
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
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

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("No GGUF selected") }
    var modelStatus by remember { mutableStateOf("Select a GGUF model, then load it") }
    var prompt by remember { mutableStateOf("What is 2+2?") }
    var output by remember { mutableStateOf("") }
    var metrics by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var isBenchmarkRunning by remember { mutableStateOf(false) }
    var benchmarkStatus by remember { mutableStateOf("Benchmark not run") }
    var isModelLoaded by remember { mutableStateOf(false) }
    val engine = remember { AiChat.getInferenceEngine(context.applicationContext) }
    val controlsBusy = isBusy || isBenchmarkRunning

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (error: SecurityException) {
                Log.w(TAG, "Provider did not grant persistable access", error)
            }
            selectedUri = uri
            selectedName = displayName(context, uri)
            modelStatus = "Selected: $selectedName"
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

        Text("Device capability", style = MaterialTheme.typography.titleMedium)
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

        Text(selectedName, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !controlsBusy,
            ) {
                Text("Select GGUF")
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
                Text("Load Model")
            }
        }
        Text(modelStatus)

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
                    metrics = "Generating…"
                    try {
                        val started = SystemClock.elapsedRealtime()
                        var generatedPieces = 0
                        engine.sendUserPrompt(
                            prompt.trim(),
                            predictLength = MAX_GENERATED_TOKENS,
                        ).collect { piece ->
                            output += piece
                            generatedPieces++
                        }
                        val generationMs = SystemClock.elapsedRealtime() - started
                        val piecesPerSecond = if (generationMs > 0) {
                            generatedPieces * 1000.0 / generationMs
                        } else {
                            0.0
                        }
                        metrics = "Generation: ${generationMs} ms  •  %.2f tokens/s".format(
                            Locale.US,
                            piecesPerSecond,
                        )
                        Log.i(
                            TAG_METRICS,
                            "generation_ms=$generationMs generated_token_pieces=$generatedPieces " +
                                "tokens_per_second=${"%.3f".format(Locale.US, piecesPerSecond)}",
                        )
                    } catch (error: Throwable) {
                        output = "Generation failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "Generation failed", error)
                    } finally {
                        isBusy = false
                    }
                }
            },
            enabled = isModelLoaded && prompt.isNotBlank() && !controlsBusy,
        ) {
            Text(if (isBusy && isModelLoaded) "Generating…" else "Generate")
        }
        Text(metrics)
        OutlinedTextField(
            value = output,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model output") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp),
        )
    }
}

private const val MAX_GENERATED_TOKENS = 512

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
