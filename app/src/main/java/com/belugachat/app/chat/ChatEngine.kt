package com.belugachat.app.chat

import com.belugachat.app.data.AppDatabase
import com.belugachat.app.data.ConversationEntity
import com.belugachat.app.data.MessageEntity
import com.belugachat.app.data.MessageStatus
import com.belugachat.app.data.Part
import com.belugachat.app.data.ProviderRepository
import com.belugachat.app.data.Role
import com.belugachat.app.data.SettingsStore
import com.belugachat.app.data.SkillEntity
import com.belugachat.app.data.ToolStatus
import com.belugachat.app.data.encodeParts
import com.belugachat.app.data.plainText
import com.belugachat.app.ext.ExtensionsRepository
import com.belugachat.app.llm.Content
import com.belugachat.app.llm.LlmClient
import com.belugachat.app.llm.LlmMessage
import com.belugachat.app.llm.LlmRequest
import com.belugachat.app.llm.StreamEvent
import com.belugachat.app.tools.AppTool
import com.belugachat.app.tools.ToolOutcome
import com.belugachat.app.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NoModelException : Exception("Choose a model first. Add an API key in Settings → Providers if you haven't yet.")

/** A response that is being generated right now. */
data class LiveRun(val conversationId: String, val messageId: String, val parts: List<Part>)

