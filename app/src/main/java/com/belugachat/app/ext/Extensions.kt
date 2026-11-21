package com.belugachat.app.ext

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.belugachat.app.data.AppDatabase
import com.belugachat.app.data.InstalledItemEntity
import com.belugachat.app.data.McpServerEntity
import com.belugachat.app.data.SecretCipher
import com.belugachat.app.data.SkillEntity
import com.belugachat.app.data.isCleartextAllowedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** MCP servers, skills and marketplace installs. */
class ExtensionsRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val cipher: SecretCipher,
    val connections: McpConnections,
    val marketplace: Marketplace = Marketplace(),
) {
    private val dao = db.extensions()
    private val json = Json { ignoreUnknownKeys = true }
    private val headerSerializer = MapSerializer(String.serializer(), String.serializer())
    private val toolListSerializer = ListSerializer(McpTool.serializer())

    val servers = dao.observeServers()
    val skills = dao.observeSkills()
    val installed = dao.observeInstalled()

    // ---- MCP ----

    fun headers(server: McpServerEntity): Map<String, String> =
        server.encryptedHeaders?.let { runCatching { json.decodeFromString(headerSerializer, cipher.decrypt(it)) }.getOrNull() }.orEmpty()

    fun tools(server: McpServerEntity): List<McpTool> =
        runCatching { json.decodeFromString(toolListSerializer, server.toolsJson) }.getOrDefault(emptyList())

    fun client(server: McpServerEntity): McpClient = connections.client(server.id, server.url, headers(server))

    suspend fun saveServer(id: String?, name: String, url: String, headers: Map<String, String>, enabled: Boolean = true, installedItemId: String? = null): McpServerEntity {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://", ignoreCase = true) && !cleanUrl.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("The server URL must start with https:// or http://")
        }
        if (cleanUrl.startsWith("http://", ignoreCase = true) && headers.isNotEmpty() && !isCleartextAllowedHost(cleanUrl)) {
            throw IllegalArgumentException("Use https:// when sending headers to a remote MCP server.")
        }
        val existing = id?.let { dao.server(it) }
        val server = McpServerEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            url = cleanUrl,
            encryptedHeaders = if (headers.isEmpty()) null else cipher.encrypt(json.encodeToString(headerSerializer, headers)),
            enabled = enabled,
            status = existing?.status ?: "new",
            toolsJson = existing?.toolsJson ?: "[]",
            installedItemId = installedItemId ?: existing?.installedItemId,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        connections.forget(server.id)
        dao.upsertServer(server)
        return server
    }

    /** Connects, lists tools and caches them. Returns the updated server. */
    suspend fun refresh(serverId: String): McpServerEntity {
        val server = dao.server(serverId) ?: error("Server not found")
        connections.forget(serverId)
        val updated = try {
            val tools = client(server).listTools()
            server.copy(status = "connected", lastError = null, toolsJson = json.encodeToString(toolListSerializer, tools))
        } catch (e: Exception) {
            server.copy(status = "error", lastError = e.message ?: e.javaClass.simpleName)
        }
        dao.upsertServer(updated)
        return updated
    }

    suspend fun setServerEnabled(server: McpServerEntity, enabled: Boolean) = dao.upsertServer(server.copy(enabled = enabled))

    suspend fun deleteServer(id: String) {
        connections.forget(id)
        dao.deleteServer(id)
    }

    // ---- Skills ----

    private fun skillDir(id: String) = File(context.filesDir, "skills/$id")

    suspend fun saveSkills(parsed: List<ParsedSkill>, source: String, installedItemId: String? = null): List<SkillEntity> =
        withContext(Dispatchers.IO) {
            parsed.map { skill ->
                val existing = dao.skillByName(skill.name)
                val id = existing?.id ?: UUID.randomUUID().toString()
                val dir = skillDir(id)
                dir.deleteRecursively()
                val stored = skill.files.filter { SkillParser.isText(it.path) }
                stored.forEach { file ->
                    val target = File(dir, file.path)
                    if (!target.canonicalPath.startsWith(dir.canonicalPath)) return@forEach
                    target.parentFile?.mkdirs()
                    target.writeBytes(file.bytes)
                }
                val entity = SkillEntity(
                    id = id,
                    name = skill.name,
                    description = skill.description,
                    body = skill.body,
                    filesJson = json.encodeToString(ListSerializer(String.serializer()), stored.map { it.path }),
                    enabled = existing?.enabled ?: true,
                    source = source,
                    installedItemId = installedItemId ?: existing?.installedItemId,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                )
                dao.upsertSkill(entity)
                entity
            }
        }

    /** Imports a SKILL.md or a .zip containing one or more skills. */
    suspend fun importSkill(uri: Uri): List<SkillEntity> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty()
        val mime = resolver.getType(uri).orEmpty()
        val input = resolver.openInputStream(uri) ?: throw SkillImportException("Could not open the file.")
        val files = input.use { stream ->
            if (name.endsWith(".zip", true) || name.endsWith(".skill", true) || mime.contains("zip")) {
                SkillParser.stripTopFolder(SkillParser.readZip(stream))
            } else {
                listOf(RepoFile("SKILL.md", stream.readBytes()))
            }
        }
        saveSkills(SkillParser.parse(files), "import")
    }

    fun skillFiles(skill: SkillEntity): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), skill.filesJson) }.getOrDefault(emptyList())

    fun readSkillFile(skill: SkillEntity, path: String): String? {
        val dir = skillDir(skill.id)
        val file = File(dir, path)
        if (!file.canonicalPath.startsWith(dir.canonicalPath) || !file.isFile) return null
        return file.readText().take(60_000)
    }

    suspend fun setSkillEnabled(skill: SkillEntity, enabled: Boolean) = dao.upsertSkill(skill.copy(enabled = enabled))

    suspend fun deleteSkill(skill: SkillEntity) {
        withContext(Dispatchers.IO) { skillDir(skill.id).deleteRecursively() }
        dao.deleteSkill(skill.id)
    }

    suspend fun enabledSkills(): List<SkillEntity> = dao.skills().filter { it.enabled }

    // ---- Marketplace ----

    data class InstallResult(val skills: Int, val servers: Int, val skippedLocal: Int)

    suspend fun install(item: MarketItem, headerValues: Map<String, String> = emptyMap()): InstallResult {
        val lockId = "${item.source}:${item.marketplace}:${item.id}"
        return when (item.source) {
            MarketSource.PLUGINS -> {
                val bundle = marketplace.download(item)
                removeInstalled(lockId)
                dao.insertInstalled(InstalledItemEntity(lockId, item.source, item.id, item.marketplace, item.name, item.version, System.currentTimeMillis()))
                val skills = saveSkills(bundle.skills, "marketplace", lockId)
                bundle.servers.forEach { server ->
                    saveServer(
                        null,
                        server.name,
                        server.url,
                        MarketDetails.mergeHeaders(server.headers, headerValues),
                        enabled = false,
                        installedItemId = lockId,
                    )
                }
                InstallResult(skills.size, bundle.servers.size, bundle.skippedLocalServers)
            }
            else -> {
                val url = item.remoteUrl ?: throw MarketplaceException("This server has no remote endpoint.")
                removeInstalled(lockId)
                dao.insertInstalled(InstalledItemEntity(lockId, item.source, item.id, item.marketplace, item.name, item.version, System.currentTimeMillis()))
                val headers = item.headers.mapNotNull { spec ->
                    val value = headerValues[spec.name]?.takeIf { it.isNotBlank() }
                        ?: spec.value?.takeIf { !it.contains('{') && !it.contains('$') }
                    value?.let { spec.name to it }
                }.toMap()
                val server = saveServer(null, item.name, url, headers, enabled = true, installedItemId = lockId)
                refresh(server.id)
                InstallResult(0, 1, 0)
            }
        }
    }

    private suspend fun removeInstalled(lockId: String) {
        dao.servers().filter { it.installedItemId == lockId }.forEach { connections.forget(it.id) }
        dao.skills().filter { it.installedItemId == lockId }.forEach { deleteSkill(it) }
        dao.deleteServersForItem(lockId)
        dao.deleteInstalled(lockId)
    }

    suspend fun uninstall(lockId: String) = removeInstalled(lockId)
}
