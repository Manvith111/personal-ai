package com.aide.core

import com.aide.core.brain.AnalysisInput
import com.aide.core.brain.AssistantBrain
import com.aide.core.brain.BrainException
import com.aide.core.model.ActionType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/**
 * Runs the real SDK against a local mock server: checks the exact request we send (model, beta
 * header, fallbacks, structured-output schema, tools) and that responses are parsed correctly.
 */
class AssistantBrainTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun brain(model: String = AssistantBrain.DEFAULT_MODEL) =
        AssistantBrain("test-key", model, server.url("/").toString().trimEnd('/'))

    private fun messageJson(content: String, stopReason: String = "end_turn", model: String = "claude-opus-5-5") = """
        {"id":"msg_1","type":"message","role":"assistant","model":"$model",
         "content":$content,"stop_reason":"$stopReason","stop_sequence":null,
         "usage":{"input_tokens":10,"output_tokens":20}}
    """.trimIndent()

    private fun textBlock(text: String) =
        """[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}]"""

    @Test
    fun analyzeSendsSchemaAndFallbackAndParsesActions() {
        val analysis = """
            {"title":"Call with Rahul","summary":"Agreed to meet at 5pm.","key_points":["Meet at Cafe Coffee Day"],
             "people_mentioned":["Rahul"],
             "actions":[
               {"type":"calendar_event","title":"Meet Rahul","details":null,"start_local":"2026-11-02T17:00",
                "end_local":null,"all_day":false,"location":"Cafe Coffee Day","people":["Rahul"],"recipient":null,
                "message_body":null,"research_question":null,"evidence":"I will meet you today at 5pm","confidence":0.95},
               {"type":"research","title":"Research co-working spaces","details":null,"start_local":null,
                "end_local":null,"all_day":false,"location":null,"people":[],"recipient":null,"message_body":null,
                "research_question":"Best co-working spaces in Andheri under 10k/month?","evidence":"research it","confidence":0.9}
             ]}
        """.trimIndent()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(messageJson(textBlock(analysis))))

        val result = brain().analyze(AnalysisInput("Reference time: Monday", "I will meet you today at 5pm"))

        assertEquals("Call with Rahul", result.analysis.title)
        assertEquals(2, result.analysis.actions.size)
        assertEquals(ActionType.CALENDAR_EVENT, result.analysis.actions[0].type)
        assertEquals("2026-11-02T17:00", result.analysis.actions[0].startLocal)
        assertEquals(ActionType.RESEARCH, result.analysis.actions[1].type)
        assertEquals("claude-opus-5-5", result.model)

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path?.substringBefore('?'))
        val beta = request.getHeader("anthropic-beta").orEmpty()
        assertTrue(beta.contains(AssistantBrain.FALLBACK_BETA), "beta header was: $beta")
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        val outputConfig = body["output_config"]!!.jsonObject
        assertEquals("medium", outputConfig["effort"]!!.jsonPrimitive.content)
        val format = outputConfig["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        val schema = format["schema"]!!.jsonObject
        assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
        val actionSchema = schema["properties"]!!.jsonObject["actions"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(13, (actionSchema["required"] as JsonArray).size)
        assertTrue(body["system"].toString().contains("You are Aide"))
        assertFalse(body.containsKey("thinking"), "thinking must be omitted for Opus 5.5")
    }

    @Test
    fun haikuDoesNotSendFallbacks() {
        val analysis = """{"title":"t","summary":"s","key_points":[],"people_mentioned":[],"actions":[]}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody(messageJson(textBlock(analysis), model = "claude-haiku-5-5")))
        brain("claude-haiku-5-5").analyze(AnalysisInput("ctx", "hello"))
        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertFalse(body.containsKey("fallbacks"))
        assertFalse(request.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback"))
    }

    @Test
    fun refusalBecomesNonRetryableError() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody(messageJson("[]", stopReason = "refusal")))
        val e = assertFailsWith<BrainException> { brain().analyze(AnalysisInput("ctx", "x")) }
        assertFalse(e.retryable)
    }

    @Test
    fun unauthorizedIsReportedClearly() {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val e = assertFailsWith<BrainException> { brain().analyze(AnalysisInput("ctx", "x")) }
        assertTrue(e.message!!.contains("API key"))
        assertFalse(e.retryable)
    }

    @Test
    fun researchResumesAfterPauseTurnAndCollectsSources() {
        // Turn 1: the server-side tool loop pauses mid-way.
        val paused = """[
          {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"co-working Andheri"}},
          {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
            {"type":"web_search_result","url":"https://example.com/a","title":"Spaces A","encrypted_content":"x","page_age":null}]}
        ]"""
        // Turn 2: the final answer, with a citation.
        val final = """[
          {"type":"text","text":"# Co-working in Andheri\n**Bottom line:** WeWork is best.","citations":[
            {"type":"web_search_result_location","url":"https://example.com/b","title":"Review B",
             "encrypted_index":"y","cited_text":"WeWork is best"}]}
        ]"""
        server.enqueue(sse(messageJson(paused, stopReason = "pause_turn")))
        server.enqueue(sse(messageJson(final)))

        val result = brain().research("Best co-working in Andheri?", "User region: India")

        assertTrue(result.markdown.startsWith("# Co-working in Andheri"))
        assertEquals(listOf("https://example.com/b"), result.sources.map { it.url })

        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val tools = first["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("web_search_20260209", "web_fetch_20260209"), tools)
        assertEquals("true", first["stream"]!!.jsonPrimitive.content)

        // The continuation must replay the paused assistant turn unchanged and add no extra user turn.
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = second["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        val replayed = messages[1].jsonObject["content"]!!.jsonArray.map {
            (it as JsonObject)["type"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("server_tool_use", "web_search_tool_result"), replayed)
    }

    /** Wraps a complete message into the SSE event sequence the streaming endpoint returns. */
    private fun sse(message: String): MockResponse {
        val msg = Json.parseToJsonElement(message).jsonObject
        val content = msg["content"]!!.jsonArray
        val start = JsonObject(msg.toMutableMap().apply {
            put("content", JsonArray(emptyList()))
            put("stop_reason", kotlinx.serialization.json.JsonNull)
        })
        val sb = StringBuilder()
        fun event(type: String, data: String) = sb.append("event: $type\ndata: $data\n\n")
        event("message_start", """{"type":"message_start","message":$start}""")
        content.forEachIndexed { i, block ->
            val b = block.jsonObject
            if (b["type"]!!.jsonPrimitive.content == "text") {
                val empty = JsonObject(b.toMutableMap().apply { put("text", kotlinx.serialization.json.JsonPrimitive("")) ; remove("citations") })
                event("content_block_start", """{"type":"content_block_start","index":$i,"content_block":$empty}""")
                b["citations"]?.jsonArray?.forEach { c ->
                    event("content_block_delta", """{"type":"content_block_delta","index":$i,"delta":{"type":"citations_delta","citation":$c}}""")
                }
                val text = Json.encodeToString(kotlinx.serialization.serializer<String>(), b["text"]!!.jsonPrimitive.content)
                event("content_block_delta", """{"type":"content_block_delta","index":$i,"delta":{"type":"text_delta","text":$text}}""")
            } else if (b["type"]!!.jsonPrimitive.content == "server_tool_use") {
                val noInput = JsonObject(b.toMutableMap().apply { put("input", JsonObject(emptyMap())) })
                event("content_block_start", """{"type":"content_block_start","index":$i,"content_block":$noInput}""")
                val partial = Json.encodeToString(kotlinx.serialization.serializer<String>(), b["input"].toString())
                event("content_block_delta", """{"type":"content_block_delta","index":$i,"delta":{"type":"input_json_delta","partial_json":$partial}}""")
            } else {
                event("content_block_start", """{"type":"content_block_start","index":$i,"content_block":$b}""")
            }
            event("content_block_stop", """{"type":"content_block_stop","index":$i}""")
        }
        event("message_delta", """{"type":"message_delta","delta":{"stop_reason":${msg["stop_reason"]},"stop_sequence":null},"usage":{"output_tokens":20}}""")
        event("message_stop", """{"type":"message_stop"}""")
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sb.toString())
    }
}
