package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DecisionHttpClientTest {

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
