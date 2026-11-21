package com.belugachat.app.llm

import okio.BufferedSource

data class SseEvent(val event: String?, val data: String)

/** Incremental Server-Sent Events parser. Feed it lines; it returns an event at each blank line. */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    fun flush(): SseEvent? {
        val result = if (hasData) SseEvent(event, data.toString()) else null
        event = null
        data.setLength(0)
        hasData = false
        return result
    }
}

/** Reads every SSE event from [source] until it is exhausted. */
inline fun readSse(source: BufferedSource, onEvent: (SseEvent) -> Unit) {
    val parser = SseParser()
    while (true) {
        val line = source.readUtf8Line() ?: break
        parser.feed(line)?.let(onEvent)
    }
    parser.flush()?.let(onEvent)
}
