package com.pocketagentslab

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/** One app-owned, loopback-only process. Never attaches to an unrelated server. */
internal class ReflexRuntime(context: Context) {
    private val nativeDir = context.applicationInfo.nativeLibraryDir
    private val logFile = File(context.filesDir, "reflex-runtime.log")
    private var process: Process? = null
    private var modelPath: String? = null
    val isRunning: Boolean get() = process?.isAlive == true
    val baseUrl = "http://127.0.0.1:8092"

    suspend fun start(path: String) = withContext(Dispatchers.IO) {
        if (modelPath == path && process?.isAlive == true) return@withContext
        stop()
        val executable = File(nativeDir, "libreflex-server.so")
        check(executable.canExecute()) { "Decision runtime missing from this APK. Build with prepare_reflex_android_runtime.py." }
        // Refuse to attach to a server owned by another app or shell session.
        ServerSocket().use { socket -> socket.bind(InetSocketAddress("127.0.0.1", 8092)) }
        val command = listOf(
            executable.absolutePath, "-m", path, "--host", "127.0.0.1", "--port", "8092",
            "-c", "2048", "-t", "4", "-tb", "4", "-ngl", "0", "--fit", "off",
            "-fa", "off", "--no-repack", "--jinja", "--parallel", "1",
        )
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile)
        builder.environment()["LD_LIBRARY_PATH"] = nativeDir
        process = builder.start()
        try {
            withTimeout(90_000) {
                while (true) {
                    check(process?.isAlive == true) { "Decision runtime exited: ${logFile.readText().takeLast(1200)}" }
                    if (healthy()) break
                    delay(200)
                }
            }
            modelPath = path
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    private fun healthy(): Boolean {
        val connection = URL("$baseUrl/health").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.responseCode == 200
        } catch (_: java.io.IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    fun stop() {
        val owned = process
        process = null
        modelPath = null
        if (owned != null) {
            owned.destroy()
            if (!owned.waitFor(3, TimeUnit.SECONDS)) {
                owned.destroyForcibly()
                check(owned.waitFor(3, TimeUnit.SECONDS)) { "Could not stop decision runtime before loading another model" }
            }
        }
    }
}
