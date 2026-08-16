package com.pocketagentslab

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val AGENT_DECISION_TOKENS = 64
internal const val AGENT_ANSWER_TOKENS = 256
internal const val GRAMMAR_SCOPE_PREFIX = "[[POCKET_GRAMMAR_SCOPE]]\n"
internal const val GRAMMAR_LIVE_PREFIX = "[[POCKET_GRAMMAR_LIVE]]\n"
internal const val GRAMMAR_ROUTE_PREFIX = "[[POCKET_GRAMMAR_ROUTE]]\n"
internal const val GRAMMAR_AGENT_ROUTE_PREFIX = "[[POCKET_GRAMMAR_AGENT_ROUTE]]\n"
internal const val CLARIFICATION_MESSAGE =
    "I could not understand what you meant. Please rephrase your request."

internal val READ_ONLY_TOOLS = setOf(
    "get_device_info",
    "get_battery_info",
    "get_storage_info",
    "search_local_files",
)

internal data class AgentDecision(
    val action: String,
    val text: String? = null,
    val toolName: String? = null,
    val workflowName: String? = null,
    val proposedAction: String? = null,
    val schemaRepaired: Boolean = false,
)

internal data class GeneratedText(val text: String, val pieces: Int)

internal data class AgentSelection(
    val decision: AgentDecision,
    val generatedPieces: Int,
    val repairAttempted: Boolean = false,
)

internal data class AgentRunResult(
    val answer: String,
    val route: String,
    val generatedPieces: Int,
    val diagnosis: String? = null,
    val proposedAction: String? = null,
)

internal fun interface AgentGenerator {
    suspend fun generate(prompt: String, maxTokens: Int): GeneratedText
}

internal fun interface ReadOnlyToolExecutor {
    suspend fun execute(name: String, userPrompt: String): String
}

internal data class AgentProgress(val fraction: Float, val message: String)

