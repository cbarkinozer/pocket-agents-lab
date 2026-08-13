package com.pocketagentslab

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Greeting(intent.getBooleanExtra("autoBenchmark", false))
                }
            }
        }
    }
}

@Composable
private fun Greeting(autoBenchmark: Boolean) {
    val context = LocalContext.current
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)

    val ramGB = memoryInfo.totalMem / 1024.0 / 1024 / 1024
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
    val androidVersion = Build.VERSION.RELEASE
    var benchmarkStatus by remember { mutableStateOf("Benchmark not run") }
    var running by remember { mutableStateOf(false) }

    fun startBenchmark() {
        if (running) return
        running = true
        benchmarkStatus = "Running MobileNet V1 for 60 seconds…"
        thread(name = "tflite-benchmark") {
            benchmarkStatus = runMobileNetBenchmark(context)
            running = false
        }
    }

    LaunchedEffect(autoBenchmark) {
        if (autoBenchmark) startBenchmark()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = "Hello Pocket Agents Lab",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        Text(
            text = "RAM: %.1f GB\nABI: %s\nAndroid: %s".format(
                ramGB,
                abi,
                androidVersion,
            ),
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { startBenchmark() },
            enabled = !running,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(if (running) "Benchmark running…" else "Run AI benchmark")
        }
        Text(
            text = benchmarkStatus,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun batteryTemperatureC(context: Context): Double {
    val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return battery?.getIntExtra("temperature", 0)?.div(10.0) ?: 0.0
}

private fun runMobileNetBenchmark(context: Context): String {
    return try {
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
        val output = ByteBuffer.allocateDirect(1001).order(ByteOrder.nativeOrder())
        repeat(input.capacity()) { input.put((it % 256).toByte()) }
        input.rewind()

        val firstStart = SystemClock.elapsedRealtimeNanos()
        interpreter.run(input, output)
        val firstMs = (SystemClock.elapsedRealtimeNanos() - firstStart) / 1_000_000.0
        val start = SystemClock.elapsedRealtime()
        val cpuStart = android.os.Process.getElapsedCpuTime()
        val latencies = ArrayList<Double>()
        var peakPssKb = 0
        while (SystemClock.elapsedRealtime() - start < 60_000) {
            input.rewind()
            output.rewind()
            val inferenceStart = SystemClock.elapsedRealtimeNanos()
            interpreter.run(input, output)
            latencies += (SystemClock.elapsedRealtimeNanos() - inferenceStart) / 1_000_000.0
            if (latencies.size % 10 == 0) {
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                peakPssKb = maxOf(peakPssKb, memory.totalPss)
            }
        }
        val elapsedMs = SystemClock.elapsedRealtime() - start
        val cpuMs = android.os.Process.getElapsedCpuTime() - cpuStart
        interpreter.close()
        descriptor.close()
        val averageMs = latencies.average()
        val tailCount = maxOf(1, latencies.size / 6)
        val finalAverageMs = latencies.takeLast(tailCount).average()
        val result = """{"model":"MobileNet_V1_1.0_224_uint8","modelBytes":4276352,"threads":4,"durationMs":$elapsedMs,"loadMs":${"%.3f".format(Locale.US, loadMs)},"firstInferenceMs":${"%.3f".format(Locale.US, firstMs)},"inferences":${latencies.size},"averageMs":${"%.3f".format(Locale.US, averageMs)},"throughputPerSec":${"%.3f".format(Locale.US, latencies.size * 1000.0 / elapsedMs)},"final10sAverageMs":${"%.3f".format(Locale.US, finalAverageMs)},"processCpuPercentOfOneCore":${"%.1f".format(Locale.US, cpuMs * 100.0 / elapsedMs)},"peakPssKb":$peakPssKb,"temperatureBeforeC":$beforeTemp,"temperatureAfter60sC":${batteryTemperatureC(context)}}"""
        context.openFileOutput("benchmark-result.json", Context.MODE_PRIVATE).use { it.write(result.toByteArray()) }
        android.util.Log.i("PocketBenchmark", result)
        "Done: %.1f inf/s, %.1f ms average\n%.1f°C → %.1f°C".format(
            Locale.US,
            latencies.size * 1000.0 / elapsedMs,
            averageMs,
            beforeTemp,
            batteryTemperatureC(context),
        )
    } catch (error: Throwable) {
        val message = "Benchmark failed: ${error.stackTraceToString()}"
        android.util.Log.e("PocketBenchmark", message)
        message
    }
}
