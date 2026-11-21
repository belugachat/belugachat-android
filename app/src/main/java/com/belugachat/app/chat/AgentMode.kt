package com.belugachat.app.chat

/** Slash-command modes that make the assistant work more autonomously. */
object AgentMode {
    const val CHAT = "chat"
    const val GOAL = "goal"
    const val DEEPSEARCH = "deepsearch"

    val autonomous = setOf(GOAL, DEEPSEARCH)

    /** Tools that are safe to run without asking while in an autonomous mode. */
    val autoApproveTools = setOf("current_time", "web_search", "web_fetch", "view_skill", "spawn_subagent")

    private val commands = mapOf(
        "goal" to GOAL,
        "deepsearch" to DEEPSEARCH,
        "research" to DEEPSEARCH,
    )

    private val instructionText = mapOf(
        GOAL to "You are in autonomous goal mode. Break the request into concrete steps and work " +
            "through them using the available tools until the goal is actually achieved. Keep going " +
            "across multiple tool rounds instead of stopping at a plan. Verify important results, then " +
            "finish with a concise summary of what you did, what you found, and anything that remains. " +
            "Only ask the user a question if you are truly blocked.",
        DEEPSEARCH to "You are in deep research mode. Investigate the question thoroughly: search for " +
            "multiple angles, fetch primary sources, and cross-check claims before relying on them. " +
            "Prefer authoritative and recent sources. Separate facts from inference, note uncertainty, " +
            "and finish with a structured report that cites the URLs you used.",
    )

    private val commandRegex = Regex("^\\s*/([A-Za-z_-]+)\\b")

    fun detect(text: String): String {
        val name = commandRegex.find(text)?.groupValues?.getOrNull(1)?.lowercase() ?: return CHAT
        return commands[name] ?: CHAT
    }

    /** Removes a leading mode command while keeping the rest of the message. */
    fun removeCommand(text: String): String =
        text.replaceFirst(Regex("^\\s*/[A-Za-z_-]+\\s*"), "")

    fun isAutonomous(mode: String): Boolean = mode in autonomous

    fun instructions(mode: String): String? = instructionText[mode]

    fun label(mode: String): String? = when (mode) {
        GOAL -> "Goal mode"
        DEEPSEARCH -> "Deep research"
        else -> null
    }

    fun maxRounds(mode: String): Int = when (mode) {
        GOAL -> 24
        DEEPSEARCH -> 16
        else -> ChatEngine.MAX_ROUNDS
    }

    /** Commands offered in the composer, in display order. */
    val suggestions: List<Pair<String, String>> = listOf(
        "/goal" to "Work autonomously through a task until it is done.",
        "/deepsearch" to "Search, read sources, and produce a cited report.",
    )
}
