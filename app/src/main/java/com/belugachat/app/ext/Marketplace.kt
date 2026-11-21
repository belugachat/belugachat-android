package com.belugachat.app.ext

import com.belugachat.app.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

object MarketSource {
    const val PLUGINS = "plugins"
    const val MCP = "mcp_registry"
}

data class HeaderSpec(val name: String, val description: String, val required: Boolean, val secret: Boolean, val value: String?)

data class RemoteServer(val name: String, val url: String, val headers: Map<String, String>)

data class MarketItem(
    val source: String,
    /** "owner/repo" for plugins, "registry" for the MCP registry. */
    val marketplace: String,
    val id: String,
    val name: String,
    val description: String,
    val version: String?,
    val author: String?,
    val category: String?,
    val installable: Boolean,
    val note: String? = null,
    val headers: List<HeaderSpec> = emptyList(),
    val remoteUrl: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val trust: String = "community",
    val components: Map<String, Int> = emptyMap(),
    val readme: String? = null,
    internal val raw: JsonObject = JsonObject(emptyMap()),
) {
    val needsSignIn: Boolean get() = headers.any { it.required || it.secret }
    val lockId: String get() = "${source}:${marketplace}:${id}"
}

data class PluginBundle(val skills: List<ParsedSkill>, val servers: List<RemoteServer>, val skippedLocalServers: Int)

class MarketplaceException(message: String) : Exception(message)

private const val MAX_README_CHARS = 12_000
private val placeholderRegex = Regex("\\$\\{[A-Za-z0-9_]+\\}|\\$[A-Za-z0-9_]+|\\{[A-Za-z0-9_]+\\}")
private val secretHeaderRegex = Regex("auth|token|key|secret|password", RegexOption.IGNORE_CASE)

internal object MarketDetails {
    /** Extracts the auth fields a plugin's MCP servers expect, including placeholders. */
    fun headersFromConfigs(configs: List<JsonObject>): List<HeaderSpec> {
        val specs = linkedMapOf<String, HeaderSpec>()
        for (config in configs) {
            val headers = config["headers"] as? JsonObject ?: continue
            for ((name, element) in headers) {
                val value = (element as? JsonPrimitive)?.contentOrNull.orEmpty()
                val placeholder = placeholderRegex.containsMatchIn(value)
                val required = value.isBlank() || placeholder
                val secret = secretHeaderRegex.containsMatchIn(name)
                specs.putIfAbsent(
                    name,
                    HeaderSpec(
                        name = name,
                        description = "Required by this plugin's MCP server.",
                        required = required,
                        secret = secret,
                        value = if (required) null else value,
                    ),
                )
            }
        }
        return specs.values.toList()
    }

    fun components(files: List<RepoFile>, servers: Int, skills: Int): Map<String, Int> = buildMap {
        put("skills", skills)
        put("mcp_servers", servers)
        put("commands", files.count { it.path.startsWith("commands/") && it.path.endsWith(".md") })
        put("agents", files.count { it.path.startsWith("agents/") && it.path.endsWith(".md") })
    }

    fun readme(files: List<RepoFile>): String? {
        val file = files.firstOrNull {
            val name = it.path.substringAfterLast('/').lowercase()
            name == "readme.md" || name == "readme.rst" || name == "readme"
        } ?: return null
        return file.bytes.decodeToString().take(MAX_README_CHARS)
    }

    /** Keeps header values from the server config unless the user supplied a value. */
    fun mergeHeaders(configured: Map<String, String>, supplied: Map<String, String>): Map<String, String> =
        configured.mapNotNull { (name, value) ->
            val override = supplied[name]?.takeIf { it.isNotBlank() }
            val fallback = value.takeUnless { it.isBlank() || placeholderRegex.containsMatchIn(it) }
            (override ?: fallback)?.let { name to it }
        }.toMap()
}

