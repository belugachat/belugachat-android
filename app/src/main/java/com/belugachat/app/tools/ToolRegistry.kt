package com.belugachat.app.tools

import com.belugachat.app.data.AppDatabase
import com.belugachat.app.data.McpServerEntity
import com.belugachat.app.data.ToolPrefEntity
import com.belugachat.app.ext.ExtensionsRepository
import com.belugachat.app.llm.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class AppTool(
    /** Stable key for preferences: `builtin:<name>` or `mcp:<serverId>:<name>`. */
    val key: String,
    /** Name exposed to the model. */
    val name: String,
    val title: String,
    val description: String,
    val schema: JsonObject,
    val group: String,
    val enabled: Boolean,
    val autoApprove: Boolean,
    val serverId: String? = null,
    val remoteName: String? = null,
) {
    fun spec() = ToolSpec(name, description, schema)
}

data class ToolOutcome(val text: String, val isError: Boolean)

class ToolRegistry(
    private val db: AppDatabase,
    private val extensions: ExtensionsRepository,
    private val web: WebTools = WebTools(),
) {
    private data class Builtin(val name: String, val title: String, val description: String, val schema: JsonObject, val auto: Boolean)

    private val builtins = listOf(
        Builtin("current_time", "Current time", "Return the current local date, time and time zone.",
            objectSchema {}, auto = true),
        Builtin("web_search", "Web search", "Search the public web. Returns titles, URLs and snippets.",
            objectSchema {
                putJsonObject("query") { put("type", "string"); put("description", "Search query") }
                putJsonObject("max_results") { put("type", "integer"); put("description", "1-10, default 5") }
            }.withRequired("query"), auto = true),
        Builtin("web_fetch", "Fetch web page", "Fetch a public http(s) URL and return its readable text.",
            objectSchema {
                putJsonObject("url") { put("type", "string"); put("description", "Absolute http(s) URL") }
            }.withRequired("url"), auto = false),
        Builtin(
            "spawn_subagent", "Spawn subagent",
            "Run a focused subagent with its own context to complete a self-contained subtask. It can use all enabled tools " +
                "(including web search, web fetch, MCP tools) and skills, and returns a final result without seeing this conversation.",
            objectSchema {
                putJsonObject("task") { put("type", "string"); put("description", "A complete, self-contained brief for the subagent.") }
                putJsonObject("role") { put("type", "string"); put("description", "Optional role or perspective, e.g. 'security reviewer'.") }
                putJsonObject("max_rounds") {
                    put("type", "integer"); put("description", "Maximum tool rounds before returning, 1-8. Default 4.")
                }
            }.withRequired("task"), auto = true,
        ),
    )

    private val viewSkill = Builtin(
        "view_skill", "Load skill", "Load the full instructions of an available skill, or one of its supporting files.",
        objectSchema {
            putJsonObject("name") { put("type", "string"); put("description", "Skill name") }
            putJsonObject("file") { put("type", "string"); put("description", "Optional supporting file path") }
        }.withRequired("name"), auto = true,
    )

    fun observe(): Flow<List<AppTool>> = combine(db.extensions().observeServers(), db.extensions().observeToolPrefs()) { servers, prefs ->
        assemble(servers, prefs, includeSkills = false)
    }

    private fun assemble(servers: List<McpServerEntity>, prefs: List<ToolPrefEntity>, includeSkills: Boolean): List<AppTool> {
        val byKey = prefs.associateBy { it.toolKey }
        val tools = mutableListOf<AppTool>()
        val list = if (includeSkills) builtins + viewSkill else builtins
        for (b in list) {
            val key = "builtin:${b.name}"
            val pref = byKey[key]
            tools += AppTool(key, b.name, b.title, b.description, b.schema, "Built-in",
                enabled = pref?.enabled ?: true, autoApprove = pref?.autoApprove ?: b.auto)
        }
        val used = tools.map { it.name }.toMutableSet()
        for (server in servers.filter { it.enabled }) {
            for (tool in extensions.tools(server)) {
                val key = "mcp:${server.id}:${tool.name}"
                val pref = byKey[key]
                var name = sanitize("${server.name}_${tool.name}")
                var n = 2
                while (name in used) name = sanitize("${server.name}_${tool.name}").take(60) + "_" + n++
                used += name
                tools += AppTool(key, name, tool.name, tool.description.ifBlank { tool.name }, normalizeSchema(tool.inputSchema),
                    group = server.name, enabled = pref?.enabled ?: true, autoApprove = pref?.autoApprove ?: false,
                    serverId = server.id, remoteName = tool.name)
            }
        }
        return tools
    }

    /** Tools offered to the model for a run. */
    suspend fun active(): List<AppTool> {
        val hasSkills = extensions.enabledSkills().isNotEmpty()
        return assemble(db.extensions().servers(), db.extensions().toolPrefs(), hasSkills).filter { it.enabled }
    }

    suspend fun setPref(tool: AppTool, enabled: Boolean = tool.enabled, autoApprove: Boolean = tool.autoApprove) =
        db.extensions().upsertToolPref(ToolPrefEntity(tool.key, enabled, autoApprove))

    suspend fun execute(tool: AppTool, arguments: String): ToolOutcome {
        val args = runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject }.getOrElse {
            return ToolOutcome("Invalid JSON arguments: ${it.message}", true)
        }
        return try {
            when {
                tool.serverId != null -> {
                    val server = db.extensions().server(tool.serverId) ?: return ToolOutcome("MCP server was removed.", true)
                    val result = extensions.client(server).callTool(tool.remoteName ?: tool.title, args)
                    ToolOutcome(result.text.take(MAX_RESULT), result.isError)
                }
                tool.name == "current_time" -> ToolOutcome(
                    ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss xxx '('VV')'")), false,
                )
                tool.name == "web_search" -> {
                    val query = args.str("query") ?: return ToolOutcome("web_search needs a query.", true)
                    ToolOutcome(web.search(query, args["max_results"]?.jsonPrimitive?.intOrNull ?: 5), false)
                }
                tool.name == "web_fetch" -> {
                    val url = args.str("url") ?: return ToolOutcome("web_fetch needs a url.", true)
                    ToolOutcome(web.fetch(url), false)
                }
                tool.name == "view_skill" -> viewSkill(args.str("name").orEmpty(), args.str("file"))
                else -> ToolOutcome("Unknown tool ${tool.name}.", true)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolOutcome(e.message ?: e.javaClass.simpleName, true)
        }
    }

    private suspend fun viewSkill(name: String, file: String?): ToolOutcome {
        val skill = extensions.enabledSkills().firstOrNull { it.name.equals(name.removePrefix("@"), true) }
            ?: return ToolOutcome("Skill '$name' is not available.", true)
        if (!file.isNullOrBlank()) {
            return extensions.readSkillFile(skill, file)?.let { ToolOutcome(it, false) }
                ?: ToolOutcome("File '$file' is not part of skill ${skill.name}.", true)
        }
        val files = extensions.skillFiles(skill)
        val manifest = if (files.isEmpty()) "None" else files.take(80).joinToString("\n") { "- $it" }
        return ToolOutcome("# Skill: ${skill.name}\n\n${skill.body}\n\n## Supporting files (load with view_skill + file)\n$manifest".take(MAX_RESULT), false)
    }

    companion object {
        private const val MAX_RESULT = 60_000

        fun sanitize(value: String): String =
            value.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(64).ifEmpty { "tool" }

        private fun objectSchema(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties", block)
        }

        private fun JsonObject.withRequired(vararg names: String): JsonObject =
            JsonObject(this + ("required" to JsonArray(names.map { JsonPrimitive(it) })))

        private fun normalizeSchema(schema: JsonObject): JsonObject {
            if (schema["type"] != null) return schema
            return JsonObject(schema + ("type" to JsonPrimitive("object")))
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
