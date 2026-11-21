package com.belugachat.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

const val DEFAULT_SYSTEM_PROMPT = """You are Beluga Chat, my personal AI assistant.
Help me think, build, research, and get things done.

Priorities:
- Answer directly. Skip filler, disclaimers, and restating my question.
- Be accurate. Say when you are unsure or lack information; never invent facts, sources, or numbers.
- Prefer concrete, actionable answers. Ask a clarifying question only when a wrong guess would be costly.
- Match my level of detail. Default to concise; expand when I ask or when the topic requires it.
- For code, give runnable examples and mention important assumptions or tradeoffs.
- Use tools when they materially improve the answer. Explain results briefly; do not dump raw data.
- Respect privacy. Do not repeat secrets or personal data unless necessary for the task.

Formatting:
- Use markdown for structure, short paragraphs, and lists when they help.
- Put code in fenced blocks with a language tag.
- Do not use emojis unless I ask."""

val DEFAULT_MARKETPLACES = setOf("anthropics/claude-plugins-official", "anthropics/skills")

data class AppSettings(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val lastProviderId: String? = null,
    val lastModelId: String? = null,
    val reasoning: String = Reasoning.DEFAULT,
    val theme: String = "system",
    val marketplaces: Set<String> = DEFAULT_MARKETPLACES,
    val lastUpdateCheckMs: Long = 0L,
    val dismissedUpdateVersion: String? = null,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val prompt = stringPreferencesKey("system_prompt")
        val provider = stringPreferencesKey("last_provider")
        val model = stringPreferencesKey("last_model")
        val reasoning = stringPreferencesKey("reasoning")
        val theme = stringPreferencesKey("theme")
        val marketplaces = stringSetPreferencesKey("marketplaces")
        val lastUpdateCheckMs = longPreferencesKey("last_update_check_ms")
        val dismissedUpdateVersion = stringPreferencesKey("dismissed_update_version")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        systemPrompt = this[Keys.prompt] ?: DEFAULT_SYSTEM_PROMPT,
        lastProviderId = this[Keys.provider],
        lastModelId = this[Keys.model],
        reasoning = this[Keys.reasoning] ?: Reasoning.DEFAULT,
        theme = this[Keys.theme] ?: "system",
        marketplaces = this[Keys.marketplaces] ?: DEFAULT_MARKETPLACES,
        lastUpdateCheckMs = this[Keys.lastUpdateCheckMs] ?: 0L,
        dismissedUpdateVersion = this[Keys.dismissedUpdateVersion],
    )

    suspend fun setSystemPrompt(value: String) = context.dataStore.edit { it[Keys.prompt] = value }
    suspend fun setTheme(value: String) = context.dataStore.edit { it[Keys.theme] = value }
    suspend fun setReasoning(value: String) = context.dataStore.edit { it[Keys.reasoning] = value }
    suspend fun setMarketplaces(value: Set<String>) = context.dataStore.edit { it[Keys.marketplaces] = value }
    suspend fun setLastUpdateCheckMs(ms: Long) = context.dataStore.edit { it[Keys.lastUpdateCheckMs] = ms }
    suspend fun setDismissedUpdateVersion(version: String?) = context.dataStore.edit {
        if (version == null) it.remove(Keys.dismissedUpdateVersion) else it[Keys.dismissedUpdateVersion] = version
    }

    suspend fun setLastModel(providerId: String, modelId: String) = context.dataStore.edit {
        it[Keys.provider] = providerId
        it[Keys.model] = modelId
    }
}
