package com.belugachat.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One piece of a stored message. A message is an ordered list of parts. */
@Serializable
sealed interface Part {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Part

    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val text: String,
        val signature: String? = null,
        val redacted: String? = null,
        val durationMs: Long? = null,
    ) : Part

    /** A user attachment. [path] is relative to the app's attachment directory. */
    @Serializable
    @SerialName("file")
    data class File(
        val id: String,
        val name: String,
        val mime: String,
        val path: String,
        val size: Long,
    ) : Part

    /** A tool call made by the model, together with its result once it ran. */
    @Serializable
    @SerialName("tool")
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
        val status: ToolStatus = ToolStatus.RUNNING,
        val result: String? = null,
        val isError: Boolean = false,
        val thoughtSignature: String? = null,
    ) : Part
}

@Serializable
enum class ToolStatus { AWAITING_APPROVAL, RUNNING, DONE, ERROR, REJECTED }

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

object MessageStatus {
    const val COMPLETE = "complete"
    const val STREAMING = "streaming"
    const val STOPPED = "stopped"
    const val ERROR = "error"
}

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    classDiscriminator = "type"
}

private val partsSerializer = ListSerializer(Part.serializer())

fun encodeParts(parts: List<Part>): String = AppJson.encodeToString(partsSerializer, parts)

fun decodeParts(json: String): List<Part> =
    runCatching { AppJson.decodeFromString(partsSerializer, json) }.getOrDefault(emptyList())

/** Plain text used for search and copy. */
fun List<Part>.plainText(): String =
    filterIsInstance<Part.Text>().joinToString("\n\n") { it.text }.trim()
