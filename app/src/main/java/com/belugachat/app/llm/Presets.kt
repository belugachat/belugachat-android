package com.belugachat.app.llm

import com.belugachat.app.data.ProviderKind

data class ProviderPreset(
    val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val keyUrl: String?,
    val keyHint: String,
    val blurb: String,
)

object Presets {
    val all = listOf(
        ProviderPreset("deepseek", "DeepSeek", ProviderKind.OPENAI, "https://api.deepseek.com/v1",
            "https://platform.deepseek.com/api_keys", "sk-…", "DeepSeek chat and reasoner"),
        ProviderPreset("deploy", "Deploy.com", ProviderKind.OPENAI, "https://api-cdn.deploy.com/api/v1",
            "https://portal.deploy.com/?api_keys=1", "Deploy API key", "DeepSeek V4.1 Flash, GLM-5.3 Flash & fast open models"),
        ProviderPreset("opencode", "OpenCode Zen", ProviderKind.OPENAI, "https://opencode.ai/zen/v1",
            "https://opencode.ai/zen", "API key", "Curated models via OpenCode Zen"),
        ProviderPreset("anthropic", "Anthropic", ProviderKind.ANTHROPIC, "https://api.anthropic.com",
            "https://console.anthropic.com/settings/keys", "sk-ant-…", "Claude models"),
        ProviderPreset("openai", "OpenAI", ProviderKind.OPENAI, "https://api.openai.com/v1",
            "https://platform.openai.com/api-keys", "sk-…", "GPT models"),
        ProviderPreset("gemini", "Google Gemini", ProviderKind.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai",
            "https://aistudio.google.com/apikey", "AIza…", "Gemini models"),
        ProviderPreset("openrouter", "OpenRouter", ProviderKind.OPENAI, "https://openrouter.ai/api/v1",
            "https://openrouter.ai/keys", "sk-or-…", "Hundreds of models, one key"),
        ProviderPreset("xai", "xAI", ProviderKind.OPENAI, "https://api.x.ai/v1",
            "https://console.x.ai", "xai-…", "Grok models"),
        ProviderPreset("mistral", "Mistral", ProviderKind.OPENAI, "https://api.mistral.ai/v1",
            "https://console.mistral.ai/api-keys", "…", "Mistral and Codestral"),
        ProviderPreset("groq", "Groq", ProviderKind.OPENAI, "https://api.groq.com/openai/v1",
            "https://console.groq.com/keys", "gsk_…", "Fast open models"),
        ProviderPreset("zai", "Z.ai", ProviderKind.OPENAI, "https://api.z.ai/api/paas/v4",
            "https://z.ai/manage-apikey/apikey-list", "…", "GLM models"),
        ProviderPreset("custom", "Custom (OpenAI-compatible)", ProviderKind.OPENAI, "",
            null, "API key", "Ollama, LM Studio, vLLM, any /v1 endpoint"),
        ProviderPreset("custom_anthropic", "Custom (Anthropic-compatible)", ProviderKind.ANTHROPIC, "",
            null, "API key", "Any Messages API endpoint"),
    )

    fun byId(id: String): ProviderPreset = all.firstOrNull { it.id == id } ?: all.first { it.id == "custom" }

    /** For catalogs with hundreds of models, only these are switched on by default. */
    private val popular = listOf(
        "claude", "gpt-5", "gpt-4.1", "gpt-4o", "o3", "o4", "gemini-2", "gemini-3", "deepseek",
        "grok", "mistral-large", "llama-4", "qwen3", "glm",
    )

    fun defaultEnabled(modelId: String, catalogSize: Int): Boolean =
        catalogSize <= 40 || popular.any { modelId.lowercase().contains(it) }
}

fun clientFor(kind: String, preset: String, baseUrl: String, apiKey: String): LlmClient =
    if (kind == ProviderKind.ANTHROPIC) AnthropicClient(baseUrl, apiKey) else OpenAiClient(baseUrl, apiKey, preset)
