package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Assert.assertTrue
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class DecisionHttpClientTest {

    @Test
    fun requestUsesReleasedMobileRouting9SchemaAndOriginalState() {
        val state = "Check my phone's storage, please."
        val body = JSONObject(DecisionHttpClient().buildRequestBody(state))
        val expected = JSONObject(requireNotNull(javaClass.getResource("/mobile-routing9-schema.json")).readText())
        assertEquals(state, body.getString("state"))
        assertTrue(expected.similar(body.getJSONObject("questions")))
    }

    @Test
    fun selectorPostsDecisionRequestAndParsesStructuredResponse() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var received: JSONObject? = null
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                check(reader.readLine() == "POST /v1/systemone HTTP/1.1")
                var length = 0
                while (true) {
                    val line = reader.readLine()
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                }
                val body = CharArray(length)
                var offset = 0
                while (offset < length) offset += reader.read(body, offset, length - offset)
                received = JSONObject(String(body))
                val response = """{"answers":{"route":{"choice":"get_storage_info"}}}"""
                val bytes = response.toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
            }
        }
        try {
            val result = DecisionHttpClient("http://127.0.0.1:${server.localPort}").select("Inspect free storage")
            worker.join(5000)
            assertEquals("get_storage_info", result.decision.toolName)
            assertEquals(0, result.generatedPieces)
            assertEquals("Inspect free storage", received!!.getString("state"))
            assertEquals(9, received!!.getJSONObject("questions").getJSONObject("route").getJSONObject("criteria").length())
        } finally {
            server.close()
        }
    }

    @Test
    fun answerChoiceMapsToAnswerDecision() {
        val d = DecisionHttpClient.routeToDecision("answer")
        assertEquals("answer", d.action)
        assertEquals("", d.text)
    }

    @Test
    fun phoneHealthCheckMapsToWorkflowDecision() {
        val d = DecisionHttpClient.routeToDecision("phone_health_check")
        assertEquals("workflow", d.action)
        assertEquals(PHONE_HEALTH_CHECK, d.workflowName)
    }

    @Test
    fun allToolRoutesMapsToToolDecision() {
        val toolRoutes = listOf(
            "get_battery_info",
            "get_device_info",
            "get_storage_info",
            "get_media_info",
            "search_local_files",
            "search_notes",
            "save_note",
        )
        for (route in toolRoutes) {
            val d = DecisionHttpClient.routeToDecision(route)
            assertEquals("Route $route: action", "tool", d.action)
            assertEquals("Route $route: toolName", route, d.toolName)
        }
    }

    @Test
    fun toolRoutesAreSubsetOfReadOnlyTools() {
        val toolRoutes = listOf(
            "get_battery_info", "get_device_info", "get_storage_info",
            "get_media_info", "search_local_files", "search_notes", "save_note",
        )
        for (route in toolRoutes) {
            assert(route in READ_ONLY_TOOLS) {
                "$route is not in READ_ONLY_TOOLS — routing contract mismatch"
            }
        }
    }

    @Test
    fun unknownRoutThrowsIllegalStateException() {
        try {
            DecisionHttpClient.routeToDecision("wipe_storage")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assert(e.message.orEmpty().contains("wipe_storage"))
        }
    }

    @Test
    fun allNinePhoneRoutingRoutesAreMapped() {
        val allRoutes = listOf(
            "answer",
            "get_battery_info",
            "get_device_info",
            "get_storage_info",
            "get_media_info",
            "search_local_files",
            "search_notes",
            "save_note",
            "phone_health_check",
        )
        for (route in allRoutes) {
            val d = DecisionHttpClient.routeToDecision(route)
            assert(d.action in setOf("answer", "tool", "workflow")) {
                "Route $route mapped to unexpected action: ${d.action}"
            }
        }
    }
}