/** UI-independent two-step agent state machine. */
internal class AgentBackend(
    private val generator: AgentGenerator,
    private val tools: ReadOnlyToolExecutor,
    private val beforeRepair: suspend () -> Unit = {},
    private val onProgress: (AgentProgress) -> Unit = {},
    private val hierarchicalRouting: Boolean = false,
    private val allowDeviceActions: Boolean = false,
) {
    suspend fun select(userPrompt: String): AgentSelection {
        if (hierarchicalRouting) return selectHierarchically(userPrompt)
        onProgress(AgentProgress(0.15f, "Selecting an action with the local model…"))
        val generated = generator.generate(
            (if (allowDeviceActions) GRAMMAR_AGENT_ROUTE_PREFIX else GRAMMAR_ROUTE_PREFIX) +
                buildRoutingPrompt(userPrompt, allowDeviceActions),
            AGENT_DECISION_TOKENS,
        )
        return try {
            AgentSelection(parseAgentDecision(generated.text), generated.pieces)
        } catch (validationError: Throwable) {
            onProgress(AgentProgress(0.22f, "Repairing one invalid routing response…"))
            beforeRepair()
            val repaired = generator.generate(
                buildRoutingRepairPrompt(
                    userPrompt,
                    generated.text,
                    validationError.message.orEmpty(),
                ),
                AGENT_DECISION_TOKENS,
            )
            AgentSelection(
                decision = parseAgentDecision(repaired.text),
                generatedPieces = generated.pieces + repaired.pieces,
                repairAttempted = true,
            )
        }
    }

    private suspend fun selectHierarchically(userPrompt: String): AgentSelection {
        onProgress(AgentProgress(0.12f, "Checking whether live phone data is needed…"))
        val scope = generator.generate(
            GRAMMAR_SCOPE_PREFIX + buildScopePrompt(userPrompt),
            AGENT_DECISION_TOKENS,
        )
        if (parseScopeDecision(scope.text) == "answer") {
            return AgentSelection(
                decision = AgentDecision(action = "answer", text = ""),
                generatedPieces = scope.pieces,
            )
        }
        onProgress(AgentProgress(0.24f, "Selecting the required phone capability…"))
        val route = generator.generate(
            GRAMMAR_LIVE_PREFIX + buildLiveRoutingPrompt(userPrompt),
            AGENT_DECISION_TOKENS,
        )
        return AgentSelection(
            decision = parseAgentDecision(route.text),
            generatedPieces = scope.pieces + route.pieces,
        )
    }

    suspend fun complete(userPrompt: String, selection: AgentSelection): AgentRunResult {
        val decision = selection.decision
        if (decision.action == "answer") {
            val directAnswer = decision.text.orEmpty().trim()
            if (directAnswer.isBlank()) {
                onProgress(AgentProgress(0.70f, "Generating a local answer…"))
                val generated = generator.generate(buildDirectAnswerPrompt(userPrompt), AGENT_ANSWER_TOKENS)
                return AgentRunResult(
                    answer = parseFinalAnswer(generated.text),
                    route = "answer",
                    generatedPieces = selection.generatedPieces + generated.pieces,
                ).also { onProgress(AgentProgress(1.0f, "Complete")) }
            }
            val copiedExample = directAnswer.isBlank() ||
                (isCopiedRoutingExample(directAnswer) && !userPrompt.contains("joke", ignoreCase = true))
            return AgentRunResult(
                answer = if (copiedExample) CLARIFICATION_MESSAGE else directAnswer,
                route = if (copiedExample) "clarify:copied-example" else "answer",
                generatedPieces = selection.generatedPieces,
            ).also { onProgress(AgentProgress(1.0f, "Complete")) }
        }

        if (decision.action == "workflow") {
            return runHealthCheck(userPrompt, selection)
        }

        if (decision.action == "propose") {
            val action = requireNotNull(decision.proposedAction)
            return AgentRunResult(
                answer = when (action) {
                    OPEN_STORAGE_SETTINGS -> "I can open Android Storage Settings. Confirm below before anything happens."
                    OPEN_BATTERY_SETTINGS -> "I can open Android Battery Settings. Confirm below before anything happens."
                    else -> error("Unknown proposed action: $action")
                },
                route = "propose:$action",
                generatedPieces = selection.generatedPieces,
                proposedAction = action,
            ).also { onProgress(AgentProgress(1.0f, "Waiting for your confirmation")) }
        }

        val toolName = requireNotNull(decision.toolName)
        onProgress(AgentProgress(0.45f, "Reading ${toolName.toDisplayName()}…"))
        val toolResult = tools.execute(toolName, userPrompt)
        onProgress(AgentProgress(0.70f, "Generating a local explanation…"))
        val generated = generator.generate(
            buildFinalAnswerPrompt(userPrompt, toolName, toolResult),
            AGENT_ANSWER_TOKENS,
        )
        val modelAnswer = parseFinalAnswer(generated.text)
        val usedFallback = isPlaceholderAnswer(modelAnswer)
        return AgentRunResult(
            answer = if (usedFallback) deterministicToolAnswer(toolName, toolResult) else modelAnswer,
            route = buildString {
                append("tool:$toolName")
                if (decision.schemaRepaired) append(" (normalized)")
                if (usedFallback) append(" (fallback answer)")
            },
            generatedPieces = selection.generatedPieces + generated.pieces,
        ).also { onProgress(AgentProgress(1.0f, "Complete")) }
    }

    suspend fun run(userPrompt: String): AgentRunResult = complete(userPrompt, select(userPrompt))

    private suspend fun runHealthCheck(
        userPrompt: String,
        selection: AgentSelection,
    ): AgentRunResult {
        require(selection.decision.workflowName == PHONE_HEALTH_CHECK)
        onProgress(AgentProgress(0.30f, "Reading device information…"))
        val device = tools.execute("get_device_info", userPrompt)
        onProgress(AgentProgress(0.40f, "Reading battery information…"))
        val battery = tools.execute("get_battery_info", userPrompt)
        onProgress(AgentProgress(0.50f, "Reading storage information…"))
        val storage = tools.execute("get_storage_info", userPrompt)
        onProgress(AgentProgress(0.60f, "Evaluating health thresholds in Kotlin…"))
        val diagnosis = evaluatePhoneHealth(device, battery, storage)
        onProgress(AgentProgress(0.72f, "Generating suggestions with the local model…"))
        val generated = generator.generate(
            buildHealthExplanationPrompt(userPrompt, diagnosis),
            AGENT_ANSWER_TOKENS,
        )
        val modelAnswer = parseFinalAnswer(generated.text)
        val usedFallback = isPlaceholderAnswer(modelAnswer)
        val trustedSummary = deterministicHealthAnswer(diagnosis)
        return AgentRunResult(
            answer = if (usedFallback) {
                trustedSummary
            } else {
                "$trustedSummary\n\nLocal SLM suggestions: $modelAnswer"
            },
            route = "workflow:$PHONE_HEALTH_CHECK" + if (usedFallback) " (fallback answer)" else "",
            generatedPieces = selection.generatedPieces + generated.pieces,
            diagnosis = diagnosis.toString(),
        ).also { onProgress(AgentProgress(1.0f, "Health check complete")) }
    }
}

