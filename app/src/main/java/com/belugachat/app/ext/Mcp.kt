package com.belugachat.app.ext

import com.belugachat.app.BuildConfig
import com.belugachat.app.llm.Http
import com.belugachat.app.llm.SseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class McpTool(val name: String, val description: String = "", val inputSchema: JsonObject = JsonObject(emptyMap()))

data class McpResult(val text: String, val isError: Boolean)

class McpException(message: String) : Exception(message)

/** Minimal MCP client for the Streamable HTTP transport (JSON or SSE responses). */
class McpClient(
    private val url: String,
    private val headers: Map<String, String>,
    private val http: OkHttpClient = Http.client,
) {
    private val mutex = Mutex()
    private var sessionId: String? = null
    private var protocolVersion: String? = null
    private var initialized = false
    private var nextId = 1

    suspend fun listTools(): List<McpTool> = withSession {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val result = request("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } })
            result["tools"]?.jsonArray.orEmpty().forEach { item ->
                val obj = item.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                tools += McpTool(
                    name = name,
                    description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    inputSchema = (obj["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") },
                )
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        tools
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpResult = withSession {
        val result = request("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
        val texts = result["content"]?.jsonArray.orEmpty().map { item ->
            val obj = item.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                "image" -> "[image: ${obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image"}]"
                "resource" -> (obj["resource"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: obj.toString()
                else -> obj.toString()
            }
        }
        val text = texts.joinToString("\n").ifBlank { result["structuredContent"]?.toString() ?: "(no output)" }
        McpResult(text, isError)
    }

    private suspend fun <T> withSession(block: suspend () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!initialized) initialize()
            try {
                block()
            } catch (e: SessionExpired) {
                initialized = false
                sessionId = null
                initialize()
                block()
            }
        }
    }

    private class SessionExpired : Exception()

    private fun initialize() {
        val result = request("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "beluga-chat-android"); put("version", BuildConfig.VERSION_NAME) })
        }, initializing = true)
        protocolVersion = result["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: "2025-06-18"
        initialized = true
        notify("notifications/initialized")
    }

    private fun baseRequest(body: JsonObject): Request {
        val builder = Request.Builder()
            .url(url)
            .header("accept", "application/json, text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        sessionId?.let { builder.header("mcp-session-id", it) }
        if (initialized) protocolVersion?.let { builder.header("mcp-protocol-version", it) }
        return builder.build()
    }

    private fun notify(method: String) {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }
        http.newCall(baseRequest(body)).execute().close()
    }

    private fun request(method: String, params: JsonObject?, initializing: Boolean = false): JsonObject {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        http.newCall(baseRequest(body)).execute().use { response ->
            if (response.code == 404 && sessionId != null && !initializing) throw SessionExpired()
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty().take(300)
                val hint = if (response.code == 401 || response.code == 403) " Check the server's auth headers." else ""
                throw McpException("MCP server returned HTTP ${response.code}.$hint ${text}".trim())
            }
            if (initializing) response.header("mcp-session-id")?.let { sessionId = it }
            val contentType = response.header("content-type").orEmpty()
            val source = response.body?.source() ?: throw McpException("Empty response from MCP server")
            if (contentType.contains("text/event-stream")) {
                val parser = SseParser()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    val event = parser.feed(line) ?: continue
                    match(event.data, id)?.let { return it }
                }
                parser.flush()?.let { event -> match(event.data, id)?.let { return it } }
                throw McpException("MCP server closed the stream without answering $method")
            }
            return match(source.readUtf8(), id) ?: throw McpException("MCP server sent no result for $method")
        }
    }

    private fun match(data: String, id: Int): JsonObject? {
        val element = runCatching { Json.parseToJsonElement(data) }.getOrNull() ?: return null
        val messages: List<JsonElement> = if (element is JsonArray) element else listOf(element)
        for (message in messages) {
            val obj = message as? JsonObject ?: continue
            if (obj["id"]?.jsonPrimitive?.intOrNull != id) continue
            (obj["error"] as? JsonObject)?.let { error ->
                throw McpException(error["message"]?.jsonPrimitive?.contentOrNull ?: "MCP error")
            }
            return (obj["result"] as? JsonObject) ?: JsonObject(emptyMap())
        }
        return null
    }
}

/** Keeps one client (and MCP session) per configured server. */
class McpConnections {
    private val clients = mutableMapOf<String, Pair<String, McpClient>>()

    @Synchronized
    fun client(serverId: String, url: String, headers: Map<String, String>): McpClient {
        val fingerprint = url + headers.hashCode()
        clients[serverId]?.let { (fp, client) -> if (fp == fingerprint) return client }
        return McpClient(url, headers).also { clients[serverId] = fingerprint to it }
    }

    @Synchronized
    fun forget(serverId: String) {
        clients.remove(serverId)
    }
}

fun parseHeaders(text: String): Map<String, String> = text.lines()
    .mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }
    .filter { it.first.isNotEmpty() }
    .toMap()

fun headersToText(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
