package com.pocketagentslab

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentBackendTest {
    @Test
    fun hierarchicalAnswerStopsAfterScopeDecision() = runBlocking {
        val fixture = fixture(
            """{"scope":"answer"}""",
            hierarchicalRouting = true,
        )

        val selection = fixture.backend.select("Tell me a joke")

        assertEquals("answer", selection.decision.action)
        assertEquals(1, fixture.prompts.size)
        assertTrue(fixture.prompts.single().contains("current facts from this phone"))
    }

    @Test
    fun hierarchicalLiveRequestUsesSecondConstrainedDecision() = runBlocking {
        val fixture = fixture(
            """{"scope":"live_device"}""",
            """{"action":"tool","name":"get_storage_info","args":{}}""",
            hierarchicalRouting = true,
        )

        val selection = fixture.backend.select("How much storage is free?")

        assertEquals("get_storage_info", selection.decision.toolName)
        assertEquals(2, fixture.prompts.size)
        assertTrue(fixture.prompts[1].contains("Choose exactly one route"))
    }

    @Test
    fun grammarSelectedAnswerGetsASeparateAnswerGeneration() = runBlocking {
        val fixture = fixture(
            """{"action":"answer","text":""}""",
            """{"action":"answer","text":"4"}""",
        )

        val result = fixture.backend.run("What is 2+2?")

        assertEquals("4", result.answer)
        assertEquals("answer", result.route)
        assertEquals(2, fixture.prompts.size)
        assertTrue(fixture.prompts[1].contains("without using device tools"))
    }

    @Test
    fun separateFinalGenerationAcceptsPlainNaturalLanguage() = runBlocking {
        val fixture = fixture(
            """{"action":"answer","text":""}""",
            "4",
        )

        val result = fixture.backend.run("What is 2+2?")

        assertEquals("4", result.answer)
        assertEquals("answer", result.route)
    }

    @Test
    fun routingPromptDistinguishesRamFromStorageCapacity() {
        val prompt = buildRoutingPrompt("How much RAM is available?")

        assertTrue(prompt.contains("ABI, and RAM"))
        assertTrue(prompt.contains("Room for another model"))
        assertTrue(prompt.contains("get_storage_info"))
    }

    @Test
    fun localFileSearchIsAnAcceptedReadOnlyTool() {
        val decision = parseAgentDecision("""{"action":"tool","name":"search_local_files","args":{}}""")
        assertEquals("search_local_files", decision.toolName)
        assertTrue(buildRoutingPrompt("Find my QLoRA document").contains("search_local_files"))
    }

    @Test
    fun noteToolsAreGrammarConstrainedAndAccepted() {
        assertEquals(
            "search_notes",
            parseAgentDecision("""{"action":"tool","name":"search_notes","args":{}}""").toolName,
        )
        assertEquals(
            "save_note",
            parseAgentDecision("""{"action":"tool","name":"save_note","args":{}}""").toolName,
        )
    }

    @Test
    fun focusedModelPassCanCorrectAmbiguousNoteRoute() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"search_notes","args":{}}""",
            """{"action":"tool","name":"save_note","args":{}}""",
        )
        val selection = fixture.backend.select("experiment number is 842 remember it")
        assertEquals("save_note", selection.decision.toolName)
        assertEquals(2, fixture.prompts.size)
        assertTrue(fixture.prompts.last().contains("SAVE_NOTE"))
    }

    @Test
    fun truncatedFinalJsonFailsSoftInsteadOfStoppingAgent() {
        assertEquals(CLARIFICATION_MESSAGE, parseFinalAnswer("""{"action":"answer","text":""""))
    }

    @Test
    fun directAnswerUsesOneModelCallAndNoTool() = runBlocking {
        val fixture = fixture("""{"action":"answer","text":"A local joke"}""")

        val result = fixture.backend.run("Tell me a joke")

        assertEquals("A local joke", result.answer)
        assertEquals("answer", result.route)
        assertEquals(1, fixture.prompts.size)
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun approvedSettingsActionIsProposedButNeverExecuted() = runBlocking {
        val fixture = fixture(
            """{"action":"propose","name":"open_storage_settings","args":{}}""",
            allowDeviceActions = true,
        )

        val result = fixture.backend.run("Open storage settings")

        assertEquals("propose:open_storage_settings", result.route)
        assertEquals(OPEN_STORAGE_SETTINGS, result.proposedAction?.name)
        assertTrue(result.answer.contains("Confirm"))
        assertTrue(fixture.toolCalls.isEmpty())
        assertTrue(fixture.prompts.single().startsWith(GRAMMAR_AGENT_ROUTE_PREFIX))
    }

    @Test
    fun timerProposalIsValidatedBeforeConfirmation() = runBlocking {
        val fixture = fixture(
            """{"action":"propose","name":"set_timer","args":{}}""",
            allowDeviceActions = true,
        )

        val result = fixture.backend.run("Set a timer for 15 minutes")

        assertEquals("propose:set_timer", result.route)
        assertEquals(SET_TIMER, result.proposedAction?.name)
        assertEquals(900, result.proposedAction?.durationSeconds)
        assertTrue(result.answer.contains("Confirm"))
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun incompleteTimerProposalAsksForDetailsAndCannotExecute() = runBlocking {
        val fixture = fixture(
            """{"action":"propose","name":"set_timer","args":{}}""",
            allowDeviceActions = true,
        )

        val result = fixture.backend.run("Set a timer")

        assertEquals("clarify:set_timer", result.route)
        assertNull(result.proposedAction)
        assertTrue(result.answer.contains("duration"))
    }

    @Test
    fun everydayAlarmProposalCarriesValidatedSchedule() = runBlocking {
        val fixture = fixture(
            """{"action":"propose","name":"set_alarm","args":{}}""",
            allowDeviceActions = true,
        )

        val result = fixture.backend.run("Set alarm to 9.13 am everyday")

        assertEquals("propose:set_alarm", result.route)
        assertEquals(9, result.proposedAction?.hour)
        assertEquals(13, result.proposedAction?.minute)
        assertEquals(7, result.proposedAction?.repeatDays?.size)
        assertTrue(result.answer.contains("09:13 every day"))
    }

    @Test
    fun calendarEventRequiresConfirmationAndNeverExecutesInBackend() = runBlocking {
        val fixture = fixture(
            """{"action":"propose","name":"create_calendar_event","args":{}}""",
            allowDeviceActions = true,
        )

        val result = fixture.backend.run("Add dentist appointment tomorrow at 3 PM")

        assertEquals("propose:create_calendar_event", result.route)
        assertEquals(CREATE_CALENDAR_EVENT, result.proposedAction?.name)
        assertEquals("dentist appointment", result.proposedAction?.title)
        assertTrue(result.answer.contains("Confirm"))
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun cameraAndBackgroundReviewAreConfirmedRatherThanExecuted() = runBlocking {
        val camera = fixture(
            """{"action":"propose","name":"open_camera","args":{}}""",
            allowDeviceActions = true,
        ).backend.run("Open camera")
        val cleanupFixture = fixture(
            """{"action":"propose","name":"review_background_apps","args":{}}""",
            allowDeviceActions = true,
        )
        val cleanup = cleanupFixture.backend.run("Please remove unnecessary processes running behind")

        assertEquals(OPEN_CAMERA, camera.proposedAction?.name)
        assertEquals(REVIEW_BACKGROUND_APPS, cleanup.proposedAction?.name)
        assertTrue(cleanup.answer.contains("does not allow me to safely force-close"))
        assertTrue(cleanupFixture.toolCalls.isEmpty())
    }

    @Test
    fun proposedActionRejectsUnknownOrArgumentBearingActions() {
        expectFailureSync("Unknown proposed action") {
            parseAgentDecision("""{"action":"propose","name":"wipe_storage","args":{}}""")
        }
        expectFailureSync("accept no arguments") {
            parseAgentDecision("""{"action":"propose","name":"open_battery_settings","args":{"force":true}}""")
        }
    }

    @Test
    fun copiedRoutingJokeBecomesClarificationInsteadOfFakeAnswer() = runBlocking {
        val fixture = fixture(
            """{"action":"answer","text":"Why did the byte cross the road?"}""",
        )

        val result = fixture.backend.run("Please inspect this thing")

        assertEquals(CLARIFICATION_MESSAGE, result.answer)
        assertEquals("clarify:copied-example", result.route)
        assertTrue(fixture.toolCalls.isEmpty())
        assertTrue(fixture.prompts.single().contains(CLARIFICATION_MESSAGE))
        assertTrue(!fixture.prompts.single().contains("byte cross"))
    }

    @Test
    fun storageRequestRunsToolThenRequiresFinalAnswer() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"get_storage_info","args":{}}""",
            """{"action":"answer","text":"You have 20 GB available."}""",
        )

        val result = fixture.backend.run("How much storage do I have?")

        assertEquals("You have 20 GB available.", result.answer)
        assertEquals("tool:get_storage_info", result.route)
        assertEquals(listOf("get_storage_info"), fixture.toolCalls)
        assertEquals(2, fixture.prompts.size)
        assertTrue(fixture.prompts[1].contains("Use action \"answer\""))
        assertTrue(fixture.prompts[1].contains("20"))
    }

    @Test
    fun unknownActionNeverExecutesTool() = runBlocking {
        val fixture = fixture(
            """{"action":"erase_storage","args":{}}""",
            """{"action":"erase_storage","args":{}}""",
        )

        expectFailure("Unknown action: erase_storage") {
            fixture.backend.run("How much storage?")
        }
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun allowlistedToolShorthandIsSafelyNormalized() = runBlocking {
        val fixture = fixture(
            """{"action":"get_storage_info","args":{}}""",
            """{"action":"answer","text":"You have 20 GB available."}""",
        )

        val selection = fixture.backend.select("How much storage?")
        assertTrue(selection.decision.schemaRepaired)
        assertEquals("get_storage_info", selection.decision.toolName)

        val result = fixture.backend.complete("How much storage?", selection)
        assertEquals("tool:get_storage_info (normalized)", result.route)
        assertEquals(listOf("get_storage_info"), fixture.toolCalls)
    }

    @Test
    fun copiedFinalPlaceholderFallsBackToRealToolData() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"get_storage_info","args":{}}""",
            """{"action":"answer","text":"YOUR NATURAL-LANGUAGE ANSWER"}""",
            toolResult = """{"totalBytes":68719476736,"availableBytes":21474836480,"usedBytes":47244640256}""",
        )

        val result = fixture.backend.run("How much storage do I have?")

        assertEquals("You have 20.0 GB available out of 64.0 GB of internal storage.", result.answer)
        assertEquals("tool:get_storage_info (fallback answer)", result.route)
    }

    @Test
    fun healthWorkflowRunsAllThreeToolsAndPreservesDiagnosis() = runBlocking {
        val responses = ArrayDeque(
            listOf(
                """{"action":"workflow","name":"phone_health_check","args":{}}""",
                """{"action":"answer","text":"Storage is low; free some space."}""",
            ),
        )
        val calls = mutableListOf<String>()
        val progress = mutableListOf<AgentProgress>()
        val backend = AgentBackend(
            generator = AgentGenerator { _, _ -> GeneratedText(responses.removeFirst(), 1) },
            tools = ReadOnlyToolExecutor { name, _ ->
                calls += name
                when (name) {
                    "get_device_info" -> """{"model":"A32","androidVersion":"13","cpuAbi":"arm64-v8a"}"""
                    "get_battery_info" -> """{"levelPercent":50,"temperatureC":30,"isCharging":false}"""
                    "get_storage_info" -> """{"totalBytes":1000,"availableBytes":50,"usedBytes":950}"""
                    else -> error(name)
                }
            },
            onProgress = progress::add,
        )

        val result = backend.run("Run a phone health check")

        assertEquals(
            listOf("get_device_info", "get_battery_info", "get_storage_info"),
            calls,
        )
        assertEquals("workflow:phone_health_check", result.route)
        assertTrue(JSONObject(result.diagnosis!!).getJSONArray("warnings").toString().contains("low_storage"))
        assertTrue(result.answer.contains("storage is low at 5.0%"))
        assertTrue(result.answer.contains("Local SLM suggestions"))
        assertEquals(
            listOf(0.15f, 0.30f, 0.40f, 0.50f, 0.60f, 0.72f, 1.0f),
            progress.map(AgentProgress::fraction),
        )
    }

    @Test
    fun unknownToolNeverExecutesTool() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"delete_files","args":{}}""",
            """{"action":"tool","name":"delete_files","args":{}}""",
        )

        expectFailure("Unknown tool: delete_files") {
            fixture.backend.run("Clean my phone")
        }
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun malformedJsonNeverExecutesTool() = runBlocking {
        val fixture = fixture("I would call get_storage_info", "still not JSON")

        expectFailure("bare JSON object") {
            fixture.backend.run("How much storage?")
        }
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun invalidJsonGetsExactlyOneConstrainedRepairAttempt() = runBlocking {
        val fixture = fixture(
            "I would call get_storage_info",
            """{"action":"tool","name":"get_storage_info","args":{}}""",
        )

        val selection = fixture.backend.select("How much storage is free?")

        assertTrue(selection.repairAttempted)
        assertEquals("get_storage_info", selection.decision.toolName)
        assertEquals(2, fixture.prompts.size)
        assertTrue(fixture.prompts[1].contains("Model did not return a bare JSON object"))
        assertTrue(fixture.prompts[1].contains("How much storage is free?"))
    }

    @Test
    fun markdownJsonFenceIsSafelyNormalizedForXlamStyleOutput() {
        val decision = parseAgentDecision(
            """```json
                {"action":"answer","text":"2 + 2 = 4"}
                ```""".trimIndent(),
        )

        assertEquals("answer", decision.action)
        assertEquals("2 + 2 = 4", decision.text)
        assertTrue(decision.schemaRepaired)
    }

    @Test
    fun xlamNativeSingleToolCallIsNormalized() {
        val decision = parseAgentDecision(
            """[{"name":"get_storage_info","arguments":{}}]""",
        )

        assertEquals("tool", decision.action)
        assertEquals("get_storage_info", decision.toolName)
        assertTrue(decision.schemaRepaired)
    }

    @Test
    fun xlamNativeThreeToolCallIsNormalizedToHealthWorkflow() {
        val decision = parseAgentDecision(
            """[{"name":"get_device_info","arguments":{}},{"name":"get_battery_info","arguments":{}},{"name":"get_storage_info","arguments":{}}]""",
        )

        assertEquals("workflow", decision.action)
        assertEquals(PHONE_HEALTH_CHECK, decision.workflowName)
        assertTrue(decision.schemaRepaired)
    }

    @Test
    fun xlamNativeTwoCategoryBundleMapsToHealthWorkflow() {
        val decision = parseAgentDecision(
            """[{"name":"get_storage_info","arguments":{}},{"name":"get_battery_info","arguments":{}}]""",
        )

        assertEquals("workflow", decision.action)
        assertEquals(PHONE_HEALTH_CHECK, decision.workflowName)
        assertTrue(decision.schemaRepaired)
    }

    @Test
    fun incompleteMarkdownFenceIsNotSilentlyAccepted() {
        val (text, normalized) = unwrapJsonFence("""```json {"action":"answer","text":"4"}""")

        assertFalse(normalized)
        assertTrue(text.startsWith("```json"))
    }

    @Test
    fun repairRunsAfterFreshContextHook() = runBlocking {
        var resets = 0
        val responses = ArrayDeque(
            listOf("not json", """{"action":"answer","text":"4"}"""),
        )
        val backend = AgentBackend(
            generator = AgentGenerator { _, _ -> GeneratedText(responses.removeFirst(), 1) },
            tools = ReadOnlyToolExecutor { _, _ -> error("No tool expected") },
            beforeRepair = { resets++ },
        )

        val selection = backend.select("What is 2+2?")

        assertEquals(1, resets)
        assertTrue(selection.repairAttempted)
    }

    @Test
    fun toolArgumentsAreRejected() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"get_storage_info","args":{"path":"/data"}}""",
            """{"action":"tool","name":"get_storage_info","args":{"path":"/data"}}""",
        )

        expectFailure("Tools accept no arguments") {
            fixture.backend.run("How much storage?")
        }
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun batteryFactsUseDeterministicAnswerWithoutSecondGeneration() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"get_battery_info","args":{}}""",
            toolResult = """{"levelPercent":63,"temperatureC":31.2,"isCharging":false}""",
        )

        val result = fixture.backend.run("How much battery life do I have?")

        assertEquals(listOf("get_battery_info"), fixture.toolCalls)
        assertTrue(result.answer.contains("remaining-hours estimate"))
        assertEquals(1, fixture.prompts.size)
    }

    @Test
    fun capabilityQuestionUsesTrustedLocalHelpWithoutLoadingTheModel() = runBlocking {
        val fixture = fixture()

        val result = fixture.backend.run("I generally do not know things to do on my phone, can you help me?")

        assertEquals(CAPABILITY_HELP, result.answer)
        assertTrue(fixture.prompts.isEmpty())
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun unrelatedStorageRouteIsDowngradedToAnAnswer() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"get_storage_info","args":{}}""",
            """{"action":"answer","text":"I cannot make a collage yet."}""",
        )

        val result = fixture.backend.run("Can you do a collage of my photos?")

        assertTrue(result.answer.contains("cannot create photo collages yet"))
        assertTrue(fixture.toolCalls.isEmpty())
        assertEquals(1, fixture.prompts.size)
    }

    @Test
    fun actionRelevancePolicyRejectsNearestUnrelatedAction() {
        assertFalse(
            isDecisionRelevant(
                AgentDecision(action = "propose", proposedAction = OPEN_STORAGE_SETTINGS),
                "Can you open a song from Spotify for me?",
            ),
        )
        assertTrue(
            isDecisionRelevant(
                AgentDecision(action = "propose", proposedAction = OPEN_CAMERA),
                "Open camera",
            ),
        )
    }

    @Test
    fun installedAppLaunchIsResolvedBeforeConfirmation() = runBlocking {
        val fixture = fixture(
            """{"action":"propose","name":"launch_app","args":{}}""",
            allowDeviceActions = true,
            actionResolver = { action, _ ->
                DeviceActionProposal(action, appPackage = "com.spotify.music", appLabel = "Spotify")
            },
        )

        val result = fixture.backend.run("Open Spotify")

        assertEquals("com.spotify.music", result.proposedAction?.appPackage)
        assertTrue(result.answer.contains("Open Spotify"))
    }

    @Test
    fun unsupportedRealUserRequestsReceiveTruthfulProductAnswers() {
        assertTrue(trustedDirectAnswer("How can I change my wallpaper?")!!.contains("Wallpaper and style"))
        assertTrue(trustedDirectAnswer("Can you do a collage of my photos?")!!.contains("cannot create"))
        assertTrue(trustedDirectAnswer("Can you text my boyfriend from WhatsApp?")!!.contains("never press Send"))
        assertTrue(trustedDirectAnswer("Can you control a YouTube video forward?")!!.contains("cannot control"))
        assertTrue(trustedDirectAnswer("Can you use ChatGPT for better results?")!!.contains("keeps inference local"))
    }

    @Test
    fun inventedExplainActionIsRejectedWithClearError() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"get_device_info","args":{}}""",
            """{"action":"explain","text":"Device details"}""",
        )

        expectFailure("Unknown action: explain") {
            fixture.backend.run("What Android version is this?")
        }
    }

    private fun fixture(
        vararg responses: String,
        toolResult: String = """{"availableBytes":20000000000}""",
        hierarchicalRouting: Boolean = false,
        allowDeviceActions: Boolean = false,
        actionResolver: (String, String) -> DeviceActionProposal? = { action, request ->
            buildDeviceActionProposal(action, request)
        },
    ): Fixture {
        val queued = ArrayDeque(responses.toList())
        val prompts = mutableListOf<String>()
        val toolCalls = mutableListOf<String>()
        val backend = AgentBackend(
            generator = AgentGenerator { prompt, _ ->
                prompts += prompt
                GeneratedText(queued.removeFirst(), pieces = 1)
            },
            tools = ReadOnlyToolExecutor { name, _ ->
                toolCalls += name
                toolResult
            },
            hierarchicalRouting = hierarchicalRouting,
            allowDeviceActions = allowDeviceActions,
            actionResolver = actionResolver,
        )
        return Fixture(backend, prompts, toolCalls)
    }

    private suspend fun expectFailure(message: String, block: suspend () -> Unit) {
        var caught: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            caught = error
        }
        if (caught == null) fail("Expected failure containing: $message")
        assertTrue("Actual message: ${caught?.message}", caught?.message.orEmpty().contains(message))
    }

    private fun expectFailureSync(message: String, block: () -> Unit) {
        var caught: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            caught = error
        }
        if (caught == null) fail("Expected failure containing: $message")
        assertTrue("Actual message: ${caught?.message}", caught?.message.orEmpty().contains(message))
    }

    private data class Fixture(
        val backend: AgentBackend,
        val prompts: MutableList<String>,
        val toolCalls: MutableList<String>,
    )
}