/** Creates messages, drives the model ↔ tool loop and keeps the message tree consistent. */
class ChatEngine(
    private val db: AppDatabase,
    private val providers: ProviderRepository,
    private val tools: ToolRegistry,
    private val extensions: ExtensionsRepository,
    private val attachments: AttachmentStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val chats = db.chats()
    private val _live = MutableStateFlow<Map<String, LiveRun>>(emptyMap())
    val live: StateFlow<Map<String, LiveRun>> = _live.asStateFlow()
    private val _deleted = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val deleted: SharedFlow<String> = _deleted.asSharedFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val alwaysAllowed = ConcurrentHashMap.newKeySet<String>()

    suspend fun createConversation(providerId: String?, modelId: String?, reasoning: String): ConversationEntity {
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(), title = NEW_TITLE, createdAt = now, updatedAt = now,
            providerId = providerId, modelId = modelId, reasoning = reasoning,
        )
        chats.upsertConversation(conversation)
        return conversation
    }

    fun isRunning(conversationId: String) = jobs.containsKey(conversationId)

    suspend fun send(conversationId: String, text: String, files: List<Part.File>) {
        val conversation = chats.conversation(conversationId) ?: return
        val parts = files + listOfNotNull(text.trim().takeIf { it.isNotEmpty() }?.let { Part.Text(it) })
        if (parts.isEmpty()) return
        val user = newMessage(conversation.id, conversation.activeLeafId, Role.USER, parts)
        chats.upsertMessage(user)
        if (conversation.title == NEW_TITLE) {
            val title = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(60)
                ?: files.firstOrNull()?.name ?: NEW_TITLE
            chats.updateConversation(conversation.copy(title = title, activeLeafId = user.id, updatedAt = now()))
        } else {
            chats.setActiveLeaf(conversation.id, user.id, now())
        }
        startAssistant(conversation.id, user.id)
    }

    /** Adds another answer under the same user message. */
    suspend fun regenerate(conversationId: String, assistantId: String) {
        val message = chats.message(assistantId) ?: return
        val parent = message.parentId ?: return
        startAssistant(conversationId, parent)
    }

    /** Edits a user message by creating a sibling version, then answers it. */
    suspend fun edit(conversationId: String, userId: String, text: String) {
        val original = chats.message(userId) ?: return
        val files = original.parts.filterIsInstance<Part.File>()
        val user = newMessage(conversationId, original.parentId, Role.USER, files + Part.Text(text.trim()))
        chats.upsertMessage(user)
        chats.setActiveLeaf(conversationId, user.id, now())
        startAssistant(conversationId, user.id)
    }

    fun stop(conversationId: String) {
        jobs[conversationId]?.cancel()
    }

    fun answerApproval(callId: String, toolName: String, approved: Boolean, always: Boolean) {
        if (approved && always) alwaysAllowed += toolName
        approvals.remove(callId)?.complete(approved)
    }

    private suspend fun startAssistant(conversationId: String, parentId: String) {
        stop(conversationId)
        jobs[conversationId]?.join()
        val conversation = chats.conversation(conversationId) ?: return
        val current = settings.current()
        val providerId = conversation.providerId ?: current.lastProviderId
        val modelId = conversation.modelId ?: current.lastModelId
        val assistant = newMessage(conversationId, parentId, Role.ASSISTANT, emptyList()).copy(
            status = MessageStatus.STREAMING, providerId = providerId, modelId = modelId,
        )
        chats.upsertMessage(assistant)
        chats.setActiveLeaf(conversationId, assistant.id, now())
        publish(LiveRun(conversationId, assistant.id, emptyList()))
        val job = scope.launch { run(conversation, assistant, current.systemPrompt) }
        jobs[conversationId] = job
        job.invokeOnCompletion {
            jobs.remove(conversationId, job)
            _live.update { live -> if (live[conversationId]?.messageId == assistant.id) live - conversationId else live }
        }
    }

    private suspend fun run(conversation: ConversationEntity, assistant: MessageEntity, defaultPrompt: String) {
        val parts = mutableListOf<Part>()
        var status = MessageStatus.COMPLETE
        var error: String? = null
        var inputTokens = 0L
        var outputTokens = 0L

        fun snapshot() = LiveRun(conversation.id, assistant.id, parts.toList())

        suspend fun persist(state: String) {
            chats.upsertMessage(
                assistant.copy(
                    partsJson = encodeParts(parts), plainText = parts.plainText(), status = state, error = error,
                    inputTokens = inputTokens, outputTokens = outputTokens,
                ),
            )
        }

        try {
            val providerId = assistant.providerId ?: throw NoModelException()
            val modelId = assistant.modelId ?: throw NoModelException()
            val provider = providers.provider(providerId) ?: throw NoModelException()
            val client = providers.client(provider)
            val all = chats.messages(conversation.id)
            val path = MessageTree.path(all, assistant.parentId)
            val history = History.build(path) { attachments.toContent(it) }
            val activeTools = tools.active()
            val skills = if (activeTools.any { it.name == "view_skill" }) extensions.enabledSkills() else emptyList()
            val lastUserText = path.lastOrNull()?.parts?.plainText().orEmpty()
            val mode = AgentMode.detect(lastUserText)
            val autonomous = AgentMode.isAutonomous(mode)
            val system = buildString {
                append(systemPrompt(conversation.systemPrompt ?: defaultPrompt, skills, lastUserText))
                AgentMode.instructions(mode)?.let { append("\n\n").append(it) }
            }
            val maxRounds = AgentMode.maxRounds(mode)

            for (round in 1..maxRounds) {
                val request = LlmRequest(
                    model = modelId,
                    system = system,
                    messages = history + History.assistantRounds(parts),
                    tools = activeTools.map { it.spec() },
                    reasoning = conversation.reasoning,
                )
                val calls = mutableListOf<Part.ToolCall>()
                var stopReason: String? = null
                var roundIn = 0L
                var roundOut = 0L
                var lastPublish = 0L
                var thinkingStartMs = 0L

                fun finishThinking() {
                    if (thinkingStartMs <= 0L) return
                    val elapsed = (System.currentTimeMillis() - thinkingStartMs).coerceAtLeast(0L)
                    thinkingStartMs = 0L
                    val index = parts.indexOfLast { it is Part.Thinking && it.redacted == null && it.durationMs == null }
                    if (index >= 0) {
                        val thinking = parts[index] as Part.Thinking
                        parts[index] = thinking.copy(durationMs = elapsed)
                    }
                }

                client.stream(request).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            finishThinking()
                            appendText(parts, event.text)
                        }
                        is StreamEvent.ThinkingDelta -> {
                            if (thinkingStartMs == 0L && event.text.isNotEmpty()) {
                                thinkingStartMs = System.currentTimeMillis()
                            }
                            appendThinking(parts, event.text)
                        }
                        is StreamEvent.ThinkingSignature -> signLastThinking(parts, event.signature)
                        is StreamEvent.RedactedThinking -> {
                            finishThinking()
                            parts += Part.Thinking("", redacted = event.data)
                        }
                        is StreamEvent.ToolCall -> {
                            finishThinking()
                            val call = Part.ToolCall(event.id, event.name, event.arguments, ToolStatus.RUNNING)
                            parts += call
                            calls += call
                        }
                        is StreamEvent.Usage -> { roundIn = event.inputTokens; roundOut = event.outputTokens }
                        is StreamEvent.Finished -> {
                            finishThinking()
                            stopReason = event.stopReason
                        }
                    }
                    val t = System.currentTimeMillis()
                    if (t - lastPublish > 50 || event is StreamEvent.ToolCall) {
                        lastPublish = t
                        publish(snapshot())
                    }
                }
                finishThinking()
                publish(snapshot())
                inputTokens += roundIn
                outputTokens += roundOut

                if (stopReason == "refusal") {
                    status = MessageStatus.ERROR
                    error = "The model declined to answer this request."
                    break
                }
                if (calls.isEmpty()) {
                    if (stopReason == "max_tokens" || stopReason == "length") error = "The answer was cut off because it hit the length limit."
                    break
                }
                persist(MessageStatus.STREAMING)
                for (call in calls) {
                    runTool(
                        call = call,
                        active = activeTools,
                        parts = parts,
                        autonomous = autonomous,
                        subagent = { sub ->
                            runSubagent(client, modelId, conversation.reasoning, activeTools, skills, autonomous, sub.arguments) { inTok, outTok ->
                                inputTokens += inTok
                                outputTokens += outTok
                            }
                        },
                        onChange = { publish(snapshot()) },
                    )
                }
                persist(MessageStatus.STREAMING)
                if (round == maxRounds) error = "Stopped after $maxRounds rounds of tool calls."
            }
        } catch (e: CancellationException) {
            status = MessageStatus.STOPPED
            withContext(NonCancellable) { finalizeTools(parts); persist(status) }
            throw e
        } catch (e: Exception) {
            status = MessageStatus.ERROR
            error = e.message ?: e.javaClass.simpleName
        }
        finalizeTools(parts)
        persist(status)
        chats.setActiveLeaf(conversation.id, assistant.id, now())
    }

    private suspend fun runTool(
        call: Part.ToolCall,
        active: List<AppTool>,
        parts: MutableList<Part>,
        autonomous: Boolean,
        subagent: suspend (Part.ToolCall) -> String,
        onChange: () -> Unit,
    ) {
        fun replace(updated: Part.ToolCall) {
            val index = parts.indexOfFirst { it is Part.ToolCall && it.id == call.id }
            if (index >= 0) parts[index] = updated
            onChange()
        }
        val tool = active.firstOrNull { it.name == call.name }
        if (tool == null) {
            replace(call.copy(status = ToolStatus.ERROR, result = "Unknown or disabled tool: ${call.name}", isError = true))
            return
        }
        val autoInMode = autonomous && tool.name in AgentMode.autoApproveTools
        if (!tool.autoApprove && tool.name !in alwaysAllowed && !autoInMode) {
            val decision = CompletableDeferred<Boolean>()
            approvals[call.id] = decision
            replace(call.copy(status = ToolStatus.AWAITING_APPROVAL))
            val approved = try { decision.await() } finally { approvals.remove(call.id) }
            if (!approved) {
                replace(call.copy(status = ToolStatus.REJECTED, result = "The user declined to run this tool.", isError = true))
                return
            }
            if (tool.name in alwaysAllowed) tools.setPref(tool, autoApprove = true)
        }
        replace(call.copy(status = ToolStatus.RUNNING))
        val outcome = if (tool.name == "spawn_subagent") {
            try {
                ToolOutcome(subagent(call), false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolOutcome(e.message ?: "The subagent failed.", true)
            }
        } else {
            tools.execute(tool, call.arguments)
        }
        replace(call.copy(status = if (outcome.isError) ToolStatus.ERROR else ToolStatus.DONE, result = outcome.text, isError = outcome.isError))
    }

    /** Runs an isolated, tool-using agent loop and returns its final answer as text. */
    private suspend fun runSubagent(
        client: LlmClient,
        modelId: String,
        reasoning: String,
        active: List<AppTool>,
        skills: List<SkillEntity>,
        autonomous: Boolean,
        rawArguments: String,
        onUsage: (Long, Long) -> Unit = { _, _ -> },
    ): String {
        val args = History.parseArgs(rawArguments)
        val task = args.string("task") ?: return "Error: spawn_subagent needs a task."
        val role = args.string("role")
        val maxRounds = (args["max_rounds"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 8) ?: 4
        val subagentTools = subagentToolsFor(active, autonomous, alwaysAllowed)
        val system = subagentSystemPrompt(role, skills, task)
        val history = mutableListOf(LlmMessage("user", listOf(Content.Text(task))))
        val parts = mutableListOf<Part>()
        var lastText = ""
        var usedRounds = 0
        var totalIn = 0L
        var totalOut = 0L

        try {
            for (round in 1..maxRounds) {
                usedRounds = round
                val calls = mutableListOf<Part.ToolCall>()
                val roundText = StringBuilder()
                var roundIn = 0L
                var roundOut = 0L
                try {
                    client.stream(
                        LlmRequest(
                            model = modelId,
                            system = system,
                            messages = history + History.assistantRounds(parts),
                            tools = subagentTools.map { it.spec() },
                            reasoning = reasoning,
                        ),
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.TextDelta -> {
                                appendText(parts, event.text)
                                roundText.append(event.text)
                            }
                            is StreamEvent.ThinkingDelta -> appendThinking(parts, event.text)
                            is StreamEvent.ThinkingSignature -> signLastThinking(parts, event.signature)
                            is StreamEvent.RedactedThinking -> parts += Part.Thinking("", redacted = event.data)
                            is StreamEvent.ToolCall -> {
                                val subCall = Part.ToolCall(event.id, event.name, event.arguments, ToolStatus.RUNNING)
                                parts += subCall
                                calls += subCall
                            }
                            is StreamEvent.Usage -> {
                                roundIn = event.inputTokens
                                roundOut = event.outputTokens
                            }
                            else -> Unit
                        }
                    }
                } finally {
                    totalIn += roundIn
                    totalOut += roundOut
                }
                lastText = roundText.toString().trim()
                if (calls.isEmpty()) break
                for (subCall in calls) {
                    val index = parts.indexOfFirst { it is Part.ToolCall && it.id == subCall.id }
                    val tool = subagentTools.firstOrNull { it.name == subCall.name }
                    val outcome = if (tool == null) {
                        ToolOutcome("Unknown tool ${subCall.name}.", true)
                    } else {
                        tools.execute(tool, subCall.arguments)
                    }
                    if (index >= 0) {
                        parts[index] = subCall.copy(
                            status = if (outcome.isError) ToolStatus.ERROR else ToolStatus.DONE,
                            result = outcome.text,
                            isError = outcome.isError,
                        )
                    }
                }
            }
        } finally {
            onUsage(totalIn, totalOut)
        }

        val finalText = lastText.ifBlank {
            parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
        }
        val plural = if (usedRounds == 1) "round" else "rounds"
        return "Subagent result ($usedRounds $plural):\n\n${finalText.ifBlank { "The subagent finished without a final answer." }}"
    }

    private fun finalizeTools(parts: MutableList<Part>) {
        for (i in parts.indices) {
            val part = parts[i]
            if (part is Part.ToolCall && (part.status == ToolStatus.RUNNING || part.status == ToolStatus.AWAITING_APPROVAL)) {
                parts[i] = part.copy(status = ToolStatus.ERROR, result = part.result ?: "Stopped before the tool finished.", isError = true)
            }
        }
    }

    private fun systemPrompt(base: String, skills: List<SkillEntity>, lastUserText: String): String = buildString {
        append(base.trim())
        append("\n\nToday's date is ${LocalDate.now()}.")
        appendSkillsSection(skills, lastUserText, mentionedPrefix = "The user invoked the skill")
    }

    private fun publish(run: LiveRun) = _live.update { it + (run.conversationId to run) }

    // ---- Conversation management ----

    suspend fun switchVersion(conversationId: String, messageId: String, delta: Int) {
        val all = chats.messages(conversationId)
        val message = all.firstOrNull { it.id == messageId } ?: return
        val leaf = MessageTree.switchSibling(all, message, delta) ?: return
        chats.setActiveLeaf(conversationId, leaf, now())
    }

    /** Copies the conversation up to and including [messageId] into a new conversation. */
    suspend fun branch(conversationId: String, messageId: String): String {
        val source = chats.conversation(conversationId) ?: error("Conversation not found")
        val path = MessageTree.path(chats.messages(conversationId), messageId)
        val ids = path.associate { it.id to UUID.randomUUID().toString() }
        val newId = UUID.randomUUID().toString()
        val copies = path.mapIndexed { index, message ->
            val parts = message.parts.map { part ->
                if (part is Part.File) copyAttachment(part) else part
            }
            message.copy(
                id = ids.getValue(message.id), conversationId = newId, parentId = message.parentId?.let(ids::get),
                partsJson = encodeParts(parts), createdAt = message.createdAt + index,
                status = if (message.status == MessageStatus.STREAMING) MessageStatus.STOPPED else message.status,
            )
        }
        chats.upsertConversation(
            source.copy(
                id = newId, title = "Branch · ${source.title}".take(80), pinned = false,
                activeLeafId = copies.lastOrNull()?.id, createdAt = now(), updatedAt = now(),
            ),
        )
        chats.insertMessages(copies)
        return newId
    }

    private fun copyAttachment(part: Part.File): Part.File {
        val source = attachments.file(part)
        val path = "${UUID.randomUUID()}.${part.path.substringAfterLast('.', "bin")}"
        val target = File(source.parentFile, path)
        runCatching { source.copyTo(target) }
        return part.copy(path = path)
    }

    suspend fun delete(conversationId: String) {
        stop(conversationId)
        chats.messages(conversationId).flatMap { it.parts }.filterIsInstance<Part.File>().forEach { attachments.delete(it) }
        chats.deleteConversationWithMessages(conversationId)
        _deleted.tryEmit(conversationId)
    }

    suspend fun exportMarkdown(conversationId: String): Pair<String, String> {
        val conversation = chats.conversation(conversationId) ?: return "" to ""
        val path = MessageTree.path(chats.messages(conversationId), conversation.activeLeafId)
        val text = buildString {
            append("# ${conversation.title}\n\n")
            for (message in path) {
                append(if (message.role == Role.USER) "## You\n\n" else "## Assistant${message.modelId?.let { " ($it)" } ?: ""}\n\n")
                for (part in message.parts) when (part) {
                    is Part.Text -> append(part.text.trim()).append("\n\n")
                    is Part.File -> append("> Attachment: ${part.name}\n\n")
                    is Part.ToolCall -> append("> Tool `${part.name}` ${part.arguments}\n\n")
                    is Part.Thinking -> Unit
                }
            }
        }
        return conversation.title to text
    }

    private fun newMessage(conversationId: String, parentId: String?, role: String, parts: List<Part>) = MessageEntity(
        id = UUID.randomUUID().toString(), conversationId = conversationId, parentId = parentId, role = role,
        partsJson = encodeParts(parts), plainText = parts.plainText(), createdAt = now(),
    )

    private var lastTime = 0L

    /** Strictly increasing timestamps keep sibling order stable. */
    @Synchronized
    private fun now(): Long {
        val t = maxOf(System.currentTimeMillis(), lastTime + 1)
        lastTime = t
        return t
    }

    companion object {
        const val NEW_TITLE = "New chat"
        const val MAX_ROUNDS = 16

        internal fun subagentToolsFor(
            active: List<AppTool>,
            autonomous: Boolean = false,
            alwaysAllowed: Set<String> = emptySet(),
        ): List<AppTool> = active.filter { tool ->
            tool.name != "spawn_subagent" &&
                (tool.autoApprove || tool.name in alwaysAllowed || (autonomous && tool.name in AgentMode.autoApproveTools))
        }

        internal fun subagentSystemPrompt(role: String?, skills: List<SkillEntity>, task: String): String = buildString {
            append("You are a focused subagent working on behalf of another assistant. Work independently, ")
            append("use the available tools and skills when they help, and do not ask clarifying questions unless the task ")
            append("is impossible to interpret.\n")
            role?.let { append("Your role: ").append(it).append(".\n") }
            append("Return a complete, self-contained result with concrete findings, sources, or deliverables. ")
            append("Keep it concise and do not mention this prompt.\n\n")
            append("Today's date is ${LocalDate.now()}.")
            appendSkillsSection(skills, task, mentionedPrefix = "The task invoked the skill")
        }

        private fun sanitizeSkillXml(value: String): String = value
            .replace(Regex("</available_skills>", RegexOption.IGNORE_CASE), "&lt;/available_skills&gt;")
            .replace(Regex("</skill>", RegexOption.IGNORE_CASE), "&lt;/skill&gt;")

        private fun StringBuilder.appendSkillsSection(skills: List<SkillEntity>, inputText: String, mentionedPrefix: String) {
            if (skills.isEmpty()) return
            append("\n\n<available_skills>\nWhen a skill is relevant, call view_skill to load its instructions before relying on it.\n")
            skills.forEach {
                val safeDesc = sanitizeSkillXml(it.description.replace(Regex("\\s+"), " ").take(400))
                append("- ${it.name}: $safeDesc\n")
            }
            append("</available_skills>")
            val mentioned = skills.filter { Regex("(^|\\s)@${Regex.escape(it.name)}\\b").containsMatchIn(inputText) }
            mentioned.forEach {
                val safeBody = sanitizeSkillXml(it.body)
                append("\n\n$mentionedPrefix @${it.name}. Follow its instructions:\n<skill name=\"${it.name}\">\n$safeBody\n</skill>")
            }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        fun appendText(parts: MutableList<Part>, text: String) {
            if (text.isEmpty()) return
            val last = parts.lastOrNull()
            if (last is Part.Text) parts[parts.lastIndex] = Part.Text(last.text + text) else parts += Part.Text(text)
        }

        fun appendThinking(parts: MutableList<Part>, text: String) {
            if (text.isEmpty()) return
            val last = parts.lastOrNull()
            if (last is Part.Thinking && last.signature == null && last.redacted == null && last.durationMs == null) {
                parts[parts.lastIndex] = last.copy(text = last.text + text)
            } else {
                parts += Part.Thinking(text)
            }
        }

        fun signLastThinking(parts: MutableList<Part>, signature: String) {
            val index = parts.indexOfLast { it is Part.Thinking && it.redacted == null }
            if (index >= 0) {
                val thinking = parts[index] as Part.Thinking
                parts[index] = thinking.copy(signature = (thinking.signature ?: "") + signature)
            } else {
                parts += Part.Thinking("", signature = signature)
            }
        }
    }
}
