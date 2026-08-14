package com.pocketagentslab

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentBackendTest {
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
        assertTrue(fixture.prompts[1].contains("only permitted shape"))
        assertTrue(fixture.prompts[1].contains("20"))
    }

    @Test
    fun unknownActionNeverExecutesTool() = runBlocking {
        val fixture = fixture("""{"action":"get_storage_info","args":{}}""")

        expectFailure("Unknown action: get_storage_info") {
            fixture.backend.run("How much storage?")
        }
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun unknownToolNeverExecutesTool() = runBlocking {
        val fixture = fixture("""{"action":"tool","name":"delete_files","args":{}}""")

        expectFailure("Unknown tool: delete_files") {
            fixture.backend.run("Clean my phone")
        }
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun malformedJsonNeverExecutesTool() = runBlocking {
        val fixture = fixture("I would call get_storage_info")

        expectFailure("bare JSON object") {
            fixture.backend.run("How much storage?")
        }
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun toolArgumentsAreRejected() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"get_storage_info","args":{"path":"/data"}}""",
        )

        expectFailure("Tools accept no arguments") {
            fixture.backend.run("How much storage?")
        }
        assertTrue(fixture.toolCalls.isEmpty())
    }

    @Test
    fun secondToolCallIsRejectedAfterFirstToolRuns() = runBlocking {
        val fixture = fixture(
            """{"action":"tool","name":"get_battery_info","args":{}}""",
            """{"action":"tool","name":"get_device_info","args":{}}""",
        )

        expectFailure("Final response must use action=answer") {
            fixture.backend.run("Is my battery hot?")
        }
        assertEquals(listOf("get_battery_info"), fixture.toolCalls)
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

    private fun fixture(vararg responses: String): Fixture {
        val queued = ArrayDeque(responses.toList())
        val prompts = mutableListOf<String>()
        val toolCalls = mutableListOf<String>()
        val backend = AgentBackend(
            generator = AgentGenerator { prompt, _ ->
                prompts += prompt
                GeneratedText(queued.removeFirst(), pieces = 1)
            },
            tools = ReadOnlyToolExecutor { name ->
                toolCalls += name
                """{"availableBytes":20000000000}"""
            },
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

    private data class Fixture(
        val backend: AgentBackend,
        val prompts: MutableList<String>,
        val toolCalls: MutableList<String>,
    )
}