class Marketplace(
    private val http: OkHttpClient = Http.client,
    private val rawBase: String = "https://raw.githubusercontent.com",
    private val codeloadBase: String = "https://codeload.github.com",
    private val registryBase: String = "https://registry.modelcontextprotocol.io/v0/servers",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).header("accept", "application/json").build()).execute().use { response ->
            if (!response.isSuccessful) throw MarketplaceException("HTTP ${response.code} from ${url.toHttpUrl().host}")
            return response.body?.string().orEmpty()
        }
    }

    private fun getText(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    /** Lists plugins from every Claude Code marketplace repo. Failing repos are reported in [errors]. */
    suspend fun plugins(repos: Collection<String>, errors: MutableList<String> = mutableListOf()): List<MarketItem> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                repos.map { repo ->
                    async {
                        runCatching { pluginsFrom(repo) }.getOrElse {
                            val reason = it.message ?: it.javaClass.simpleName
                            synchronized(errors) { errors += "$repo: $reason" }
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
        }

    private fun pluginsFrom(repo: String): List<MarketItem> {
        val text = get("${rawBase.trimEnd('/')}/$repo/HEAD/.claude-plugin/marketplace.json")
        val catalog = json.parseToJsonElement(text).jsonObject
        return catalog["plugins"]?.jsonArray.orEmpty().mapNotNull { element ->
            val plugin = element as? JsonObject ?: return@mapNotNull null
            val name = plugin.string("name") ?: return@mapNotNull null
            val location = pluginLocation(repo, plugin)
            val repository = location?.let { "https://github.com/${it.owner}/${it.repo}" }
            MarketItem(
                source = MarketSource.PLUGINS,
                marketplace = repo,
                id = name,
                name = name,
                description = plugin.string("description").orEmpty(),
                version = plugin.string("version"),
                author = author(plugin["author"]),
                category = plugin.string("category"),
                installable = location != null,
                note = if (location == null) "Source is not on GitHub" else null,
                homepage = urlOf(plugin["homepage"]) ?: urlOf(plugin["repository"]) ?: repository,
                repository = repository ?: urlOf(plugin["repository"]),
                trust = if ("official" in plugin.string("category").orEmpty().lowercase()) "official" else "community",
                components = mapOf("skills" to (plugin["skills"]?.jsonArray?.size ?: 0)),
                raw = plugin,
            )
        }
    }

    suspend fun mcpServers(query: String): List<MarketItem> = withContext(Dispatchers.IO) {
        val url = registryBase.toHttpUrl().newBuilder()
            .addQueryParameter("limit", "100")
            .apply { if (query.isNotBlank()) addQueryParameter("search", query.trim()) }
            .build()
        val root = json.parseToJsonElement(get(url.toString())).jsonObject
        val items = linkedMapOf<String, MarketItem>()
        root["servers"]?.jsonArray.orEmpty().forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val server = (obj["server"] as? JsonObject) ?: obj
            val id = server.string("name") ?: return@forEach
            val meta = (obj["_meta"] as? JsonObject)?.get("io.modelcontextprotocol.registry/official") as? JsonObject
            if (meta?.get("isLatest")?.jsonPrimitive?.booleanOrNull == false && id in items) return@forEach
            val remotes = server["remotes"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
            val remote = remotes.firstOrNull { it.string("type") == "streamable-http" && it.string("url") != null }
                ?: remotes.firstOrNull { it.string("url") != null }
            val headers = remote?.get("headers")?.jsonArray.orEmpty().mapNotNull { h ->
                val header = h as? JsonObject ?: return@mapNotNull null
                HeaderSpec(
                    name = header.string("name") ?: return@mapNotNull null,
                    description = header.string("description").orEmpty(),
                    required = header["isRequired"]?.jsonPrimitive?.booleanOrNull == true,
                    secret = header["isSecret"]?.jsonPrimitive?.booleanOrNull == true,
                    value = header.string("value"),
                )
            }
            val repository = urlOf(server["repository"]) ?: urlOf(server["repositoryUrl"])
            items[id] = MarketItem(
                source = MarketSource.MCP,
                marketplace = "registry",
                id = id,
                name = server.string("title") ?: id.substringAfterLast('/'),
                description = server.string("description").orEmpty(),
                version = server.string("version"),
                author = id.substringBefore('/', "").ifEmpty { null },
                category = server.string("category"),
                installable = remote != null,
                note = if (remote == null) "Runs as a local package; needs a computer" else null,
                headers = headers,
                remoteUrl = remote?.string("url"),
                homepage = urlOf(server["homepage"]) ?: repository,
                repository = repository,
                trust = "official registry",
                components = mapOf(
                    "remotes" to remotes.size,
                    "packages" to (server["packages"]?.jsonArray?.size ?: 0),
                ),
            )
        }
        rankMcpItems(items.values, query)
    }

    /** Loads README, component counts and sign-in fields for a marketplace item. */
    suspend fun detail(item: MarketItem): MarketItem = withContext(Dispatchers.IO) {
        when (item.source) {
            MarketSource.PLUGINS -> {
                val (_, files) = fetchPluginFiles(item)
                val skills = runCatching { SkillParser.parse(files) }.getOrDefault(emptyList())
                val configs = serverConfigs(files, item.raw)
                val remotes = remoteServers(configs)
                item.copy(
                    readme = MarketDetails.readme(files) ?: readmeFromRepo(item.repository),
                    headers = MarketDetails.headersFromConfigs(configs.map { it.second }),
                    remoteUrl = remotes.firstOrNull()?.url ?: item.remoteUrl,
                    components = MarketDetails.components(files, remotes.size, skills.size),
                    note = when {
                        item.note != null -> item.note
                        remotes.isEmpty() && configs.size > 0 -> "This plugin only has local (stdio) MCP servers, which need a computer."
                        else -> null
                    },
                )
            }
            MarketSource.MCP -> item.copy(readme = readmeFromRepo(item.repository).orEmpty().ifBlank { null })
            else -> item
        }
    }

    private data class Location(val owner: String, val repo: String, val ref: String, val path: String)

    private fun pluginLocation(marketRepo: String, plugin: JsonObject): Location? {
        val (mOwner, mRepo) = marketRepo.split('/').let { if (it.size < 2) return null else it[0] to it[1] }
        return when (val source = plugin["source"]) {
            is JsonPrimitive -> {
                val value = source.contentOrNull ?: return null
                if (value.startsWith("http") || value.startsWith("git@")) {
                    github(value)?.let { (o, r) -> Location(o, r, "HEAD", "") }
                } else {
                    Location(mOwner, mRepo, "HEAD", value.removePrefix("./").trim('/'))
                }
            }
            is JsonObject -> {
                val ref = source.string("sha") ?: source.string("ref") ?: "HEAD"
                val path = source.string("path").orEmpty().removePrefix("./").trim('/')
                val repo = source.string("repo")?.let { r -> r.split('/').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }
                    ?: source.string("url")?.let { github(it) }
                repo?.let { (o, r) -> Location(o, r, ref, path) }
            }
            else -> Location(mOwner, mRepo, "HEAD", "")
        }
    }

    private fun github(url: String): Pair<String, String>? {
        val cleaned = url.trim().replace("git@github.com:", "https://github.com/").removeSuffix(".git")
        val match = Regex("^https?://(www\\.)?github\\.com/([^/]+)/([^/#?]+)").find(cleaned) ?: return null
        return match.groupValues[2] to match.groupValues[3]
    }

    private fun fetchPluginFiles(item: MarketItem): Pair<Location, List<RepoFile>> {
        val location = pluginLocation(item.marketplace, item.raw)
            ?: throw MarketplaceException("This plugin's source isn't on GitHub, so it can't be installed here.")
        val prefix = if (location.path.isEmpty()) "" else location.path + "/"
        val files = downloadZip(location).mapNotNull { file ->
            val relative = file.path.substringAfter('/', "")
            if (relative.isEmpty() || !relative.startsWith(prefix)) {
                null
            } else {
                RepoFile(relative.removePrefix(prefix), file.bytes)
            }
        }
        return location to files
    }

    private fun downloadZip(location: Location): List<RepoFile> {
        val candidates = if (Regex("^[0-9a-f]{40}$").matches(location.ref)) {
            listOf("zip/${location.ref}")
        } else {
            listOf("zip/${location.ref}", "zip/refs/heads/${location.ref}", "zip/refs/tags/${location.ref}")
        }
        var status: Int? = null
        for (candidate in candidates.distinct()) {
            val url = "${codeloadBase.trimEnd('/')}/${location.owner}/${location.repo}/$candidate"
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    status = response.code
                } else {
                    return SkillParser.readZip(response.body!!.byteStream())
                }
            }
        }
        throw MarketplaceException("Could not download the plugin (HTTP $status).")
    }

    /** Downloads a plugin's source and extracts its skills and remote MCP servers. */
    suspend fun download(item: MarketItem): PluginBundle = withContext(Dispatchers.IO) {
        val (_, files) = fetchPluginFiles(item)
        val explicitSkills = item.raw["skills"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.removePrefix("./")?.trim('/') }
        val skillFiles = if (explicitSkills.isNullOrEmpty()) files else files.filter { f -> explicitSkills.any { f.path.startsWith("$it/") } }
        val skills = runCatching { SkillParser.parse(skillFiles) }.getOrDefault(emptyList())

        val configs = serverConfigs(files, item.raw)
        val remote = remoteServers(configs)
        val skipped = configs.size - remote.size
        if (skills.isEmpty() && remote.isEmpty()) {
            throw MarketplaceException(
                if (skipped > 0) "This plugin only has local (stdio) MCP servers, which need a computer."
                else "This plugin has no skills or remote MCP servers to install.",
            )
        }
        PluginBundle(skills, remote, skipped)
    }

    private fun serverConfigs(files: List<RepoFile>, raw: JsonObject): List<Pair<String, JsonObject>> {
        val configs = mutableListOf<Pair<String, JsonObject>>()
        fun collect(element: JsonElement?) {
            val obj = element as? JsonObject ?: return
            val servers = (obj["mcpServers"] as? JsonObject) ?: obj
            servers.forEach { (name, config) -> (config as? JsonObject)?.let { configs += name to it } }
        }
        files.firstOrNull { it.path == ".mcp.json" }?.let { collect(runCatching { json.parseToJsonElement(it.bytes.decodeToString()) }.getOrNull()) }
        files.firstOrNull { it.path == ".claude-plugin/plugin.json" }?.let { file ->
            val manifest = runCatching { json.parseToJsonElement(file.bytes.decodeToString()).jsonObject }.getOrNull()
            (manifest?.get("mcpServers") as? JsonObject)?.let { collect(it) }
        }
        (raw["mcpServers"] as? JsonObject)?.let { collect(it) }
        return configs
    }

    private fun remoteServers(configs: List<Pair<String, JsonObject>>): List<RemoteServer> =
        configs.mapNotNull { (name, config) ->
            val serverUrl = config.string("url") ?: return@mapNotNull null
            val headers = (config["headers"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
            RemoteServer(name, serverUrl, headers)
        }.distinctBy { it.url }

    private suspend fun readmeFromRepo(repository: String?): String? {
        val (owner, repo) = repository?.let(::github) ?: return null
        for (branch in listOf("HEAD", "main", "master")) {
            for (name in listOf("README.md", "readme.md", "README.MD", "README.rst", "README")) {
                val text = getText("${rawBase.trimEnd('/')}/$owner/$repo/$branch/$name")
                if (!text.isNullOrBlank()) return text.take(MAX_README_CHARS)
            }
        }
        return null
    }

    private fun author(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonObject -> value.string("name")
        else -> null
    }

    companion object {
        val featuredMcpServers: List<MarketItem> = listOf(
            MarketItem(
                source = MarketSource.MCP,
                marketplace = "registry",
                id = "ai.exa/exa",
                name = "Exa",
                description = "AI web search and clean page reading with a free keyless quota. Great upgrade for /deepsearch.",
                version = null,
                author = "Exa",
                category = "Search",
                installable = true,
                remoteUrl = "https://mcp.exa.ai/mcp",
                homepage = "https://exa.ai",
                repository = "https://github.com/exa-labs/exa-mcp-server",
                trust = "recommended · no key needed",
                components = mapOf("remotes" to 1),
            ),
            MarketItem(
                source = MarketSource.MCP,
                marketplace = "registry",
                id = "com.upstash/context7",
                name = "Context7",
                description = "Up-to-date official documentation and code examples for libraries and frameworks.",
                version = null,
                author = "Upstash",
                category = "Docs",
                installable = true,
                remoteUrl = "https://mcp.context7.com/mcp",
                homepage = "https://context7.com",
                repository = "https://github.com/upstash/context7",
                trust = "recommended · no key needed",
                components = mapOf("remotes" to 1),
            ),
            MarketItem(
                source = MarketSource.MCP,
                marketplace = "registry",
                id = "com.microsoft/learn",
                name = "Microsoft Learn",
                description = "Search official Microsoft, Azure, .NET, and developer documentation.",
                version = null,
                author = "Microsoft",
                category = "Docs",
                installable = true,
                remoteUrl = "https://learn.microsoft.com/api/mcp",
                homepage = "https://learn.microsoft.com",
                repository = "https://github.com/MicrosoftDocs/mcp",
                trust = "recommended · no key needed",
                components = mapOf("remotes" to 1),
            ),
            MarketItem(
                source = MarketSource.MCP,
                marketplace = "registry",
                id = "co.huggingface/hf-mcp-server",
                name = "Hugging Face",
                description = "Search AI models, datasets, Spaces, and ML research papers on Hugging Face.",
                version = null,
                author = "Hugging Face",
                category = "AI & ML",
                installable = true,
                remoteUrl = "https://huggingface.co/mcp",
                homepage = "https://huggingface.co/mcp",
                repository = "https://github.com/huggingface/hf-mcp-server",
                trust = "recommended · no key needed",
                components = mapOf("remotes" to 1),
            ),
        )

        internal fun rankMcpItems(items: Collection<MarketItem>, query: String): List<MarketItem> {
            val q = query.trim().lowercase()
            return items.sortedWith(
                compareByDescending<MarketItem> { it.installable }
                    .thenBy { item ->
                        if (q.isEmpty()) return@thenBy 0
                        val name = item.name.lowercase()
                        val shortId = item.id.substringAfterLast('/').lowercase()
                        val fullId = item.id.lowercase()
                        when {
                            name == q || shortId == q || fullId == q -> 0
                            name.startsWith(q) || shortId.startsWith(q) -> 1
                            name.contains(q) || shortId.contains(q) || fullId.contains(q) -> 2
                            else -> 3
                        }
                    }
                    .thenBy { it.name.lowercase() },
            )
        }
    }
}

private fun urlOf(value: JsonElement?): String? = when (value) {
    is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonObject -> value.string("url")?.takeIf { it.isNotBlank() }
    else -> null
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
