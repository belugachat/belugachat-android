package com.belugachat.app.data

import com.belugachat.app.llm.LlmClient
import com.belugachat.app.llm.Presets
import com.belugachat.app.llm.ProviderException
import com.belugachat.app.llm.RemoteModel
import com.belugachat.app.llm.clientFor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

internal fun isCleartextAllowedHost(url: String): Boolean {
    val host = url.toHttpUrlOrNull()?.host?.lowercase()?.trim('[', ']') ?: return false
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
    if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal") || host.endsWith(".home.arpa")) {
        return true
    }
    val parts = host.split('.')
    if (parts.size == 4) {
        val octets = parts.map { it.toIntOrNull() }
        if (octets.all { it != null && it in 0..255 }) {
            val a = octets[0]!!
            val b = octets[1]!!
            if (a == 10 || a == 127) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 100 && b in 64..127) return true
        }
    }
    return false
}

class ProviderRepository(private val db: AppDatabase, private val cipher: SecretCipher) {
    private val dao = db.providers()

    val providers = dao.observeProviders()
    val models = dao.observeModels()

    suspend fun provider(id: String) = dao.provider(id)

    fun apiKey(provider: ProviderEntity): String = runCatching { cipher.decrypt(provider.encryptedKey) }.getOrDefault("")

    fun maskedKey(provider: ProviderEntity): String = maskKey(apiKey(provider))

    fun client(provider: ProviderEntity): LlmClient =
        clientFor(provider.kind, provider.preset, provider.baseUrl, apiKey(provider))

    /**
     * Saves a provider after checking the key by listing its models.
     * With [verify] false the provider is saved even if the model list can't be fetched.
     */
    suspend fun save(
        id: String?,
        presetId: String,
        name: String,
        baseUrl: String,
        apiKey: String?,
        verify: Boolean = true,
    ): Pair<ProviderEntity, Int> {
        val preset = Presets.byId(presetId)
        val existing = id?.let { dao.provider(it) }
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.let { apiKey(it) } ?: ""
        val url = baseUrl.trim().ifEmpty { preset.baseUrl }.trimEnd('/')
        if (url.isEmpty()) throw ProviderException("Enter the base URL.")
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            throw ProviderException("The base URL must start with https:// or http://")
        }
        if (url.startsWith("http://", ignoreCase = true) && key.isNotEmpty() && !isCleartextAllowedHost(url)) {
            throw ProviderException("Use https:// when sending an API key to a remote host.")
        }
        val provider = ProviderEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { preset.name },
            kind = existing?.kind ?: preset.kind,
            preset = existing?.preset ?: preset.id,
            baseUrl = url,
            encryptedKey = cipher.encrypt(key),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            enabled = existing?.enabled ?: true,
        )
        val remote: List<RemoteModel>? = try {
            clientFor(provider.kind, provider.preset, provider.baseUrl, key).listModels()
        } catch (e: Exception) {
            if (verify) throw e
            null
        }
        dao.upsertProvider(provider)
        if (remote != null) storeModels(provider.id, remote)
        return provider to (remote?.size ?: 0)
    }

    private suspend fun storeModels(providerId: String, remote: List<RemoteModel>) {
        val previous = dao.models(providerId).associateBy { it.modelId }
        val models = remote.distinctBy { it.id }.mapIndexed { index, model ->
            ModelEntity(
                providerId = providerId,
                modelId = model.id,
                displayName = model.displayName,
                enabled = previous[model.id]?.enabled ?: Presets.defaultEnabled(model.id, remote.size),
                sort = index,
            )
        }
        // Keep manually added models that the endpoint doesn't list.
        val manual = previous.values.filter { prev -> models.none { it.modelId == prev.modelId } && prev.sort < 0 }
        dao.replaceModels(providerId, models + manual)
    }

    suspend fun refreshModels(providerId: String): Int {
        val provider = dao.provider(providerId) ?: return 0
        val remote = client(provider).listModels()
        storeModels(providerId, remote)
        return remote.size
    }

    suspend fun addModel(providerId: String, modelId: String) {
        val id = modelId.trim()
        if (id.isEmpty()) return
        dao.insertModels(listOf(ModelEntity(providerId, id, id, enabled = true, sort = -1)))
    }

    suspend fun setModelEnabled(model: ModelEntity, enabled: Boolean) =
        dao.setModelEnabled(model.providerId, model.modelId, enabled)

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) =
        dao.setProviderEnabled(providerId, enabled)

    suspend fun delete(providerId: String) {
        dao.deleteModels(providerId)
        dao.deleteProvider(providerId)
    }
}