internal const val PHONE_HEALTH_CHECK = "phone_health_check"
internal const val STORAGE_WARNING_FREE_PERCENT = 10.0
internal const val BATTERY_WARNING_TEMPERATURE_C = 40.0

/** Routing stays strict JSON; final prose may be JSON-wrapped or plain text. */
internal fun parseFinalAnswer(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return CLARIFICATION_MESSAGE
    if (trimmed.startsWith("{") || trimmed.startsWith("```") || trimmed.startsWith("[")) {
        val decision = parseAgentDecision(trimmed)
        require(decision.action == "answer") { "Final response must use action=answer" }
        return decision.text.orEmpty().trim().ifBlank { CLARIFICATION_MESSAGE }
    }
    return trimmed
}

internal fun parseAgentDecision(raw: String): AgentDecision {
    val (trimmed, fenceNormalized) = unwrapJsonFence(raw)
    if (trimmed.startsWith("[")) {
        return parseNativeToolCalls(trimmed)
    }
    require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
        "Model did not return a bare JSON object"
    }
    val json = JSONObject(trimmed)
    return when (val action = json.optString("action")) {
        "answer" -> {
            require(json.length() == 2 && json.has("text")) { "Invalid answer schema" }
            AgentDecision(
                action = action,
                text = json.getString("text"),
                schemaRepaired = fenceNormalized,
            )
        }
        "tool" -> {
            require(json.length() == 3 && json.has("name") && json.has("args")) {
                "Invalid tool schema"
            }
            val name = json.getString("name")
            require(name in READ_ONLY_TOOLS) { "Unknown tool: $name" }
            require(json.getJSONObject("args").length() == 0) { "Tools accept no arguments" }
            AgentDecision(action = action, toolName = name, schemaRepaired = fenceNormalized)
        }
        "workflow" -> {
            require(json.length() == 3 && json.has("name") && json.has("args")) {
                "Invalid workflow schema"
            }
            val name = json.getString("name")
            require(name == PHONE_HEALTH_CHECK) { "Unknown workflow: $name" }
            require(json.getJSONObject("args").length() == 0) { "Workflow accepts no arguments" }
            AgentDecision(action = action, workflowName = name, schemaRepaired = fenceNormalized)
        }
        "propose" -> {
            require(json.length() == 3 && json.has("name") && json.has("args")) {
                "Invalid proposed-action schema"
            }
            val name = json.getString("name")
            require(name in APPROVED_DEVICE_ACTIONS) { "Unknown proposed action: $name" }
            require(json.getJSONObject("args").length() == 0) { "Device actions accept no arguments" }
            AgentDecision(action = action, proposedAction = name, schemaRepaired = fenceNormalized)
        }
        in READ_ONLY_TOOLS -> {
            require(json.length() == 2 && json.has("args")) { "Invalid shorthand tool schema" }
            require(json.getJSONObject("args").length() == 0) { "Tools accept no arguments" }
            AgentDecision(
                action = "tool",
                toolName = action,
                schemaRepaired = true,
            )
        }
        PHONE_HEALTH_CHECK -> {
            require(json.length() == 2 && json.has("args")) { "Invalid shorthand workflow schema" }
            require(json.getJSONObject("args").length() == 0) { "Workflow accepts no arguments" }
            AgentDecision(
                action = "workflow",
                workflowName = PHONE_HEALTH_CHECK,
                schemaRepaired = true,
            )
        }
        else -> error("Unknown action: $action")
    }
}

