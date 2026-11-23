package com.belugachat.app.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.belugachat.app.BuildConfig
import com.belugachat.app.chat.AttachmentStore
import com.belugachat.app.chat.ChatEngine
import com.belugachat.app.chat.MessageTree
import com.belugachat.app.container
import com.belugachat.app.data.AppSettings
import com.belugachat.app.data.ConversationEntity
import com.belugachat.app.data.MessageEntity
import com.belugachat.app.data.MessageStatus
import com.belugachat.app.data.ModelEntity
import com.belugachat.app.data.Part
import com.belugachat.app.data.ProviderEntity
import com.belugachat.app.data.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ThreadItem(
    val message: MessageEntity,
    val parts: List<Part>,
    val siblings: MessageTree.Siblings,
    val streaming: Boolean,
)

data class ModelChoice(val provider: ProviderEntity, val model: ModelEntity)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val chats = c.db.chats()
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val conversationId = MutableStateFlow<String?>(null)
    val conversations = chats.observeConversations().state(emptyList())
    val settings = c.settings.settings.state(AppSettings())
    val providers = c.providers.providers.state(emptyList())
    val skills: StateFlow<List<SkillEntity>> = c.extensions.skills.map { list -> list.filter { it.enabled } }.state(emptyList())

    val models: StateFlow<List<ModelChoice>> = combine(c.providers.providers, c.providers.models) { providers, models ->
        val byId = providers.filter { it.enabled }.associateBy { it.id }
        models.filter { it.enabled }.mapNotNull { m -> byId[m.providerId]?.let { ModelChoice(it, m) } }
    }.state(emptyList())

    val conversation: StateFlow<ConversationEntity?> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else chats.observeConversation(id) }
        .state(null)

    private val messages = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chats.observeMessages(id) }

    val running: StateFlow<Boolean> = combine(conversationId, c.engine.live) { id, live -> id != null && live.containsKey(id) }.state(false)

    val thread: StateFlow<List<ThreadItem>> = combine(conversation, messages, c.engine.live) { conv, all, live ->
        if (conv == null) return@combine emptyList()
        val run = live[conv.id]
        MessageTree.path(all, conv.activeLeafId).map { message ->
            val streaming = run?.messageId == message.id
            ThreadItem(
                message = message,
                parts = if (streaming) run!!.parts else message.parts,
                siblings = MessageTree.siblings(all, message),
                streaming = streaming || (message.status == MessageStatus.STREAMING && run != null),
            )
        }
    }.state(emptyList())

    /** Model picked before the first message of a new chat. */
    private val pendingModel = MutableStateFlow<Pair<String, String>?>(null)
    private val pendingReasoning = MutableStateFlow<String?>(null)
    private val pendingPrompt = MutableStateFlow<String?>(null)

    val chatPrompt: StateFlow<String?> = combine(conversation, pendingPrompt) { conv, pending ->
        conv?.systemPrompt ?: pending
    }.state(null)

    val selectedModel: StateFlow<ModelChoice?> = combine(conversation, pendingModel, settings, models, providers) { conv, pending, s, models, providerList ->
        val key = conv?.providerId?.let { p -> conv.modelId?.let { p to it } } ?: pending
            ?: s.lastProviderId?.let { p -> s.lastModelId?.let { p to it } }
        models.firstOrNull { it.provider.id == key?.first && it.model.modelId == key.second }
            ?: key?.let { (p, m) -> providerList.firstOrNull { it.id == p && it.enabled }?.let { ModelChoice(it, ModelEntity(p, m, m, true, 0)) } }
            ?: models.firstOrNull()
    }.state(null)

    val reasoning: StateFlow<String> = combine(conversation, pendingReasoning, settings) { conv, pending, s ->
        conv?.reasoning ?: pending ?: s.reasoning
    }.state("default")

    val draft = MutableStateFlow("")
    val attachments = MutableStateFlow<List<Part.File>>(emptyList())
    val attaching = MutableStateFlow(0)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = _messages.asSharedFlow()
    private val drafts = mutableMapOf<String?, String>()
    private var draftEpoch = 0

    private val _searchResults = MutableStateFlow<List<ConversationEntity>?>(null)
    val searchResults: StateFlow<List<ConversationEntity>?> = _searchResults
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            c.engine.deleted.collect { id ->
                drafts.remove(id)
                if (conversationId.value == id) newChat()
            }
        }
        if (BuildConfig.ENABLE_SELF_UPDATE) {
            viewModelScope.launch { c.updater.checkAutomatically() }
        }
    }

    private fun notify(text: String) { _messages.tryEmit(text) }

    private fun launchSafe(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { notify(e.message ?: "Something went wrong.") }
    }

    fun open(id: String?) {
        draftEpoch++
        drafts[conversationId.value] = draft.value
        conversationId.value = id
        draft.value = drafts[id].orEmpty()
        val discarded = attachments.value
        attachments.value = emptyList()
        if (discarded.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) { discarded.forEach { c.attachments.delete(it) } }
        }
        pendingModel.value = null
        pendingReasoning.value = null
        pendingPrompt.value = null
    }

    fun newChat() = open(null)

    fun send() {
        val text = draft.value
        val files = attachments.value
        if (text.isBlank() && files.isEmpty()) return
        if (attaching.value > 0) { notify("Wait for attachments to finish."); return }
        val choice = selectedModel.value
        if (choice == null) { notify("Add a provider and API key first (Settings → Providers)."); return }
        draft.value = ""
        attachments.value = emptyList()
        launchSafe {
            var id = conversationId.value
            if (id == null) {
                val conv = c.engine.createConversation(
                    choice.provider.id,
                    choice.model.modelId,
                    reasoning.value,
                    systemPrompt = pendingPrompt.value,
                )
                pendingPrompt.value = null
                id = conv.id
                conversationId.value = id
            }
            c.engine.send(id, text, files)
        }
    }

    fun stop() { conversationId.value?.let(c.engine::stop) }

    fun regenerate(messageId: String) = withConversation { c.engine.regenerate(it, messageId) }

    fun edit(messageId: String, text: String) {
        if (text.isBlank()) return
        withConversation { c.engine.edit(it, messageId, text) }
    }

    fun switchVersion(messageId: String, delta: Int) = withConversation { c.engine.switchVersion(it, messageId, delta) }

    fun branch(messageId: String) = withConversation {
        val newId = c.engine.branch(it, messageId)
        open(newId)
        notify("Opened a branch of this chat.")
    }

    fun approve(call: Part.ToolCall, approved: Boolean, always: Boolean = false) =
        c.engine.answerApproval(call.id, call.name, approved, always)

    private fun withConversation(block: suspend (String) -> Unit) {
        val id = conversationId.value ?: return
        launchSafe { block(id) }
    }

    fun selectModel(choice: ModelChoice) = launchSafe {
        c.settings.setLastModel(choice.provider.id, choice.model.modelId)
        val conv = conversation.value
        if (conv != null) chats.updateConversation(conv.copy(providerId = choice.provider.id, modelId = choice.model.modelId))
        else pendingModel.value = choice.provider.id to choice.model.modelId
    }

    fun toggleDeepSeekReasoning(): Boolean {
        val current = selectedModel.value ?: return false
        if (current.provider.preset != "deepseek") return false
        val sameProvider = models.value.filter { it.provider.id == current.provider.id }
        val isReasoner = current.model.modelId.contains("reasoner", ignoreCase = true)
        val target = if (isReasoner) {
            sameProvider.firstOrNull { it.model.modelId.equals("deepseek-chat", ignoreCase = true) }
                ?: sameProvider.firstOrNull { !it.model.modelId.contains("reasoner", ignoreCase = true) }
        } else {
            sameProvider.firstOrNull { it.model.modelId.equals("deepseek-reasoner", ignoreCase = true) }
                ?: sameProvider.firstOrNull { it.model.modelId.contains("reasoner", ignoreCase = true) }
        } ?: return false
        selectModel(target)
        return true
    }

    fun setReasoning(level: String) = launchSafe {
        c.settings.setReasoning(level)
        val conv = conversation.value
        if (conv != null) chats.updateConversation(conv.copy(reasoning = level)) else pendingReasoning.value = level
    }

    fun attach(uris: List<Uri>) {
        val epoch = draftEpoch
        uris.forEach { uri ->
            attaching.value += 1
            viewModelScope.launch {
                try {
                    val part = c.attachments.import(uri)
                    if (epoch != draftEpoch) {
                        viewModelScope.launch(Dispatchers.IO) { c.attachments.delete(part) }
                    } else {
                        attachments.value = attachments.value + part
                        val kind = AttachmentStore.kindOf(part.mime, part.name)
                        if (selectedModel.value?.provider?.preset == "deepseek" && kind == AttachmentStore.Kind.IMAGE) {
                            notify("DeepSeek API models currently accept text and documents rather than images.")
                        }
                    }
                } catch (e: Exception) {
                    notify(e.message ?: "Could not attach the file.")
                } finally {
                    attaching.value -= 1
                }
            }
        }
    }

    fun removeAttachment(part: Part.File) {
        attachments.value = attachments.value - part
        viewModelScope.launch(Dispatchers.IO) { c.attachments.delete(part) }
    }

    fun rename(conv: ConversationEntity, title: String) = launchSafe {
        if (title.isNotBlank()) chats.updateConversation(conv.copy(title = title.trim()))
    }

    fun togglePin(conv: ConversationEntity) = launchSafe { chats.updateConversation(conv.copy(pinned = !conv.pinned)) }

    fun delete(conv: ConversationEntity) = launchSafe {
        if (conversationId.value == conv.id) newChat()
        c.engine.delete(conv.id)
    }

    fun setChatPrompt(prompt: String?) = launchSafe {
        val cleaned = prompt?.takeIf { it.isNotBlank() }
        val conv = conversation.value
        if (conv != null) chats.updateConversation(conv.copy(systemPrompt = cleaned))
        else pendingPrompt.value = cleaned
    }

    suspend fun exportMarkdown(): Pair<String, String>? = conversationId.value?.let { c.engine.exportMarkdown(it) }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { _searchResults.value = null; return }
        searchJob = viewModelScope.launch {
            delay(200)
            _searchResults.value = chats.search(query.trim())
        }
    }

    fun isNewTitle(conv: ConversationEntity?) = conv == null || conv.title == ChatEngine.NEW_TITLE

    override fun onCleared() {
        val leftover = attachments.value
        if (leftover.isNotEmpty()) {
            c.scope.launch(Dispatchers.IO) { leftover.forEach { c.attachments.delete(it) } }
        }
        super.onCleared()
    }
}
