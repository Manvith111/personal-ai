package com.aide.core

import com.aide.core.brain.AnalysisInput
import com.aide.core.brain.AssistantBrain
import com.aide.core.brain.BrainException
import com.aide.core.brain.ChatTurn
import com.aide.core.brain.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real SDK against a mock server: the exact requests sent by ask / briefing / weeklyReview, and parsing. */
class NewBrainFeaturesTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer().also { it.start() } }
    @AfterTest fun tearDown() = server.shutdown()

    private fun brain(model: String = AssistantBrain.DEFAULT_MODEL) =
        AssistantBrain("test-key", model, server.url("/").toString().trimEnd('/'))

    private fun str(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

    private fun reply(text: String, input: Int = 120, output: Int = 45, model: String = "claude-opus-5-5") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"$model",
               "content":[{"type":"text","text":${str(text)}}],"stop_reason":"end_turn","stop_sequence":null,
               "usage":{"input_tokens":$input,"output_tokens":$output}}""",
        )

    private fun requestBody() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun askSendsSchemaQuestionAndHistoryAndParsesTheAnswer() {
        server.enqueue(reply("""{"answer":"**Rs 45,000**, due Friday.","source_ids":["i-rahul"],"found":true}""", input = 900, output = 30))
        val r = brain().ask("What was the quote?", "<history>\nstuff\n</history>")

        assertEquals("**Rs 45,000**, due Friday.", r.answer.answer)
        assertEquals(listOf("i-rahul"), r.answer.sourceIds)
        assertTrue(r.answer.found)
        assertEquals(Usage(900, 30), r.usage)
        assertEquals("claude-opus-5-5", r.model)

        val body = requestBody()
        assertTrue(body["system"].toString().contains("Answer ONLY from the history"))
        val cfg = body["output_config"]!!.jsonObject
        assertEquals("low", cfg["effort"]!!.jsonPrimitive.content)
        val schema = cfg["format"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals(setOf("answer", "source_ids", "found"), schema["properties"]!!.jsonObject.keys)
        assertEquals(3, (schema["required"] as JsonArray).size)
        val msgs = body["messages"]!!.jsonArray
        assertEquals(1, msgs.size)
        val text = msgs[0].jsonObject["content"].toString()
        assertTrue(text.contains("<history>") && text.contains("<question>") && text.contains("What was the quote?"))
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
    }

    @Test
    fun askDropsSourceIdsClaudeInvented() {
        server.enqueue(reply("""{"answer":"x","source_ids":["i-rahul","made-up","i-rahul"],"found":true}"""))
        val r = brain().ask("q", "<history/>", validIds = setOf("i-rahul", "i-priya"))
        assertEquals(listOf("i-rahul"), r.answer.sourceIds)
    }

    @Test
    fun askReportsNotFound() {
        server.enqueue(reply("""{"answer":"I don't have that in your history.","source_ids":[],"found":false}"""))
        val r = brain().ask("What's the weather?", "<history/>")
        assertFalse(r.answer.found)
        assertTrue(r.answer.sourceIds.isEmpty())
    }

    @Test
    fun askKeepsChatOrderStartsWithUserAndSkipsBlanks() {
        server.enqueue(reply("""{"answer":"x","source_ids":[],"found":true}"""))
        brain().ask(
            "and his number?", "<history/>",
            previous = listOf(
                ChatTurn(fromUser = false, text = "orphan assistant turn at the start"),
                ChatTurn(fromUser = true, text = "What did Rahul say about the quote?"),
                ChatTurn(fromUser = false, text = "   "),
                ChatTurn(fromUser = false, text = "Rs 45,000"),
            ),
        )
        val msgs = requestBody()["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertTrue(msgs.last().jsonObject["content"].toString().contains("and his number?"))
    }

    @Test
    fun askRejectsAnUnexpectedShapeAsRetryable() {
        server.enqueue(reply("this is not json"))
        val e = assertFailsWith<BrainException> { brain().ask("q", "<history/>") }
        assertTrue(e.retryable)
    }

    @Test
    fun askOnHaikuSendsNoFallbacks() {
        server.enqueue(reply("""{"answer":"x","source_ids":[],"found":true}""", model = "claude-haiku-5-5"))
        brain("claude-haiku-5-5").ask("q", "<history/>")
        assertFalse(requestBody().containsKey("fallbacks"))
    }

    @Test
    fun briefingAndWeeklyUseTheirOwnPromptsAndPlainTextOutput() {
        server.enqueue(reply("## Quick brief\nRahul is a client.", input = 500, output = 80))
        server.enqueue(reply("## Your week in numbers\n3 conversations.", input = 700, output = 120))
        val b = brain().briefing("Person to brief the owner about: Rahul")
        val w = brain().weeklyReview("Week: 2026-10-30 to 2026-11-05")
        assertEquals("## Quick brief\nRahul is a client.", b.markdown)
        assertEquals(Usage(700, 120), w.usage)

        val briefBody = requestBody()
        assertTrue(briefBody["system"].toString().contains("## Quick brief"))
        assertEquals("medium", briefBody["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(briefBody["output_config"]!!.jsonObject.containsKey("format"), "plain Markdown, no JSON schema")

        val weeklyBody = requestBody()
        assertTrue(weeklyBody["system"].toString().contains("end-of-week review"))
        assertTrue(weeklyBody["messages"].toString().contains("Week: 2026-10-30"))
    }

    @Test
    fun emptyTextIsARetryableError() {
        server.enqueue(reply("   "))
        val e = assertFailsWith<BrainException> { brain().briefing("x") }
        assertTrue(e.retryable)
    }

    @Test
    fun analyzeNowReportsUsage() {
        server.enqueue(reply("""{"title":"t","summary":"s","key_points":[],"people_mentioned":[],"actions":[]}""", input = 321, output = 54))
        val r = brain().analyze(AnalysisInput("ctx", "hello"))
        assertEquals(Usage(321, 54), r.usage)
        assertEquals(375L, r.usage.total)
    }

    @Test
    fun researchSumsUsageAcrossPauseTurnContinuations() {
        fun sse(stop: String, text: String?, input: Int, output: Int): MockResponse {
            val sb = StringBuilder()
            fun ev(t: String, d: String) = sb.append("event: $t\ndata: $d\n\n")
            ev("message_start", """{"type":"message_start","message":{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":$input,"output_tokens":1}}}""")
            if (text != null) {
                ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
                ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${str(text)}}}""")
                ev("content_block_stop", """{"type":"content_block_stop","index":0}""")
            }
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{"output_tokens":$output}}""")
            ev("message_stop", """{"type":"message_stop"}""")
            return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sb.toString())
        }
        server.enqueue(sse("pause_turn", "Searching… ", input = 1000, output = 200))
        server.enqueue(sse("end_turn", "# Done\n**Bottom line:** yes.", input = 1500, output = 300))
        val r = brain().research("q", "ctx")
        assertEquals(Usage(2500, 500), r.usage)
        assertTrue(r.markdown.endsWith("yes."))
    }

    @Test
    fun usageArithmetic() {
        assertEquals(Usage(3, 7), Usage(1, 2) + Usage(2, 5))
        assertEquals(Usage.NONE, Usage())
    }
}
