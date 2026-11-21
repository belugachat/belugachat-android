package com.belugachat.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.belugachat.app.container
import com.belugachat.app.data.AppSettings
import com.belugachat.app.data.DEFAULT_SYSTEM_PROMPT
import com.belugachat.app.data.McpServerEntity
import com.belugachat.app.data.ModelEntity
import com.belugachat.app.data.ProviderEntity
import com.belugachat.app.data.SkillEntity
import com.belugachat.app.ext.ExtensionsRepository
import com.belugachat.app.ext.MarketItem
import com.belugachat.app.ext.McpTool
import com.belugachat.app.ext.headersToText
import com.belugachat.app.ext.parseHeaders
import com.belugachat.app.tools.AppTool
import com.belugachat.app.update.UpdateRelease
import com.belugachat.app.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class ManageViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val settings = c.settings.settings.state(AppSettings())
    val providers = c.providers.providers.state(emptyList())
    val models = c.providers.models.state(emptyList())
    val tools = c.tools.observe().state(emptyList())
    val servers = c.extensions.servers.state(emptyList())
    val skills = c.extensions.skills.state(emptyList())
    val installed = c.extensions.installed.state(emptyList())
    val conversations = c.db.chats().observeConversations().state(emptyList())

    val busy = MutableStateFlow(false)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()
    fun notify(text: String) { _notices.tryEmit(text) }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try { block() } catch (e: Exception) { notify(e.message ?: "Something went wrong.") } finally { busy.value = false }
    }

    // ---- Settings ----
    fun savePrompt(text: String) = work { c.settings.setSystemPrompt(text.ifBlank { DEFAULT_SYSTEM_PROMPT }); notify("Default prompt saved.") }
    fun resetPrompt() = work { c.settings.setSystemPrompt(DEFAULT_SYSTEM_PROMPT) }
    fun setTheme(mode: String) = work { c.settings.setTheme(mode) }

    // ---- Providers ----
    fun maskedKey(provider: ProviderEntity) = c.providers.maskedKey(provider)

    fun saveProvider(id: String?, preset: String, name: String, baseUrl: String, key: String?, verify: Boolean, onSaved: (String) -> Unit) = work {
        val (provider, count) = c.providers.save(id, preset, name, baseUrl, key, verify)
        notify(if (count > 0) "Connected · $count models found." else "Saved.")
        onSaved(provider.id)
    }

    fun refreshModels(id: String) = work { notify("${c.providers.refreshModels(id)} models found.") }
    fun toggleModel(model: ModelEntity, enabled: Boolean) = work { c.providers.setModelEnabled(model, enabled) }
    fun setAllModels(models: List<ModelEntity>, enabled: Boolean) = work { models.forEach { c.providers.setModelEnabled(it, enabled) } }
    fun addModel(providerId: String, modelId: String) = work { c.providers.addModel(providerId, modelId) }
    fun deleteProvider(id: String, then: () -> Unit) = work { c.providers.delete(id); then() }

    // ---- Tools ----
    fun setTool(tool: AppTool, enabled: Boolean = tool.enabled, autoApprove: Boolean = tool.autoApprove) =
        work { c.tools.setPref(tool, enabled, autoApprove) }

    // ---- MCP ----
    fun serverTools(server: McpServerEntity): List<McpTool> = c.extensions.tools(server)
    fun serverHeaders(server: McpServerEntity): String = headersToText(c.extensions.headers(server))

    fun saveServer(id: String?, name: String, url: String, headers: String) = work {
        if (name.isBlank() || !url.startsWith("http")) throw IllegalArgumentException("Enter a name and an http(s) URL.")
        val server = c.extensions.saveServer(id, name, url, parseHeaders(headers))
        val updated = c.extensions.refresh(server.id)
        notify(if (updated.status == "connected") "Connected · ${serverTools(updated).size} tools." else "Couldn't connect: ${updated.lastError}")
    }

    fun refreshServer(server: McpServerEntity) = work {
        val updated = c.extensions.refresh(server.id)
        notify(if (updated.status == "connected") "${serverTools(updated).size} tools available." else "Couldn't connect: ${updated.lastError}")
    }

    fun toggleServer(server: McpServerEntity, enabled: Boolean) = work {
        c.extensions.setServerEnabled(server, enabled)
        if (enabled && server.status != "connected") c.extensions.refresh(server.id)
    }

    fun deleteServer(server: McpServerEntity) = work { c.extensions.deleteServer(server.id) }

    // ---- Skills ----
    fun importSkill(uri: Uri) = work {
        val imported = c.extensions.importSkill(uri)
        notify("Imported ${imported.joinToString { it.name }}.")
    }

    fun toggleSkill(skill: SkillEntity, enabled: Boolean) = work { c.extensions.setSkillEnabled(skill, enabled) }
    fun deleteSkill(skill: SkillEntity) = work { c.extensions.deleteSkill(skill) }
    fun skillFiles(skill: SkillEntity) = c.extensions.skillFiles(skill)

    // ---- Marketplace ----
    val plugins = MutableStateFlow<List<MarketItem>?>(null)
    val pluginErrors = MutableStateFlow<List<String>>(emptyList())
    val mcpItems = MutableStateFlow<List<MarketItem>?>(null)
    val marketLoading = MutableStateFlow(false)
    val installing = MutableStateFlow<String?>(null)
    val detailItem = MutableStateFlow<MarketItem?>(null)
    val detailLoading = MutableStateFlow(false)
    private var detailToken = 0

    fun loadPlugins(force: Boolean = false) {
        if (plugins.value != null && pluginErrors.value.isEmpty() && !force) return
        viewModelScope.launch {
            marketLoading.value = true
            try {
                val errors = mutableListOf<String>()
                val repos = c.settings.current().marketplaces
                plugins.value = c.extensions.marketplace.plugins(repos, errors)
                pluginErrors.value = errors
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Could not load the marketplace.")
                if (plugins.value == null) plugins.value = emptyList()
            } finally {
                marketLoading.value = false
            }
        }
    }

    fun searchMcp(query: String) {
        viewModelScope.launch {
            marketLoading.value = true
            try { mcpItems.value = c.extensions.marketplace.mcpServers(query) } catch (e: Exception) { notify(e.message ?: "Registry unavailable."); mcpItems.value = emptyList() }
            marketLoading.value = false
        }
    }

    fun openDetail(item: MarketItem) {
        val token = ++detailToken
        detailItem.value = item
        detailLoading.value = true
        viewModelScope.launch {
            try {
                val detailed = c.extensions.marketplace.detail(item)
                if (token == detailToken) detailItem.value = detailed
            } catch (e: Exception) {
                if (token == detailToken) notify(e.message ?: "Could not load details.")
            } finally {
                if (token == detailToken) detailLoading.value = false
            }
        }
    }

    fun closeDetail() {
        detailToken++
        detailItem.value = null
        detailLoading.value = false
    }

    fun install(item: MarketItem, headers: Map<String, String> = emptyMap(), onDone: () -> Unit = {}) {
        viewModelScope.launch {
            installing.value = item.marketplace + item.id
            try {
                val result = c.extensions.install(item, headers)
                notifyInstallResult(item, result)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Install failed.")
            } finally {
                installing.value = null
            }
        }
    }

    fun quickInstallPlugin(item: MarketItem, onNeedsSignIn: () -> Unit) {
        viewModelScope.launch {
            installing.value = item.marketplace + item.id
            try {
                val detailed = c.extensions.marketplace.detail(item)
                if (detailed.needsSignIn || detailed.headers.any { it.required }) {
                    detailToken++
                    detailItem.value = detailed
                    detailLoading.value = false
                    installing.value = null
                    onNeedsSignIn()
                    return@launch
                }
                val result = c.extensions.install(detailed, emptyMap())
                notifyInstallResult(item, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Install failed.")
            } finally {
                installing.value = null
            }
        }
    }

    private fun notifyInstallResult(item: MarketItem, result: ExtensionsRepository.InstallResult) {
        val parts = buildList {
            if (result.skills > 0) add("${result.skills} skill${if (result.skills == 1) "" else "s"}")
            if (result.servers > 0) add("${result.servers} MCP server${if (result.servers == 1) "" else "s"}")
        }
        val extra = buildString {
            if (result.skippedLocal > 0) append(" ${result.skippedLocal} local server(s) skipped.")
            if (item.source == "plugins" && result.servers > 0) append(" Turn servers on in Settings → MCP servers.")
        }
        notify("Installed ${item.name}: ${parts.joinToString(" and ")}.$extra")
    }

    fun uninstall(lockId: String) = work { c.extensions.uninstall(lockId); notify("Removed.") }

    fun addMarketplace(repo: String) = work {
        val cleaned = repo.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(cleaned)) throw IllegalArgumentException("Use the owner/repo form, e.g. anthropics/skills")
        c.settings.setMarketplaces(c.settings.current().marketplaces + cleaned)
        plugins.value = null
        loadPlugins(force = true)
    }

    fun removeMarketplace(repo: String) = work {
        c.settings.setMarketplaces(c.settings.current().marketplaces - repo)
        loadPlugins(force = true)
    }

    // ---- Chats ----
    fun deleteChats(ids: Set<String>) = work {
        ids.forEach { c.engine.delete(it) }
        notify("Deleted ${ids.size} chat${if (ids.size == 1) "" else "s"}.")
    }

    // ---- Updates ----
    val updateState: StateFlow<UpdateState> = c.updater.state

    fun checkForUpdates() = viewModelScope.launch { c.updater.checkManually() }
    fun downloadUpdate(release: UpdateRelease) = c.updater.startDownload(release, viewModelScope)
    fun cancelUpdateDownload() = c.updater.cancelDownload()
    fun dismissUpdate(rememberVersion: Boolean) = viewModelScope.launch { c.updater.dismiss(rememberVersion) }
    fun canInstallUpdates(): Boolean = c.updater.canInstallPackages()
    fun openInstallPermissionSettings() = c.updater.openInstallPermissionSettings()
    fun installUpdate(apkFile: File) = c.updater.installApk(apkFile)

    // ---- Analytics ----
    val analytics = MutableStateFlow<AnalyticsSnapshot?>(null)
    val analyticsLoading = MutableStateFlow(false)
    private var analyticsJob: Job? = null

    fun loadAnalytics(period: AnalyticsPeriod = analytics.value?.period ?: AnalyticsPeriod.MONTH) {
        analyticsJob?.cancel()
        analyticsJob = viewModelScope.launch {
            analyticsLoading.value = true
            try {
                val dao = c.db.chats()
                val now = System.currentTimeMillis() + 1
                val day = 24L * 3600 * 1000
                val from = period.days?.let { now - it * day } ?: 0L
                val previous = period.days?.let { days ->
                    dao.analyticsTotals((from - days * day).coerceAtLeast(0L), from)
                }
                analytics.value = AnalyticsSnapshot(
                    period = period,
                    totals = dao.analyticsTotals(from, now),
                    previous = previous,
                    models = dao.analyticsByModel(from, now),
                    daily = dao.analyticsDaily(from, now),
                    hourly = dao.analyticsHourly(from, now),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Could not load analytics.")
            } finally {
                analyticsLoading.value = false
            }
        }
    }
}
