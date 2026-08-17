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
internal const val GRAMMAR_NOTE_ROUTE_PREFIX = "[[POCKET_GRAMMAR_NOTE_ROUTE]]\n"
internal const val CLARIFICATION_MESSAGE =
    "I could not understand what you meant. Please rephrase your request."

internal val READ_ONLY_TOOLS = setOf(
    "get_device_info",
    "get_battery_info",
    "get_storage_info",
    "search_local_files",
    "search_notes",
    "save_note",
    "get_media_info",
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
    val proposedAction: DeviceActionProposal? = null,
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
    private val actionResolver: (String, String) -> DeviceActionProposal? = { action, request ->
        buildDeviceActionProposal(action, request)
    },
) {
    suspend fun select(userPrompt: String): AgentSelection {
        capabilityHelpAnswer(userPrompt)?.let { answer ->
            onProgress(AgentProgress(1.0f, "Capabilities ready"))
            return AgentSelection(AgentDecision(action = "answer", text = answer), generatedPieces = 0)
        }
        if (hierarchicalRouting) return selectHierarchically(userPrompt)
        onProgress(AgentProgress(0.15f, "Selecting an action with the local model…"))
        val generated = generator.generate(
            (if (allowDeviceActions) GRAMMAR_AGENT_ROUTE_PREFIX else GRAMMAR_ROUTE_PREFIX) +
                buildRoutingPrompt(userPrompt, allowDeviceActions),
            AGENT_DECISION_TOKENS,
        )
        val initial = try {
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
        val mediaCommandCount = listOf("stop", "pause", "continue", "resume", "next", "forward", "previous", "backward")
            .count(userPrompt.lowercase(Locale.ROOT)::contains)
        val normalized = if (mediaCommandCount >= 2) {
            initial.copy(
                decision = AgentDecision(
                    action = "answer",
                    text = "Tell me one media action at a time: play/pause, next, or previous.",
                ),
            )
        } else if (
            initial.decision.proposedAction == REVIEW_BACKGROUND_APPS &&
            listOf("remove", "clean", "optimize", "lag", "slow").any(userPrompt.lowercase(Locale.ROOT)::contains)
        ) {
            initial.copy(decision = AgentDecision(action = "workflow", workflowName = PHONE_OPTIMIZATION_REPORT))
        } else {
            initial
        }
        val conservative = if (isDecisionRelevant(normalized.decision, userPrompt)) normalized else {
            AgentSelection(
                decision = AgentDecision(action = "answer", text = ""),
                generatedPieces = normalized.generatedPieces,
                repairAttempted = normalized.repairAttempted,
            )
        }
        return refineNoteRoute(userPrompt, conservative)
    }

    private suspend fun refineNoteRoute(userPrompt: String, initial: AgentSelection): AgentSelection {
        if (initial.decision.toolName !in setOf("search_notes", "save_note")) return initial
        onProgress(AgentProgress(0.28f, "Distinguishing note save from recall…"))
        val refined = generator.generate(
            GRAMMAR_NOTE_ROUTE_PREFIX + buildNoteRoutingPrompt(userPrompt),
            AGENT_DECISION_TOKENS,
        )
        return AgentSelection(
            decision = parseAgentDecision(refined.text),
            generatedPieces = initial.generatedPieces + refined.pieces,
            repairAttempted = initial.repairAttempted,
        )
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
                trustedDirectAnswer(userPrompt)?.let { trusted ->
                    return AgentRunResult(
                        answer = trusted,
                        route = "answer:product-guardrail",
                        generatedPieces = selection.generatedPieces,
                    ).also { onProgress(AgentProgress(1.0f, "Complete")) }
                }
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
            return if (decision.workflowName == PHONE_OPTIMIZATION_REPORT) {
                runPhoneOptimization(selection)
            } else {
                runHealthCheck(userPrompt, selection)
            }
        }

        if (decision.action == "propose") {
            val action = requireNotNull(decision.proposedAction)
            val proposal = actionResolver(action, userPrompt)
                ?: return AgentRunResult(
                    answer = when (action) {
                        SET_TIMER -> "Tell me a timer duration, for example: set a timer for 15 minutes."
                        SET_ALARM -> "Tell me an alarm time, for example: set an alarm for 9 PM."
                        CREATE_CALENDAR_EVENT -> "Tell me the event title, day, and time, for example: add dentist tomorrow at 3 PM."
                        LAUNCH_APP -> "Tell me which installed app to open, for example: open Spotify."
                        else -> "I could not validate that action. Please rephrase it."
                    },
                    route = "clarify:$action",
                    generatedPieces = selection.generatedPieces,
                ).also { onProgress(AgentProgress(1.0f, "Waiting for clearer action details")) }
            return AgentRunResult(
                answer = when (action) {
                    OPEN_STORAGE_SETTINGS -> "I can open Android Storage Settings. Confirm below before anything happens."
                    OPEN_BATTERY_SETTINGS -> "I can open Android Battery Settings. Confirm below before anything happens."
                    OPEN_CAMERA -> "I can open Camera. Confirm below before anything happens."
                    OPEN_WALLPAPER_SETTINGS -> "I can open Android Wallpaper Settings. Confirm below before anything happens."
                    REVIEW_BACKGROUND_APPS -> "Android does not allow me to safely force-close every other app. I can open app management so you can review or stop unnecessary apps. Confirm below."
                    SEARCH_SPOTIFY, SEARCH_YOUTUBE -> "I understood: ${deviceActionLabel(proposal)}. Confirm to open the app's search results. Playback remains under your control."
                    DRAFT_TELEGRAM_MESSAGE -> "I prepared: ${deviceActionLabel(proposal)}. Confirm to open Telegram's share screen. Select the intended chat, verify the recipient, and press Send yourself."
                    MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, OPEN_MEDIA_ACCESS -> "I understood: ${deviceActionLabel(proposal)}. Confirm before Android performs it."
                    SET_TIMER, SET_ALARM, CREATE_CALENDAR_EVENT, LAUNCH_APP -> "I understood: ${deviceActionLabel(proposal)}. Confirm below before anything happens."
                    else -> error("Unknown proposed action: $action")
                },
                route = "propose:$action",
                generatedPieces = selection.generatedPieces,
                proposedAction = proposal,
            ).also { onProgress(AgentProgress(1.0f, "Waiting for your confirmation")) }
        }

        val toolName = requireNotNull(decision.toolName)
        onProgress(AgentProgress(0.45f, "Reading ${toolName.toDisplayName()}…"))
        val toolResult = tools.execute(toolName, userPrompt)
        if (toolName == "search_notes" || toolName == "get_battery_info" || toolName == "get_media_info") {
            return AgentRunResult(
                answer = deterministicToolAnswer(toolName, toolResult),
                route = buildString {
                    append("tool:$toolName (deterministic result)")
                    if (decision.schemaRepaired) append(" (normalized)")
                },
                generatedPieces = selection.generatedPieces,
            ).also { onProgress(AgentProgress(1.0f, "Note recall complete")) }
        }
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

    private suspend fun runPhoneOptimization(selection: AgentSelection): AgentRunResult {
        onProgress(AgentProgress(0.35f, "Measuring memory pressure…"))
        val memory = JSONObject(tools.execute("get_memory_info", ""))
        onProgress(AgentProgress(0.55f, "Checking storage and battery…"))
        val storage = JSONObject(tools.execute("get_storage_info", ""))
        val battery = JSONObject(tools.execute("get_battery_info", ""))
        val totalRam = memory.getLong("totalBytes")
        val availableRam = memory.getLong("availableBytes")
        val freeRamPercent = availableRam * 100.0 / totalRam
        val freeStoragePercent = storage.getLong("availableBytes") * 100.0 / storage.getLong("totalBytes")
        val temperature = battery.getDouble("temperatureC")
        val findings = buildList {
            add("Available RAM: ${formatOne(availableRam / 1024.0 / 1024.0 / 1024.0)} GB (${formatOne(freeRamPercent)}%).")
            add("Free storage: ${formatOne(freeStoragePercent)}%.")
            add("Battery temperature: ${formatOne(temperature)}°C.")
            if (memory.optBoolean("lowMemory")) add("Android currently reports low-memory pressure.")
            if (freeStoragePercent < 10.0) add("Storage is below the 10% safety target.")
            if (temperature > 40.0) add("The phone is warm enough that heavy work should pause.")
        }
        val suggestion = if (memory.optBoolean("lowMemory") || freeStoragePercent < 10.0) {
            "Review unused apps and storage. I can open app management after a separate confirmed request."
        } else {
            "No critical pressure is visible. Android normally manages cached background apps itself; bulk force-closing them may increase battery use when they restart."
        }
        return AgentRunResult(
            answer = "Phone optimization report\n${findings.joinToString("\n")}\n$suggestion",
            route = "workflow:$PHONE_OPTIMIZATION_REPORT",
            generatedPieces = selection.generatedPieces,
        ).also { onProgress(AgentProgress(1.0f, "Optimization report complete")) }
    }
}

internal const val PHONE_HEALTH_CHECK = "phone_health_check"
internal const val PHONE_OPTIMIZATION_REPORT = "phone_optimization_report"
internal const val STORAGE_WARNING_FREE_PERCENT = 10.0
internal const val BATTERY_WARNING_TEMPERATURE_C = 40.0

/** Routing stays strict JSON; final prose may be JSON-wrapped or plain text. */
internal fun parseFinalAnswer(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return CLARIFICATION_MESSAGE
    if (trimmed.startsWith("{") || trimmed.startsWith("```") || trimmed.startsWith("[")) {
        val decision = runCatching { parseAgentDecision(trimmed) }.getOrElse { validationError ->
            val syntacticallyValidJson = runCatching { JSONObject(unwrapJsonFence(trimmed).first) }.isSuccess
            if (syntacticallyValidJson) throw validationError
            val partialText = Regex("\\\"text\\\"\\s*:\\s*\\\"([^\\\"]*)").find(trimmed)?.groupValues?.get(1)
            return partialText?.ifBlank { CLARIFICATION_MESSAGE } ?: CLARIFICATION_MESSAGE
        }
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
            require(name == PHONE_HEALTH_CHECK || name == PHONE_OPTIMIZATION_REPORT) { "Unknown workflow: $name" }
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
        names.size == 1 && names.single() == PHONE_OPTIMIZATION_REPORT -> AgentDecision(
            action = "workflow",
            workflowName = PHONE_OPTIMIZATION_REPORT,
            schemaRepaired = true,
        )
        names.size == 1 && names.single() in APPROVED_DEVICE_ACTIONS -> AgentDecision(
            action = "propose",
            proposedAction = names.single(),
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
internal const val OPEN_CAMERA = "open_camera"
internal const val OPEN_WALLPAPER_SETTINGS = "open_wallpaper_settings"
internal const val REVIEW_BACKGROUND_APPS = "review_background_apps"
internal const val LAUNCH_APP = "launch_app"
internal val APPROVED_DEVICE_ACTIONS = setOf(
    OPEN_STORAGE_SETTINGS,
    OPEN_BATTERY_SETTINGS,
    SET_TIMER,
    SET_ALARM,
    CREATE_CALENDAR_EVENT,
    OPEN_CAMERA,
    OPEN_WALLPAPER_SETTINGS,
    REVIEW_BACKGROUND_APPS,
    LAUNCH_APP,
    SEARCH_SPOTIFY,
    SEARCH_YOUTUBE,
    DRAFT_TELEGRAM_MESSAGE,
    MEDIA_PLAY_PAUSE,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    OPEN_MEDIA_ACCESS,
)

internal const val CAPABILITY_HELP = """I can work locally with:
• device, battery, storage, and phone-health information
• private notes that survive app restarts
• keyword search in an authorized local folder
• alarms, timers, and calendar-event drafts
• opening Camera, Wallpaper Settings, Storage Settings, Battery Settings, and app-management screens
• Spotify/YouTube searches, Telegram drafts, and media information/control after you grant access

Actions require confirmation. I cannot silently send messages, close arbitrary apps, or modify system settings."""

internal fun capabilityHelpAnswer(request: String): String? {
    val normalized = request.lowercase(Locale.ROOT)
    return CAPABILITY_HELP.takeIf {
        normalized.contains("what can you do") ||
            normalized.contains("how can you help") ||
            normalized.contains("help me with my phone") ||
            (normalized.contains("don't know") && normalized.contains("phone")) ||
            (normalized.contains("do not know") && normalized.contains("phone"))
    }
}

internal fun isDecisionRelevant(decision: AgentDecision, request: String): Boolean {
    val text = request.lowercase(Locale.ROOT)
    val relevant = when (decision.toolName ?: decision.workflowName ?: decision.proposedAction) {
        "get_device_info" -> listOf("android", "phone", "device", "model", "manufacturer", "ram", "abi", "hardware").any(text::contains)
        "get_battery_info" -> listOf("battery", "charge", "charging", "power", "temperature", "hot", "cool").any(text::contains)
        "get_storage_info" -> listOf("storage", "space", "disk", "room", "fit", "capacity").any(text::contains)
        "get_media_info" -> listOf("playing", "song", "track", "media", "spotify", "youtube music").any(text::contains)
        "search_local_files" -> listOf("file", "document", "folder", "pdf").any(text::contains)
        "search_notes", "save_note" -> listOf(
            "note", "remember", "recall", "forget", "told you", "save this", "keep this", "what was", "what did i tell",
        ).any(text::contains)
        PHONE_HEALTH_CHECK -> listOf("health", "condition", "readiness", "check everything").any(text::contains)
        PHONE_OPTIMIZATION_REPORT -> listOf("optimize", "optimization", "lag", "slow", "background", "process").any(text::contains)
        OPEN_STORAGE_SETTINGS -> text.contains("storage") && listOf("open", "show", "settings", "take me").any(text::contains)
        OPEN_BATTERY_SETTINGS -> text.contains("battery") && listOf("open", "show", "settings", "take me").any(text::contains)
        OPEN_CAMERA -> text.contains("camera") && listOf("open", "launch", "start").any(text::contains)
        OPEN_WALLPAPER_SETTINGS -> text.contains("wallpaper") && listOf("open", "show", "settings", "take me").any(text::contains)
        REVIEW_BACKGROUND_APPS -> listOf("open", "show", "manage", "review").any(text::contains) &&
            listOf("background", "process", "running apps", "apps").any(text::contains)
        LAUNCH_APP -> listOf("open", "launch", "start").any(text::contains) &&
            listOf("stop", "continue", "forward", "backward", "seek", "control playback").none(text::contains)
        SEARCH_SPOTIFY -> text.contains("spotify") && listOf("play", "open", "find", "search").any(text::contains)
        SEARCH_YOUTUBE -> text.contains("youtube") && listOf("play", "open", "find", "search").any(text::contains)
        DRAFT_TELEGRAM_MESSAGE -> text.contains("telegram") && listOf("send", "text", "message", "say", "write").any(text::contains)
        MEDIA_PLAY_PAUSE -> listOf("pause", "resume", "continue", "play").any(text::contains)
        MEDIA_NEXT -> listOf("next", "skip", "forward").any(text::contains)
        MEDIA_PREVIOUS -> listOf("previous", "back", "backward").any(text::contains)
        OPEN_MEDIA_ACCESS -> text.contains("media") && text.contains("access")
        SET_TIMER -> text.contains("timer")
        SET_ALARM -> text.contains("alarm") || text.contains("wake me")
        CREATE_CALENDAR_EVENT -> listOf("calendar", "event", "appointment", "meeting", "schedule").any(text::contains)
        else -> true
    }
    return relevant
}

internal fun trustedDirectAnswer(request: String): String? {
    val text = request.lowercase(Locale.ROOT)
    return when {
        text.contains("wallpaper") ->
            "Touch and hold an empty area of the Home screen, choose Wallpaper and style, select an image, then choose where to apply it. You can also ask me to open Wallpaper Settings."
        text.contains("collage") && (text.contains("photo") || text.contains("picture")) ->
            "I cannot create photo collages yet. That capability will require you to select photos explicitly; I will not access the gallery without permission."
        text.contains("whatsapp") && listOf("text", "message", "send").any(text::contains) ->
            "I cannot safely send WhatsApp messages yet. A future version can prepare a message for your review, but it will never press Send without you."
        text.contains("chatgpt") && listOf("use", "result", "better").any(text::contains) ->
            "I can open the ChatGPT app if it is installed, but Android does not give me a reliable way to operate it and retrieve its answer. Pocket Agents currently keeps inference local."
        else -> null
    }
}

internal fun buildRoutingPrompt(userPrompt: String, allowDeviceActions: Boolean = false): String = """Select exactly one route. Native grammar constructs the JSON, so choose by meaning:
Live phone fact: {"action":"tool","name":"TOOL","args":{}}
TOOL is exactly get_device_info, get_battery_info, get_storage_info, get_media_info, search_local_files, search_notes, or save_note.
Device info covers model, manufacturer, Android, ABI, and RAM. Storage info covers disk space and room for files/models.
Battery info covers live level, charging state, temperature, heat, and whether cooling is needed.
Media info reports the active song/video metadata when Notification Access is enabled.
Local file search finds filenames or text in the folder the user previously authorized. Its query is the original request.
Note search recalls something the user previously asked the app to remember. Save note is for any explicit request to retain information for later. Kotlin uses the original request as the query or content.
Overall phone health: {"action":"workflow","name":"phone_health_check","args":{}}
Phone optimization/lag diagnosis: {"action":"workflow","name":"phone_optimization_report","args":{}}
Two or more live categories, overall condition, or AI-workload readiness use phone_health_check.
No live phone data needed: {"action":"answer","text":""}
Writing, jokes, arithmetic, definitions, colors, sequences, and general knowledge need no tool.
Unclear: {"action":"answer","text":"$CLARIFICATION_MESSAGE"}
Examples:
Battery level -> {"action":"tool","name":"get_battery_info","args":{}}
What song is playing -> {"action":"tool","name":"get_media_info","args":{}}
Free space -> {"action":"tool","name":"get_storage_info","args":{}}
Find a file or phrase in local files -> {"action":"tool","name":"search_local_files","args":{}}
Recall something previously saved -> {"action":"tool","name":"search_notes","args":{}}
Remember or save something to notes -> {"action":"tool","name":"save_note","args":{}}
Don't forget the experiment number -> {"action":"tool","name":"save_note","args":{}}
Keep this for later -> {"action":"tool","name":"save_note","args":{}}
Make a note that the meeting is Monday -> {"action":"tool","name":"save_note","args":{}}
What was the experiment number? -> {"action":"tool","name":"search_notes","args":{}}
Android version -> {"action":"tool","name":"get_device_info","args":{}}
Physical RAM or manufacturer -> {"action":"tool","name":"get_device_info","args":{}}
Room for another model -> {"action":"tool","name":"get_storage_info","args":{}}
Check everything -> {"action":"workflow","name":"phone_health_check","args":{}}
Why is my phone slow / optimize it -> {"action":"workflow","name":"phone_optimization_report","args":{}}
${if (allowDeviceActions) """Explicit request to open Storage Settings -> {"action":"propose","name":"open_storage_settings","args":{}}
Explicit request to open Battery Settings -> {"action":"propose","name":"open_battery_settings","args":{}}
Explicit request to open Camera -> {"action":"propose","name":"open_camera","args":{}}
Explicit request to open Wallpaper Settings -> {"action":"propose","name":"open_wallpaper_settings","args":{}}
Explicit request to open/manage/review the apps screen -> {"action":"propose","name":"review_background_apps","args":{}}
Explicit request to open an installed app -> {"action":"propose","name":"launch_app","args":{}}
Find/play a named song on Spotify -> {"action":"propose","name":"search_spotify","args":{}}
Find/open a named video on YouTube -> {"action":"propose","name":"search_youtube","args":{}}
Prepare a Telegram message -> {"action":"propose","name":"draft_telegram_message","args":{}}
Pause/resume active media -> {"action":"propose","name":"media_play_pause","args":{}}
Next active media item -> {"action":"propose","name":"media_next","args":{}}
Previous active media item -> {"action":"propose","name":"media_previous","args":{}}
Open media/notification access settings -> {"action":"propose","name":"open_media_access","args":{}}
Set/count down a duration -> {"action":"propose","name":"set_timer","args":{}}
Wake/remind at a clock time -> {"action":"propose","name":"set_alarm","args":{}}
Add/schedule an event or appointment -> {"action":"propose","name":"create_calendar_event","args":{}}
Advice such as how to change wallpaper is an answer, not an action. Unsupported requests are answers or clarifications, never the nearest unrelated tool. A proposal never executes without confirmation.""" else ""}
Request: $userPrompt
JSON:"""

internal fun buildNoteRoutingPrompt(userPrompt: String): String = """Choose exactly one note operation.
SAVE_NOTE: the user provides information now and asks the app to retain, remember, keep, record, or not forget it for later.
SEARCH_NOTES: the user asks to retrieve, recall, find, or tell them information saved earlier.
Contrast:
"Don't forget the experiment number is 842" -> SAVE_NOTE
"What was the experiment number?" -> SEARCH_NOTES
"Keep this for later" -> SAVE_NOTE
"What did I tell you before?" -> SEARCH_NOTES
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
Answer this request without using device tools. Do not claim you performed an action. If the app does not support the request, say so briefly and suggest a supported alternative. Supported actions are alarms, timers, calendar drafts, Camera, and Android management/settings screens. Current read-only capabilities are device, battery, storage, health, private notes, and authorized local-file search.
Request: $userPrompt
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

internal fun deterministicToolAnswer(toolName: String, rawResult: String): String {
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
            "The battery is at $levelText and ${formatOne(temperature)}°C. " +
                "Android does not expose a reliable remaining-hours estimate here; actual battery life depends on current usage."
        }
        "get_device_info" ->
            "This is a ${result.getString("manufacturer")} ${result.getString("model")}, " +
                "running Android ${result.getString("androidVersion")} on " +
                "${result.getString("cpuAbi")}."
        "get_media_info" -> when {
            !result.optBoolean("available", false) ->
                "I need Notification Access to see and control the active media session. Ask me to open media access settings."
            !result.optBoolean("active", false) -> "No active Spotify, YouTube, or other media session is visible right now."
            else -> {
                val title = result.optString("title").ifBlank { "Unknown title" }
                val artist = result.optString("artist")
                if (artist.isBlank()) "Currently active: $title." else "Currently active: $title by $artist."
            }
        }
        "search_local_files" -> {
            val error = result.optString("error")
            if (error.isNotBlank()) error else {
                val matches = result.optJSONArray("matches") ?: JSONArray()
                if (matches.length() == 0) "I found no matching local files." else
                    "I found ${matches.length()} local file match(es). Review them below before opening one."
            }
        }
        "search_notes" -> {
            val matches = result.optJSONArray("matches") ?: JSONArray()
            if (matches.length() == 0) "I found no matching local notes." else
                "I found ${matches.length()} matching local note(s): " +
                    (0 until matches.length()).joinToString("; ") { matches.getString(it) }
        }
        "save_note" -> result.optString("message", "The note was saved locally.")
        else -> error("Unknown tool: $toolName")
    }
}

private fun Long.toGib(): String = formatOne(this / 1024.0 / 1024.0 / 1024.0)

private fun formatOne(value: Double): String = "%.1f".format(Locale.US, value)

private fun String.toDisplayName(): String = removePrefix("get_")
    .removeSuffix("_info")
    .replace('_', ' ')
