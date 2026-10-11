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
 * Note: This class is a code seam. The app has not been rebuilt with it wired into
 * [AgentBackend]; it is not yet active in production. Wire it in via the [AgentBackend]
 * constructor's [decisionSelector] parameter after validating routing accuracy.
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
     *   fall back to the grammar-based generator path.
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

    private fun buildRequestBody(state: String): String =
        JSONObject()
            .put("model", "reflex-1")
            .put("state", state)
            .put("workflow", "phone_routing")
            .put("questions", PHONE_ROUTING_QUESTIONS)
            .toString()

    private fun parseChoice(responseBody: String): String {
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

        // Questions structure matches phone_routing_v1 training contract (manifest.json).
        // Do not change these strings without retraining the model.
        private val PHONE_ROUTING_QUESTIONS: JSONObject = JSONObject("""
            {
              "route": {
                "type": "choice",
                "instructions": "Select exactly one Android agent route by the meaning of the request. Use live phone tools only for current phone facts. Use phone_health_check for overall condition or two or more live categories. Otherwise answer without a tool.",
                "criteria": {
                  "answer": "Writing, jokes, arithmetic, definitions, general knowledge, or unclear requests. No live phone data needed.",
                  "get_device_info": "Current phone model, manufacturer, Android version, CPU ABI or physical RAM.",
                  "get_battery_info": "Live battery level, charging status, temperature, heat or need to cool down.",
                  "get_storage_info": "Live internal disk space, used/free/total storage, capacity to save files or models.",
                  "get_media_info": "Currently playing song or video metadata.",
                  "search_local_files": "Find a filename or phrase in previously authorized local files.",
                  "search_notes": "Recall or find information previously saved to notes.",
                  "save_note": "Explicitly remember or save information for later.",
                  "phone_health_check": "Overall phone health, condition, AI workload readiness, check everything, or two or more live categories together."
                }
              }
            }
        """.trimIndent())
    }
}
