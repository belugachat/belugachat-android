package com.belugachat.app

import com.belugachat.app.chat.ChatEngine
import com.belugachat.app.chat.History
import com.belugachat.app.chat.MessageTree
import com.belugachat.app.data.MessageEntity
import com.belugachat.app.data.Part
import com.belugachat.app.data.SkillEntity
import com.belugachat.app.data.ToolStatus
import com.belugachat.app.data.decodeParts
import com.belugachat.app.data.encodeParts
import com.belugachat.app.llm.Content
import com.belugachat.app.tools.AppTool
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {
    private fun msg(id: String, parent: String?, role: String, t: Long, text: String = id) =
        MessageEntity(id, "c", parent, role, encodeParts(listOf(Part.Text(text))), text, t)

    // u1 → a1, a1b (regenerated); u1 edited as u1b → a2
    private val tree = listOf(
        msg("u1", null, "user", 1),
        msg("a1", "u1", "assistant", 2),
        msg("a1b", "u1", "assistant", 3),
        msg("u1b", null, "user", 4),
        msg("a2", "u1b", "assistant", 5),
    )

    @Test fun pathFollowsParents() {
        assertEquals(listOf("u1", "a1b"), MessageTree.path(tree, "a1b").map { it.id })
        assertEquals(emptyList<String>(), MessageTree.path(tree, null).map { it.id })
    }

    @Test fun siblingsAndSwitching() {
        val s = MessageTree.siblings(tree, tree.first { it.id == "a1" })
        assertEquals(listOf("a1", "a1b"), s.ids)
        assertEquals(0, s.index)
        assertEquals("a1b", MessageTree.switchSibling(tree, tree.first { it.id == "a1" }, 1))
        // switching the user message lands on the newest leaf below the other version
        assertEquals("a2", MessageTree.switchSibling(tree, tree.first { it.id == "u1" }, 1))
        assertEquals("a1b", MessageTree.switchSibling(tree, tree.first { it.id == "u1b" }, -1))
        assertEquals(null, MessageTree.switchSibling(tree, tree.first { it.id == "u1b" }, 1))
    }

    @Test fun assistantRoundsSplitAroundToolCalls() {
        val parts = listOf(
            Part.Thinking("plan", "sig"),
            Part.Text("Let me check."),
            Part.ToolCall("t1", "current_time", "{}", ToolStatus.DONE, "noon"),
            Part.ToolCall("t2", "web_search", "{\"query\":\"x\"}", ToolStatus.REJECTED),
            Part.Text("It is noon."),
        )
        val rounds = History.assistantRounds(parts)
        assertEquals(listOf("assistant", "tool", "assistant"), rounds.map { it.role })
        assertEquals(4, rounds[0].content.size)
        val results = rounds[1].content.filterIsInstance<Content.ToolResult>()
        assertEquals("noon", results[0].content)
        assertTrue(results[1].isError)
        assertEquals(Content.Text("It is noon."), rounds[2].content.single())
    }

    @Test fun unfinishedToolCallsStillGetResults() {
        val rounds = History.assistantRounds(listOf(Part.ToolCall("t", "x", "{}", ToolStatus.RUNNING)))
        assertEquals(listOf("assistant", "tool"), rounds.map { it.role })
        assertTrue((rounds[1].content.single() as Content.ToolResult).isError)
    }

    @Test fun historyResolvesFilesAndSkipsEmptyAssistant() {
        val user = MessageEntity("u", "c", null, "user",
            encodeParts(listOf(Part.File("f", "a.png", "image/png", "f.png", 3), Part.Text("what is this"))), "", 1)
        val empty = MessageEntity("a", "c", "u", "assistant", "[]", "", 2, status = "error")
        val history = History.build(listOf(user, empty)) { listOf(Content.Image(it.mime, "B64")) }
        assertEquals(1, history.size)
        assertEquals(Content.Image("image/png", "B64"), history[0].content[0])
    }

    @Test fun partsRoundTripThroughJson() {
        val parts = listOf(Part.Text("hi"), Part.ToolCall("1", "n", "{}", ToolStatus.AWAITING_APPROVAL), Part.Thinking("t", redacted = "x"))
        assertEquals(parts, decodeParts(encodeParts(parts)))
    }

    @Test fun streamingHelpersCoalesce() {
        val parts = mutableListOf<Part>()
        ChatEngine.appendThinking(parts, "a")
        ChatEngine.appendThinking(parts, "b")
        ChatEngine.signLastThinking(parts, "sig")
        ChatEngine.appendText(parts, "x")
        ChatEngine.appendText(parts, "y")
        assertEquals(listOf(Part.Thinking("ab", "sig"), Part.Text("xy")), parts)
    }

    @Test fun subagentsGetMcpAndSkillToolsButCannotRecurse() {
        fun tool(key: String, name: String, serverId: String? = null) = AppTool(
            key = key,
            name = name,
            title = name,
            description = name,
            schema = JsonObject(emptyMap()),
            group = if (serverId == null) "Built-in" else "Exa",
            enabled = true,
            autoApprove = true,
            serverId = serverId,
        )
        val active = listOf(
            tool("builtin:web_search", "web_search"),
            tool("builtin:view_skill", "view_skill"),
            tool("builtin:spawn_subagent", "spawn_subagent"),
            tool("mcp:exa:web_search_exa", "exa_web_search_exa", serverId = "exa"),
        )
        val allowed = ChatEngine.subagentToolsFor(active).map { it.name }
        assertEquals(listOf("web_search", "view_skill", "exa_web_search_exa"), allowed)
        assertFalse("spawn_subagent" in allowed)
    }

    @Test fun subagentsExcludeUnapprovedToolsUnlessAutonomousOrAlwaysAllowed() {
        fun tool(key: String, name: String, autoApprove: Boolean, serverId: String? = null) = AppTool(
            key = key,
            name = name,
            title = name,
            description = name,
            schema = JsonObject(emptyMap()),
            group = if (serverId == null) "Built-in" else "Exa",
            enabled = true,
            autoApprove = autoApprove,
            serverId = serverId,
        )
        val active = listOf(
            tool("builtin:web_search", "web_search", autoApprove = true),
            tool("builtin:web_fetch", "web_fetch", autoApprove = false),
            tool("mcp:github:create_issue", "github_create_issue", autoApprove = false, serverId = "github"),
            tool("builtin:spawn_subagent", "spawn_subagent", autoApprove = true),
        )
        assertEquals(
            listOf("web_search"),
            ChatEngine.subagentToolsFor(active).map { it.name },
        )
        assertEquals(
            listOf("web_search", "github_create_issue"),
            ChatEngine.subagentToolsFor(active, alwaysAllowed = setOf("github_create_issue")).map { it.name },
        )
        assertEquals(
            listOf("web_search", "web_fetch"),
            ChatEngine.subagentToolsFor(active, autonomous = true).map { it.name },
        )
    }

    @Test fun subagentPromptIncludesAvailableSkillsAndInlinesMentionedSkill() {
        val skills = listOf(
            SkillEntity("s1", "deep-research", "Multi-source synthesis", "Use primary sources first.", createdAt = 1L),
            SkillEntity("s2", "code-audit", "Review code", "Check edge cases.", createdAt = 2L),
        )
        val promptWithSkill = ChatEngine.subagentSystemPrompt("Researcher", skills, "Investigate using @deep-research")
        assertTrue(promptWithSkill.contains("Your role: Researcher."))
        assertTrue(promptWithSkill.contains("Today's date is "))
        assertTrue(promptWithSkill.contains("<available_skills>"))
        assertTrue(promptWithSkill.contains("- deep-research: Multi-source synthesis"))
        assertTrue(promptWithSkill.contains("<skill name=\"deep-research\">\nUse primary sources first.\n</skill>"))
        assertFalse(promptWithSkill.contains("<skill name=\"code-audit\">"))

        val promptWithoutSkills = ChatEngine.subagentSystemPrompt(null, emptyList(), "Simple task")
        assertFalse(promptWithoutSkills.contains("<available_skills>"))
    }
}
