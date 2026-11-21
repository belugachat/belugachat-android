package com.belugachat.app.chat

import com.belugachat.app.data.MessageEntity
import com.belugachat.app.data.Part
import com.belugachat.app.data.Role
import com.belugachat.app.data.ToolStatus
import com.belugachat.app.llm.Content
import com.belugachat.app.llm.LlmMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Converts stored messages into provider-neutral history. */
object History {
    fun build(path: List<MessageEntity>, resolveFile: (Part.File) -> List<Content>): List<LlmMessage> {
        val out = mutableListOf<LlmMessage>()
        for (message in path) {
            val parts = message.parts
            if (message.role == Role.USER) {
                val content = parts.flatMap { part ->
                    when (part) {
                        is Part.File -> resolveFile(part)
                        is Part.Text -> listOf(Content.Text(part.text))
                        else -> emptyList()
                    }
                }
                if (content.isNotEmpty()) out += LlmMessage("user", content)
            } else {
                out += assistantRounds(parts)
            }
        }
        return out
    }

    /**
     * One stored assistant message can span several model rounds (text → tool calls → more text).
     * Each run of tool calls closes a round and is followed by a `tool` message with the results.
     */
    fun assistantRounds(parts: List<Part>): List<LlmMessage> {
        val out = mutableListOf<LlmMessage>()
        var current = mutableListOf<Content>()
        var results = mutableListOf<Content>()

        fun flush() {
            if (current.isNotEmpty()) out += LlmMessage("assistant", current)
            if (results.isNotEmpty()) out += LlmMessage("tool", results)
            current = mutableListOf()
            results = mutableListOf()
        }

        for (part in parts) {
            when (part) {
                is Part.ToolCall -> {
                    current += Content.ToolUse(part.id, part.name, parseArgs(part.arguments))
                    results += Content.ToolResult(part.id, resultText(part), isError(part))
                }
                else -> {
                    if (results.isNotEmpty()) flush()
                    when (part) {
                        is Part.Text -> if (part.text.isNotBlank()) current += Content.Text(part.text)
                        is Part.Thinking -> current += Content.Thinking(part.text, part.signature, part.redacted)
                        else -> Unit
                    }
                }
            }
        }
        flush()
        return out
    }

    private fun resultText(part: Part.ToolCall): String = when (part.status) {
        ToolStatus.REJECTED -> "The user declined to run this tool."
        ToolStatus.DONE, ToolStatus.ERROR -> part.result.orEmpty()
        else -> part.result ?: "The tool call was interrupted before it finished."
    }

    private fun isError(part: Part.ToolCall) = part.isError || part.status != ToolStatus.DONE

    fun parseArgs(raw: String): JsonObject =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
}