/** Normalize xLAM-style native function calls without accepting arbitrary arrays. */
internal fun parseNativeToolCalls(raw: String): AgentDecision {
    val calls = JSONArray(raw)
    require(calls.length() > 0) { "Native tool-call array is empty" }
    val names = buildList {
        repeat(calls.length()) { index ->
            val call = calls.getJSONObject(index)
            require(call.length() == 2 && call.has("name") && call.has("arguments")) {
                "Invalid native tool-call schema"
            }
            require(call.getJSONObject("arguments").length() == 0) {
                "Native tools accept no arguments"
            }
            add(call.getString("name"))
        }
    }
    require(names.distinct().size == names.size) { "Duplicate native tool calls" }
    return when {
        names.size == 1 && names.single() in READ_ONLY_TOOLS -> AgentDecision(
            action = "tool",
            toolName = names.single(),
            schemaRepaired = true,
        )
        names.size == 1 && names.single() == PHONE_HEALTH_CHECK -> AgentDecision(
            action = "workflow",
            workflowName = PHONE_HEALTH_CHECK,
            schemaRepaired = true,
        )
        names.size >= 2 && names.all { it in READ_ONLY_TOOLS } -> AgentDecision(
            action = "workflow",
            workflowName = PHONE_HEALTH_CHECK,
            schemaRepaired = true,
        )
        else -> error("Unsupported native tool-call combination: ${names.joinToString()}")
    }
}

/**
 * Accept the common ```json ... ``` wrapper without relying on a regular expression.
 * Android's ICU regex implementation rejects some patterns accepted by the desktop JVM,
 * which previously made AgentBackendKt fail during class initialization on the phone.
 */
internal fun unwrapJsonFence(raw: String): Pair<String, Boolean> {
    val trimmed = raw.trim()
    val openingLength = when {
        trimmed.startsWith("```json", ignoreCase = true) -> 7
        trimmed.startsWith("```") -> 3
        else -> return trimmed to false
    }
    if (!trimmed.endsWith("```") || trimmed.length < openingLength + 3) {
        return trimmed to false
    }
    val inner = trimmed.substring(openingLength, trimmed.length - 3).trim()
    if (!inner.startsWith("{") || !inner.endsWith("}")) {
        return trimmed to false
    }
    return inner to true
}

internal const val OPEN_STORAGE_SETTINGS = "open_storage_settings"
internal const val OPEN_BATTERY_SETTINGS = "open_battery_settings"
internal val APPROVED_DEVICE_ACTIONS = setOf(OPEN_STORAGE_SETTINGS, OPEN_BATTERY_SETTINGS)

internal fun buildRoutingPrompt(userPrompt: String, allowDeviceActions: Boolean = false): String = """Select exactly one route. Native grammar constructs the JSON, so choose by meaning:
Live phone fact: {"action":"tool","name":"TOOL","args":{}}
TOOL is exactly get_device_info, get_battery_info, get_storage_info, or search_local_files.
Device info covers model, manufacturer, Android, ABI, and RAM. Storage info covers disk space and room for files/models.
Battery info covers live level, charging state, temperature, heat, and whether cooling is needed.
Local file search finds filenames or text in the folder the user previously authorized. Its query is the original request.
Overall phone health: {"action":"workflow","name":"phone_health_check","args":{}}
Two or more live categories, overall condition, or AI-workload readiness use phone_health_check.
No live phone data needed: {"action":"answer","text":""}
Writing, jokes, arithmetic, definitions, colors, sequences, and general knowledge need no tool.
Unclear: {"action":"answer","text":"$CLARIFICATION_MESSAGE"}
Examples:
Battery level -> {"action":"tool","name":"get_battery_info","args":{}}
Free space -> {"action":"tool","name":"get_storage_info","args":{}}
Find a file or phrase in local files -> {"action":"tool","name":"search_local_files","args":{}}
Android version -> {"action":"tool","name":"get_device_info","args":{}}
Physical RAM or manufacturer -> {"action":"tool","name":"get_device_info","args":{}}
Room for another model -> {"action":"tool","name":"get_storage_info","args":{}}
Check everything -> {"action":"workflow","name":"phone_health_check","args":{}}
${if (allowDeviceActions) """Explicit request to open Storage Settings -> {"action":"propose","name":"open_storage_settings","args":{}}
Explicit request to open Battery Settings -> {"action":"propose","name":"open_battery_settings","args":{}}
Only propose an action when the user asks to open or navigate to that settings page. Reading facts still uses a read-only tool. A proposal never executes without confirmation.""" else ""}
Request: $userPrompt
JSON:"""

