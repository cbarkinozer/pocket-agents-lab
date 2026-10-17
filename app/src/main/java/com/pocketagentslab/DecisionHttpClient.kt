package com.pocketagentslab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP implementation of [AgentDecisionSelector] that calls the Clef /v1/systemone endpoint.
 *
 * Prerequisites:
 *  - The reflex-1 GGUF server must be running on [baseUrl] before calling [select].
 *  - Start the server with: llama-server -m model-Q4_K_M.gguf --no-repack --jinja
 *    (The Clef GGUF has no vocabulary logits — it cannot serve chat completions.)
 *  - Port-forward with adb forward tcp:8091 tcp:8091 before the first call.
 *
 * Used by ReflexInferenceEngine after starting its packaged on-device runtime.
 * Requests stay on loopback. The selector never generates prose or tool arguments.
 *
 * @param baseUrl  Server base URL, e.g. "http://127.0.0.1:8091"
 * @param timeoutMs Connection and read timeout per request (default 45 000 ms)
 */
internal class DecisionHttpClient(
    private val baseUrl: String = "http://127.0.0.1:8091",
    private val timeoutMs: Int = 45_000,
) : AgentDecisionSelector {

    /**
     * Routes [userPrompt] via /v1/systemone and returns an [AgentSelection].
     *
     * @throws IOException if the HTTP call fails or times out — caller should
     *   report the failure without pretending the decision GGUF can generate text.
     * @throws IllegalStateException if the response is structurally invalid or
     *   returns an unrecognised route — treat as a routing failure.
     */
    override suspend fun select(userPrompt: String): AgentSelection =
        withContext(Dispatchers.IO) {
            val choice = postSystemone(userPrompt)
            AgentSelection(
                decision = routeToDecision(choice),
                generatedPieces = 0,
            )
        }

    private fun postSystemone(state: String): String {
        val body = buildRequestBody(state)
        val url = URL("$baseUrl/v1/systemone")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("Server returned HTTP $responseCode: ${errorBody.take(256)}")
            }
            return connection.inputStream.bufferedReader().readText()
                .let { parseChoice(it) }
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequestBody(state: String): String =
        JSONObject()
            .put("model", "reflex-1")
            .put("state", state)
            .put("workflow", "phone_routing")
            .put("questions", PHONE_ROUTING_QUESTIONS)
            .toString()

    internal fun parseChoice(responseBody: String): String {
        val root = JSONObject(responseBody)
        val answers = root.optJSONObject("answers")
            ?: error("Response missing 'answers' field")
        val route = answers.optJSONObject("route")
            ?: error("Response missing 'answers.route' field")
        val choice = route.optString("choice")
        check(choice.isNotBlank()) { "Response 'answers.route.choice' is blank" }
        return choice
    }

    companion object {
        /**
         * Maps a /v1/systemone route choice to an [AgentDecision].
         * This must stay in sync with the nine-route schema in phone_routing_v1.
         */
        internal fun routeToDecision(choice: String): AgentDecision = when (choice) {
            "answer" ->
                AgentDecision(action = "answer", text = "")
            "phone_health_check" ->
                AgentDecision(action = "workflow", workflowName = PHONE_HEALTH_CHECK)
            "get_battery_info", "get_device_info", "get_storage_info",
            "get_media_info", "search_local_files", "search_notes", "save_note" ->
                AgentDecision(action = "tool", toolName = choice)
            else ->
                error("Unrecognised route from decision model: $choice")
        }

        // Frozen MOBILE_ROUTING9 schema used to train and evaluate the released model.
        internal val PHONE_ROUTING_QUESTIONS: JSONObject = JSONObject("""
{
  "route": {
    "type": "choice",
    "instructions": "Select exactly one route by the user's intended task. Route requests by meaning, including plausible future phone capabilities. Use phone_health_check for overall phone condition or requests spanning multiple live-data categories. Use answer for ordinary responses or when no stable tool intent can be inferred.",
    "criteria": {
      "answer": "General knowledge, arithmetic, writing, conversation, or a request requiring clarification because no stable tool intent is clear.",
      "get_device_info": "Identify this device or inspect its OS, hardware specifications, capabilities, ratings, or certifications.",
      "get_battery_info": "Inspect this phone's battery level, charging, temperature, health, usage, or runtime estimates.",
      "get_storage_info": "Inspect this phone's storage space, usage, capacity, or internal or external storage status.",
      "get_media_info": "Inspect current or recent media playback, metadata, availability, or playback settings.",
      "search_local_files": "Find files on this device by name, type, content, time, location, or provenance.",
      "search_notes": "Retrieve information or a note previously saved in the notes system.",
      "save_note": "Store new information in the notes system when the user explicitly asks to save or remember it.",
      "phone_health_check": "Inspect the phone's overall condition or readiness, or combine two or more live phone-data categories."
    }
  }
}
        """.trimIndent())
    }
}
