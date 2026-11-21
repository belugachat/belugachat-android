package com.belugachat.app

import com.belugachat.app.llm.AnthropicClient
import com.belugachat.app.llm.Content
import com.belugachat.app.llm.LlmMessage
import com.belugachat.app.llm.LlmRequest
import com.belugachat.app.llm.OpenAiClient
import com.belugachat.app.llm.Presets
import com.belugachat.app.llm.ProviderException
import com.belugachat.app.llm.SseParser
import com.belugachat.app.llm.StreamEvent
import com.belugachat.app.llm.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ProviderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("content-type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    private val tool = ToolSpec("current_time", "time", buildJsonObject { put("type", "object") })

    @Test fun sseParserJoinsMultilineDataAndSkipsComments() {
        val parser = SseParser()
        assertNull(parser.feed(": ping"))
        assertNull(parser.feed("event: message"))
        assertNull(parser.feed("data: a"))
        assertNull(parser.feed("data:b"))
        val event = parser.feed("")!!
        assertEquals("message", event.event)
        assertEquals("a\nb", event.data)
        assertNull(parser.feed(""))
    }

    @Test fun anthropicStreamsTextThinkingAndTools() = runBlocking {
        server.enqueue(sse(
            """{"type":"message_start","message":{"usage":{"input_tokens":12}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hi"}}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"t1","name":"current_time"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"1}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}""",
            """{"type":"message_stop"}""",
        ))
        val client = AnthropicClient(server.url("/").toString(), "key")
        val events = client.stream(LlmRequest("claude-opus-4-6", "sys", listOf(LlmMessage("user", listOf(Content.Text("hi")))), listOf(tool), "high")).toList()
        assertTrue(StreamEvent.ThinkingDelta("hmm") in events)
        assertTrue(StreamEvent.ThinkingSignature("sig") in events)
        assertTrue(StreamEvent.TextDelta("Hi") in events)
        assertTrue(StreamEvent.ToolCall("t1", "current_time", "{\"a\":1}") in events)
        assertEquals(StreamEvent.Usage(12, 7), events.filterIsInstance<StreamEvent.Usage>().last())
        assertEquals(StreamEvent.Finished("tool_use"), events.last())

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("key", request.getHeader("x-api-key"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(body["temperature"])
    }

    @Test fun anthropicLegacyModelsUseBudgetAndToolResultsMergeIntoUserTurn() {
        val messages = listOf(
            LlmMessage("user", listOf(Content.Text("q"))),
            LlmMessage("assistant", listOf(Content.Thinking("t", "s", null), Content.ToolUse("c1", "x", JsonObject(emptyMap())))),
            LlmMessage("tool", listOf(Content.ToolResult("c1", "ok", false))),
            LlmMessage("user", listOf(Content.Text("next"))),
        )
        val payload = AnthropicClient.buildPayload(LlmRequest("claude-haiku-4-5", "", messages, reasoning = "low"))
        assertEquals("enabled", payload["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2048, payload["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        val sent = payload["messages"]!!.jsonArray
        assertEquals(3, sent.size)
        val last = sent[2].jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        assertEquals(2, last["content"]!!.jsonArray.size)
        assertEquals("thinking", sent[1].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(payload["system"])
    }

    @Test fun anthropicErrorsSurfaceProviderMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        try {
            AnthropicClient(server.url("/").toString(), "bad").stream(LlmRequest("claude-opus-4-6", "", emptyList())).toList()
            fail("expected error")
        } catch (e: ProviderException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!.contains("invalid x-api-key"))
        }
    }

    @Test fun anthropicListsModels() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"claude-opus-4-6","display_name":"Claude Opus 4.6"}]}"""))
        val models = AnthropicClient(server.url("/v1").toString(), "k").listModels()
        assertEquals("Claude Opus 4.6", models.single().displayName)
        assertEquals("/v1/models?limit=1000", server.takeRequest().path)
    }

    @Test fun openAiStreamsTextReasoningToolCallsAndUsage() = runBlocking {
        server.enqueue(sse(
            """{"choices":[{"delta":{"reasoning_content":"think"}}]}""",
            """{"choices":[{"delta":{"content":"Hel"}}]}""",
            """{"choices":[{"delta":{"content":"lo"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"web_search","arguments":"{\"q"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"uery\":\"x\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":3}}""",
            "[DONE]",
        ))
        val client = OpenAiClient(server.url("/v1").toString(), "sk", "openai")
        val events = client.stream(LlmRequest("gpt-5", "sys", listOf(LlmMessage("user", listOf(Content.Text("hi")))))).toList()
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertTrue(StreamEvent.ThinkingDelta("think") in events)
        assertTrue(StreamEvent.ToolCall("call_1", "web_search", """{"query":"x"}""") in events)
        assertTrue(StreamEvent.Usage(5, 3) in events)
        assertEquals(StreamEvent.Finished("tool_use"), events.last())
        assertEquals(1, events.count { it is StreamEvent.Finished })
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk", request.getHeader("authorization"))
    }

    @Test fun openAiPayloadMapsToolsImagesAndResults() {
        val payload = OpenAiClient.buildPayload(
            LlmRequest(
                "gpt-5", "sys",
                listOf(
                    LlmMessage("user", listOf(Content.Text("look"), Content.Image("image/png", "AAA"))),
                    LlmMessage("assistant", listOf(Content.ToolUse("c1", "current_time", JsonObject(emptyMap())))),
                    LlmMessage("tool", listOf(Content.ToolResult("c1", "noon", false))),
                ),
                listOf(tool), reasoning = "medium",
            ),
            "openai",
        )
        val messages = payload["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val image = messages[1].jsonObject["content"]!!.jsonArray[1].jsonObject
        assertEquals("data:image/png;base64,AAA", image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("c1", messages[2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("tool", messages[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("medium", payload["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("function", payload["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun openRouterUsesReasoningObjectAndDefaultSendsNothing() {
        val req = LlmRequest("x", "", emptyList(), reasoning = "high")
        assertEquals("high", OpenAiClient.buildPayload(req, "openrouter")["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val plain = OpenAiClient.buildPayload(req.copy(reasoning = "default"), "openai")
        assertFalse(plain.containsKey("reasoning_effort"))
    }

    @Test fun openRouterSendsBelugaHeaders() = runBlocking {
        server.enqueue(sse("[DONE]"))
        val client = OpenAiClient(server.url("/v1").toString(), "sk", "openrouter")
        client.stream(LlmRequest("deepseek/deepseek-r1", "", listOf(LlmMessage("user", listOf(Content.Text("hi")))))).toList()
        val request = server.takeRequest()
        assertEquals("https://belugachat.app", request.getHeader("HTTP-Referer"))
        assertEquals("Beluga Chat", request.getHeader("X-Title"))
    }

    @Test fun deepSeekReplaysReasoningContentOnToolTurnsAndOmitsReasoningEffort() {
        val payload = OpenAiClient.buildPayload(
            LlmRequest(
                model = "deepseek-reasoner",
                system = "sys",
                messages = listOf(
                    LlmMessage("user", listOf(Content.Text("check time"))),
                    LlmMessage(
                        "assistant",
                        listOf(
                            Content.Thinking("need current_time tool", null, null),
                            Content.ToolUse("c1", "current_time", JsonObject(emptyMap())),
                        ),
                    ),
                    LlmMessage("tool", listOf(Content.ToolResult("c1", "12:00", false))),
                ),
                tools = listOf(tool),
                reasoning = "high",
            ),
            preset = "deepseek",
        )
        assertFalse(payload.containsKey("reasoning_effort"))
        assertFalse(payload.containsKey("reasoning"))
        val messages = payload["messages"]!!.jsonArray
        val assistantMsg = messages[2].jsonObject
        assertEquals("need current_time tool", assistantMsg["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("c1", assistantMsg["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun deployPresetAndReasoningEffortMapping() {
        assertEquals("deepseek", Presets.all[0].id)
        assertEquals("deploy", Presets.all[1].id)
        val deploy = Presets.byId("deploy")
        assertEquals("Deploy.com", deploy.name)
        assertEquals("https://api-cdn.deploy.com/api/v1", deploy.baseUrl)
        assertEquals("https://portal.deploy.com/?api_keys=1", deploy.keyUrl)

        val base = LlmRequest("deepseek-ai/deepseek-v4.1-flash-1017", "", emptyList())
        assertFalse(OpenAiClient.buildPayload(base.copy(reasoning = "default"), "deploy").containsKey("reasoning_effort"))
        assertEquals("low", OpenAiClient.buildPayload(base.copy(reasoning = "low"), "deploy")["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("high", OpenAiClient.buildPayload(base.copy(reasoning = "medium"), "deploy")["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("high", OpenAiClient.buildPayload(base.copy(reasoning = "high"), "deploy")["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("max", OpenAiClient.buildPayload(base.copy(reasoning = "max"), "deploy")["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun legacyDetection() {
        assertTrue(AnthropicClient.isLegacyThinking("claude-haiku-4-5"))
        assertTrue(AnthropicClient.isLegacyThinking("claude-3-5-sonnet-20241022"))
        assertTrue(AnthropicClient.isLegacyThinking("claude-sonnet-4-20250514"))
        assertFalse(AnthropicClient.isLegacyThinking("claude-opus-4-6"))
        assertFalse(AnthropicClient.isLegacyThinking("claude-sonnet-4-6"))
    }
}