internal fun buildScopePrompt(userPrompt: String): String = """Choose whether the request needs current facts from this phone.
ANSWER: writing, jokes, arithmetic, definitions, explanations, colors, sequences, or general knowledge.
LIVE_DEVICE: current device/model/Android/RAM, storage capacity, battery/charging/heat, overall phone condition, or AI-workload readiness.
Request: $userPrompt
Decision:"""

internal fun buildLiveRoutingPrompt(userPrompt: String): String = """The request needs live phone data. Choose exactly one route:
DEVICE: model, manufacturer, Android, SDK, ABI, or RAM.
BATTERY: level, charging state, temperature, heat, or cooling.
STORAGE: free/used disk space or whether a file/model fits.
HEALTH: overall wellness/readiness, recommendations, or two or more live categories.
Request: $userPrompt
Decision:"""

internal fun parseScopeDecision(raw: String): String {
    val json = JSONObject(raw.trim())
    require(json.length() == 1 && json.has("scope")) { "Invalid scope schema" }
    return json.getString("scope").also {
        require(it == "answer" || it == "live_device") { "Unknown scope: $it" }
    }
}

internal fun buildDirectAnswerPrompt(userPrompt: String): String =
    """Return exactly one compact JSON object: {"action":"answer","text":"your concise answer"}
Answer this request without using device tools: $userPrompt
JSON:"""

internal fun evaluatePhoneHealth(
    rawDevice: String,
    rawBattery: String,
    rawStorage: String,
): JSONObject {
    val device = JSONObject(rawDevice)
    val battery = JSONObject(rawBattery)
    val storage = JSONObject(rawStorage)
    val totalBytes = storage.getLong("totalBytes")
    val availableBytes = storage.getLong("availableBytes")
    require(totalBytes > 0) { "Total storage must be positive" }
    val freePercent = availableBytes * 100.0 / totalBytes
    val temperatureC = battery.getDouble("temperatureC")
    val warnings = org.json.JSONArray()
    val suggestions = org.json.JSONArray()
    if (freePercent < STORAGE_WARNING_FREE_PERCENT) {
        warnings.put("low_storage")
        suggestions.put("Free storage until at least 10% is available.")
    }
    if (temperatureC > BATTERY_WARNING_TEMPERATURE_C) {
        warnings.put("hot_battery")
        suggestions.put("Pause heavy workloads, remove charging if safe, and let the phone cool.")
    }
    if (warnings.length() == 0) {
        suggestions.put("Storage and battery temperature are within the configured thresholds.")
    }
    return JSONObject()
        .put("status", if (warnings.length() == 0) "okay" else "warning")
        .put("warnings", warnings)
        .put("suggestions", suggestions)
        .put("storageFreePercent", freePercent)
        .put("storageThresholdPercent", STORAGE_WARNING_FREE_PERCENT)
        .put("batteryTemperatureC", temperatureC)
        .put("batteryTemperatureThresholdC", BATTERY_WARNING_TEMPERATURE_C)
        .put("batteryLevelPercent", battery.opt("levelPercent"))
        .put("isCharging", battery.getBoolean("isCharging"))
        .put("deviceModel", device.getString("model"))
        .put("androidVersion", device.getString("androidVersion"))
        .put("cpuAbi", device.getString("cpuAbi"))
}

internal fun buildRoutingRepairPrompt(
    userPrompt: String,
    invalidOutput: String,
    validationError: String,
): String =
    """No thinking, explanation, or markdown. Return one corrected compact JSON object only.
Validator error: $validationError
Allowed actions and schemas:
{"action":"answer","text":"..."}
{"action":"tool","name":"get_device_info","args":{}}
{"action":"tool","name":"get_battery_info","args":{}}
{"action":"tool","name":"get_storage_info","args":{}}
{"action":"workflow","name":"phone_health_check","args":{}}
Original request: $userPrompt
Invalid response excerpt: ${invalidOutput.take(256)}
Corrected JSON:"""

