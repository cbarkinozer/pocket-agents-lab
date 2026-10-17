package com.pocketagentslab

import android.content.Context
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Routes through Clef, then swaps to an optional chat model for prose. Never two loaded models. */
internal class ReflexInferenceEngine(context: Context, private val textEngine: InferenceEngine) : InferenceEngine {
    private val preferences = context.getSharedPreferences("model_defaults", Context.MODE_PRIVATE)
    private val runtime = ReflexRuntime(context.applicationContext)
    private val client = DecisionHttpClient(runtime.baseUrl, timeoutMs = 120_000)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mutex = Mutex()
    private val currentState = MutableStateFlow<InferenceEngine.State>(textEngine.state.value)
    override val state = currentState.asStateFlow()
    @Volatile private var decisionPath: String? = null
    private var systemPrompt = ""
    internal val decisionRuntimeRunning: Boolean get() = runtime.isRunning
    internal val textModelReady: Boolean get() = textEngine.state.value is InferenceEngine.State.ModelReady
    val isDecisionModel: Boolean get() = decisionPath != null
    val hasTextModel: Boolean get() = preferences.getString("chat_model_path", null)?.let { File(it).isFile } == true

    init {
        scope.launch { textEngine.state.collect { if (!isDecisionModel) currentState.value = it } }
    }

    override suspend fun loadModel(pathToModel: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            stopModels()
            val architecture = GgufArchitecture.read(File(pathToModel))
            if (architecture == "clef") {
                if (!hasTextModel) {
                    // Preserve the user's previously selected regular model on upgrade.
                    preferences.getString("model_path", null)?.let { previous ->
                        if (previous != pathToModel && File(previous).isFile) chooseTextModel(File(previous))
                    }
                }
                decisionPath = pathToModel
                currentState.value = InferenceEngine.State.LoadingModel
                try {
                    runtime.start(pathToModel)
                    currentState.value = InferenceEngine.State.ModelReady
                } catch (error: Throwable) {
                    decisionPath = null
                    currentState.value = InferenceEngine.State.Initialized
                    throw error
                }
            } else {
                decisionPath = null
                textEngine.loadModel(pathToModel)
                preferences.edit().putString("chat_model_path", pathToModel).apply()
                currentState.value = textEngine.state.value
            }
        }
    }

    suspend fun chooseTextModel(file: File) = withContext(Dispatchers.IO) {
        val architecture = GgufArchitecture.read(file)
        require(architecture != "clef") { "Reflex-1 is a router. Choose a regular Qwen/chat GGUF for text answers." }
        preferences.edit().putString("chat_model_path", file.absolutePath).apply()
    }

    override suspend fun setSystemPrompt(systemPrompt: String) {
        this.systemPrompt = systemPrompt
        if (!isDecisionModel) textEngine.setSystemPrompt(systemPrompt)
    }

    suspend fun prepareRouting() = mutex.withLock {
        decisionPath?.let { path ->
            withContext(Dispatchers.IO) { unloadText() }
            runtime.start(path)
            currentState.value = InferenceEngine.State.ModelReady
        }
    }

    fun selector(): AgentDecisionSelector? = if (!isDecisionModel) null else AgentDecisionSelector { prompt ->
        mutex.withLock {
            val path = checkNotNull(decisionPath)
            withContext(Dispatchers.IO) { unloadText() }
            runtime.start(path)
            try {
                currentState.value = InferenceEngine.State.ProcessingUserPrompt
                client.select(prompt)
            } finally {
                currentState.value = InferenceEngine.State.ModelReady
            }
        }
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        mutex.withLock {
            if (isDecisionModel) {
                val chatPath = preferences.getString("chat_model_path", null)
                check(chatPath != null && File(chatPath).isFile) {
                    "Reflex-1 selected the route, but a text model is needed for this answer. Load a regular Qwen GGUF once, then reload Reflex-1."
                }
                withContext(Dispatchers.IO) {
                    runtime.stop()
                    if (textEngine.state.value !is InferenceEngine.State.ModelReady) {
                        unloadText()
                        textEngine.loadModel(chatPath)
                        textEngine.setSystemPrompt(systemPrompt)
                    }
                }
            }
            try {
                currentState.value = InferenceEngine.State.Generating
                emitAll(textEngine.sendUserPrompt(message, predictLength))
            } finally {
                // Keep only the chat model resident until the next routing request.
                currentState.value = InferenceEngine.State.ModelReady
            }
        }
    }

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String {
        check(!isDecisionModel) { "Use the routing benchmark for Reflex-1; token-generation benchmarks do not apply." }
        return textEngine.bench(pp, tg, pl, nr)
    }

    private suspend fun unloadText() {
        val s = withTimeout(30_000) { textEngine.state.first {
            it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error
        } }
        if (s !is InferenceEngine.State.Initialized) textEngine.cleanUp()
    }

    private suspend fun stopModels() {
        runtime.stop()
        unloadText()
        decisionPath = null
    }

    override fun cleanUp() {
        runtime.stop()
        decisionPath = null
        if (textEngine.state.value is InferenceEngine.State.ModelReady || textEngine.state.value is InferenceEngine.State.Error) textEngine.cleanUp()
        currentState.value = textEngine.state.value
    }

    /** Activity teardown stops its server without destroying the shared JNI singleton. */
    fun close() {
        runtime.stop()
        scope.cancel()
    }

    override fun destroy() {
        close()
        textEngine.destroy()
    }
}
