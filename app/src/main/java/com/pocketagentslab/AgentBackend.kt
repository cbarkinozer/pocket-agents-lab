package com.pocketagentslab

import org.json.JSONObject

internal const val AGENT_DECISION_TOKENS = 128
internal const val AGENT_ANSWER_TOKENS = 256

internal val READ_ONLY_TOOLS = setOf(
    "get_device_info",
    "get_battery_info",
    "get_storage_info",
)

internal data class AgentDecision(
    val action: String,
    val text: String? = null,
    val toolName: String? = null,
    val schemaRepaired: Boolean = false,
)

internal data class GeneratedText(val text: String, val pieces: Int)

internal data class AgentSelection(
    val decision: AgentDecision,
    val generatedPieces: Int,
)

internal data class AgentRunResult(
    val answer: String,
    val route: String,
    val generatedPieces: Int,
)

internal fun interface AgentGenerator {
    suspend fun generate(prompt: String, maxTokens: Int): GeneratedText
}

internal fun interface ReadOnlyToolExecutor {
    fun execute(name: String): String
}

/** UI-independent two-step agent state machine. */
internal class AgentBackend(
    private val generator: AgentGenerator,
    private val tools: ReadOnlyToolExecutor,
) {
    suspend fun select(userPrompt: String): AgentSelection {
        val generated = generator.generate(buildRoutingPrompt(userPrompt), AGENT_DECISION_TOKENS)
        return AgentSelection(parseAgentDecision(generated.text), generated.pieces)
    }

    suspend fun complete(userPrompt: String, selection: AgentSelection): AgentRunResult {
        val decision = selection.decision
        if (decision.action == "answer") {
            return AgentRunResult(
                answer = decision.text.orEmpty(),
                route = "answer",
                generatedPieces = selection.generatedPieces,
            )
        }

        val toolName = requireNotNull(decision.toolName)
        val toolResult = tools.execute(toolName)
        val generated = generator.generate(
            buildFinalAnswerPrompt(userPrompt, toolName, toolResult),
            AGENT_ANSWER_TOKENS,
        )
        val finalDecision = parseAgentDecision(generated.text)
        require(finalDecision.action == "answer") {
            "Final response must use action=answer, got action=${finalDecision.action}"
        }
        return AgentRunResult(
            answer = finalDecision.text.orEmpty(),
            route = "tool:$toolName" + if (decision.schemaRepaired) " (normalized)" else "",
            generatedPieces = selection.generatedPieces + generated.pieces,
        )
    }

    suspend fun run(userPrompt: String): AgentRunResult = complete(userPrompt, select(userPrompt))
}

internal fun parseAgentDecision(raw: String): AgentDecision {
    val trimmed = raw.trim()
    require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
        "Model did not return a bare JSON object"
    }
    val json = JSONObject(trimmed)
    return when (val action = json.optString("action")) {
        "answer" -> {
            require(json.length() == 2 && json.has("text")) { "Invalid answer schema" }
            AgentDecision(action = action, text = json.getString("text"))
        }
        "tool" -> {
            require(json.length() == 3 && json.has("name") && json.has("args")) {
                "Invalid tool schema"
            }
            val name = json.getString("name")
            require(name in READ_ONLY_TOOLS) { "Unknown tool: $name" }
            require(json.getJSONObject("args").length() == 0) { "Tools accept no arguments" }
            AgentDecision(action = action, toolName = name)
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
        else -> error("Unknown action: $action")
    }
}

internal fun buildRoutingPrompt(userPrompt: String): String = """Return exactly one compact JSON object. Do not answer outside JSON.
For current device facts choose one tool using {"action":"tool","name":"TOOL_NAME","args":{}}.
Allowed TOOL_NAME values: get_device_info, get_battery_info, get_storage_info.
For anything else use {"action":"answer","text":"YOUR ANSWER"}.
Examples:
User: How much storage is free?
Output: {"action":"tool","name":"get_storage_info","args":{}}
User: Is the battery hot?
Output: {"action":"tool","name":"get_battery_info","args":{}}
User: What Android version is this?
Output: {"action":"tool","name":"get_device_info","args":{}}
User: Tell me a joke.
Output: {"action":"answer","text":"Why did the byte cross the bus?"}
User: $userPrompt
Output:"""

internal fun buildFinalAnswerPrompt(
    userPrompt: String,
    toolName: String,
    toolResult: String,
): String = """Return exactly one compact JSON object and nothing else.
The only permitted shape for this response is:
{"action":"answer","text":"YOUR NATURAL-LANGUAGE ANSWER"}
Do not use action=tool, action=explain, or any other action. The tool has already run.
Original user question: $userPrompt
Tool used: $toolName
Tool result: $toolResult
Output:"""