internal fun buildHealthExplanationPrompt(userPrompt: String, diagnosis: JSONObject): String =
    """Return exactly one compact JSON object with action "answer" and a concise text explanation.
The diagnosis below was calculated by trusted Android code. Do not change its status, thresholds, or facts.
Mention each warning and give its listed suggestion. If status is okay, say the checked values are okay.
Original request: $userPrompt
Trusted diagnosis: $diagnosis
Output JSON:"""

private fun deterministicHealthAnswer(diagnosis: JSONObject): String {
    val free = formatOne(diagnosis.getDouble("storageFreePercent"))
    val temperature = formatOne(diagnosis.getDouble("batteryTemperatureC"))
    val warnings = diagnosis.getJSONArray("warnings")
    if (warnings.length() == 0) {
        return "Phone health looks okay: $free% storage is free and battery temperature is $temperature°C."
    }
    val findings = mutableListOf<String>()
    for (index in 0 until warnings.length()) {
        when (warnings.getString(index)) {
            "low_storage" -> findings += "storage is low at $free% free; free space until at least 10% is available"
            "hot_battery" -> findings += "battery temperature is high at $temperature°C; pause heavy work and let it cool"
        }
    }
    return "Phone health warning: ${findings.joinToString(". ")}."
}

internal fun buildFinalAnswerPrompt(
    userPrompt: String,
    toolName: String,
    toolResult: String,
): String = """Return exactly one compact JSON object and nothing else.
Use action "answer" and put your concise interpretation of the actual tool result in "text".
Example structure: {"action":"answer","text":"You have 12 GB available out of 64 GB."}
Do not copy the example sentence. Calculate the answer from the tool result below.
Do not use action=tool, action=explain, or any other action. The tool has already run.
Original user question: $userPrompt
Tool used: $toolName
Tool result: $toolResult
Output:"""

private fun isPlaceholderAnswer(answer: String): Boolean =
    answer.isBlank() ||
        answer.equals("YOUR NATURAL-LANGUAGE ANSWER", ignoreCase = true) ||
        answer.equals("YOUR ANSWER", ignoreCase = true)

private fun isCopiedRoutingExample(answer: String): Boolean {
    val normalized = answer.lowercase(Locale.US)
    return normalized.contains("why did the byte cross the bus") ||
        normalized.contains("why did the byte cross the road")
}

private fun deterministicToolAnswer(toolName: String, rawResult: String): String {
    val result = JSONObject(rawResult)
    return when (toolName) {
        "get_storage_info" -> {
            val available = result.getLong("availableBytes").toGib()
            val total = result.getLong("totalBytes").toGib()
            "You have $available GB available out of $total GB of internal storage."
        }
        "get_battery_info" -> {
            val level = result.optDouble("levelPercent", Double.NaN)
            val temperature = result.getDouble("temperatureC")
            val levelText = if (level.isNaN()) "an unknown charge level" else "${formatOne(level)}% charge"
            "The battery is at $levelText and ${formatOne(temperature)}°C."
        }
        "get_device_info" ->
            "This is a ${result.getString("manufacturer")} ${result.getString("model")}, " +
                "running Android ${result.getString("androidVersion")} on " +
                "${result.getString("cpuAbi")}."
        "search_local_files" -> {
            val error = result.optString("error")
            if (error.isNotBlank()) error else {
                val matches = result.optJSONArray("matches") ?: JSONArray()
                if (matches.length() == 0) "I found no matching local files." else
                    "I found ${matches.length()} local file match(es). Review them below before opening one."
            }
        }
        else -> error("Unknown tool: $toolName")
    }
}

private fun Long.toGib(): String = formatOne(this / 1024.0 / 1024.0 / 1024.0)

private fun formatOne(value: Double): String = "%.1f".format(Locale.US, value)

private fun String.toDisplayName(): String = removePrefix("get_")
    .removeSuffix("_info")
    .replace('_', ' ')
